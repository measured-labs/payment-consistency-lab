package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.support.Http
import com.jun.labs.paymentconsistency.support.HttpResult
import com.jun.labs.paymentconsistency.support.MockPgLauncher
import com.jun.labs.paymentconsistency.support.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/** 앱 ↔ 흉내 PG(실제 HTTP · 장애 0) ↔ PostgreSQL — 정상 경로 · 거부 경로 · 입력 검증 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val app by lazy { Http(rest) }
    private val pg by lazy { Http(rest, MockPgLauncher.baseUrl(mockPg)) }

    private fun newOrderId() = "order-" + UUID.randomUUID()

    private fun approve(orderId: String, amount: Long = 10_000, card: String = "4111111111111111", userId: String? = "1"): HttpResult =
        app.post(
            "/payments",
            """{"orderId":"$orderId","amount":$amount,"cardNumber":"$card"}""",
            userId?.let { mapOf("X-User-Id" to it) } ?: emptyMap(),
        )

    private fun rowCount(orderId: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Int::class.javaObjectType, orderId)!!

    @Test
    fun `승인 성공 - APPROVED · DB와 PG가 같은 paymentKey · 금액`() {
        val orderId = newOrderId()
        val res = approve(orderId, 12_000)

        assertThat(res.status).isEqualTo(201)
        assertThat(res.body["status"].asText()).isEqualTo("APPROVED")
        val paymentKey = res.body["paymentKey"].asText()
        val id = res.body["paymentId"].asLong()

        val row = jdbc.queryForMap("SELECT status, payment_key, amount, canceled_amount, user_id FROM payment WHERE id = ?", id)
        assertThat(row["status"]).isEqualTo("APPROVED")
        assertThat(row["payment_key"]).isEqualTo(paymentKey)
        assertThat(row["amount"]).isEqualTo(12_000L)
        assertThat(row["canceled_amount"]).isEqualTo(0L)
        assertThat(row["user_id"]).isEqualTo(1L)

        val pgPayment = pg.get("/v1/payments/$paymentKey")
        assertThat(pgPayment.status).isEqualTo(200)
        assertThat(pgPayment.body["orderId"].asText()).isEqualTo(orderId)
        assertThat(pgPayment.body["totalAmount"].asLong()).isEqualTo(12_000)
        assertThat(pgPayment.body["status"].asText()).isEqualTo("DONE")

        val got = app.get("/payments/$id")
        assertThat(got.status).isEqualTo(200)
        assertThat(got.body["paymentKey"].asText()).isEqualTo(paymentKey)
        assertThat(got.body["cancels"].size()).isZero()
    }

    @Test
    fun `카드 거절 - 201 FAILED · 사유 기록 · PG에 기록 없음`() {
        val orderId = newOrderId()
        val res = approve(orderId, card = "4000000000000002")

        assertThat(res.status).isEqualTo(201)
        assertThat(res.body["status"].asText()).isEqualTo("FAILED")
        assertThat(res.body["failureCode"].asText()).isEqualTo("CARD_DECLINED")
        assertThat(res.body["paymentKey"].isNull).isTrue()
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String::class.java, orderId)).isEqualTo("FAILED")
        assertThat(pg.get("/v1/payments/orders/$orderId").status).isEqualTo(404)
    }

    @Test
    fun `잔액 부족 - FAILED INSUFFICIENT_FUNDS`() {
        val res = approve(newOrderId(), card = "4000000000009995")
        assertThat(res.status).isEqualTo(201)
        assertThat(res.body["status"].asText()).isEqualTo("FAILED")
        assertThat(res.body["failureCode"].asText()).isEqualTo("INSUFFICIENT_FUNDS")
    }

    @Test
    fun `같은 orderId 재요청 - 새 행이 생기고 PG 중복 거절로 FAILED (기준선은 막지 않는다)`() {
        val orderId = newOrderId()
        assertThat(approve(orderId).body["status"].asText()).isEqualTo("APPROVED")
        val second = approve(orderId)
        assertThat(second.status).isEqualTo(201)
        assertThat(second.body["status"].asText()).isEqualTo("FAILED")
        assertThat(second.body["failureCode"].asText()).isEqualTo("DUPLICATED_ORDER_ID")
        assertThat(rowCount(orderId)).isEqualTo(2)
    }

    @Test
    fun `부분 취소 → 생략 취소 - PARTIALLY_CANCELED → CANCELED · 앱과 PG 누적 취소액 같음`() {
        val approved = approve(newOrderId(), 10_000).body
        val id = approved["paymentId"].asLong()
        val paymentKey = approved["paymentKey"].asText()

        val partial = app.post("/payments/$id/cancel", """{"cancelAmount":3000,"reason":"부분"}""")
        assertThat(partial.status).isEqualTo(200)
        assertThat(partial.body["status"].asText()).isEqualTo("PARTIALLY_CANCELED")
        assertThat(partial.body["canceledAmount"].asLong()).isEqualTo(3_000)

        val full = app.post("/payments/$id/cancel", """{"cancelAmount":null}""")
        assertThat(full.status).isEqualTo(200)
        assertThat(full.body["status"].asText()).isEqualTo("CANCELED")
        assertThat(full.body["canceledAmount"].asLong()).isEqualTo(10_000)
        assertThat(full.body["cancels"].map { it["amount"].asLong() }).containsExactly(3_000L, 7_000L)

        val pgPayment = pg.get("/v1/payments/$paymentKey").body
        assertThat(pgPayment["status"].asText()).isEqualTo("CANCELED")
        assertThat(pgPayment["balanceAmount"].asLong()).isZero()
        assertThat(pgPayment["cancels"].sumOf { it["cancelAmount"].asLong() }).isEqualTo(10_000)

        val rows = jdbc.queryForList("SELECT amount FROM payment_cancel WHERE payment_id = ? ORDER BY id", Long::class.javaObjectType, id)
        assertThat(rows).containsExactly(3_000L, 7_000L)
    }

    @Test
    fun `금액 생략 취소도 PG에는 우리 스냅샷의 남은 금액을 명시한다 - PG만 먼저 줄었으면 409 PG_REJECTED · 상태 불변`() {
        val created = approve(newOrderId(), 10_000).body
        val id = created["paymentId"].asLong()
        val paymentKey = created["paymentKey"].asText()
        // PG에서만 4000 취소 — PG 잔액 6000 · 우리 스냅샷 남은 금액 10000
        assertThat(pg.post("/v1/payments/$paymentKey/cancel", """{"cancelAmount":4000}""").status).isEqualTo(200)

        // 명시(10000)면 PG가 EXCEED_CANCEL_AMOUNT로 거절한다 — 생략해서 보냈다면 PG가 잔액 6000을 취소해 200이었을 것
        val res = app.post("/payments/$id/cancel", "{}")
        assertThat(res.status).isEqualTo(409)
        assertThat(res.body["code"].asText()).isEqualTo("PG_REJECTED")
        val got = app.get("/payments/$id").body
        assertThat(got["status"].asText()).isEqualTo("APPROVED")
        assertThat(got["canceledAmount"].asLong()).isZero()
        assertThat(pg.get("/v1/payments/$paymentKey").body["balanceAmount"].asLong()).isEqualTo(6_000)
    }

    @Test
    fun `본문 없는 취소 = 전체 취소`() {
        val id = approve(newOrderId(), 5_000).body["paymentId"].asLong()
        val res = app.post("/payments/$id/cancel", null)
        assertThat(res.status).isEqualTo(200)
        assertThat(res.body["status"].asText()).isEqualTo("CANCELED")
    }

    @Test
    fun `취소 거부 - 상태 · 금액 · 없음 · 상태 불변`() {
        val failedId = approve(newOrderId(), card = "4000000000000002").body["paymentId"].asLong()
        val notCancelable = app.post("/payments/$failedId/cancel", "{}")
        assertThat(notCancelable.status).isEqualTo(409)
        assertThat(notCancelable.body["code"].asText()).isEqualTo("PAYMENT_NOT_CANCELABLE")

        val id = approve(newOrderId(), 10_000).body["paymentId"].asLong()
        val exceeded = app.post("/payments/$id/cancel", """{"cancelAmount":10001}""")
        assertThat(exceeded.status).isEqualTo(409)
        assertThat(exceeded.body["code"].asText()).isEqualTo("CANCEL_AMOUNT_EXCEEDED")
        assertThat(app.get("/payments/$id").body["status"].asText()).isEqualTo("APPROVED")

        assertThat(app.post("/payments/$id/cancel", null).status).isEqualTo(200)
        val again = app.post("/payments/$id/cancel", null)
        assertThat(again.status).isEqualTo(409)
        assertThat(again.body["code"].asText()).isEqualTo("PAYMENT_NOT_CANCELABLE")

        assertThat(app.post("/payments/${Long.MAX_VALUE}/cancel", null).body["code"].asText()).isEqualTo("PAYMENT_NOT_FOUND")
        assertThat(app.get("/payments/${Long.MAX_VALUE}").status).isEqualTo(404)
    }

    @Test
    fun `입력 검증 위반은 400 INVALID_REQUEST · 행이 생기지 않는다`() {
        val orderId = newOrderId()
        val cases = listOf(
            app.post("/payments", """{"orderId":"$orderId","amount":1.5,"cardNumber":"4111"}""", mapOf("X-User-Id" to "1")),
            app.post("/payments", """{"orderId":"$orderId","amount":"1000","cardNumber":"4111"}""", mapOf("X-User-Id" to "1")),
            app.post("/payments", """{"orderId":"$orderId","amount":0,"cardNumber":"4111"}""", mapOf("X-User-Id" to "1")),
            app.post("/payments", """{"orderId":"$orderId","amount":1000}""", mapOf("X-User-Id" to "1")),
            app.post("/payments", """{"orderId":"$orderId","amount":1000,"cardNumber":"${"1".repeat(33)}"}""", mapOf("X-User-Id" to "1")),
            app.post("/payments", """{"orderId":"${"x".repeat(65)}","amount":1000,"cardNumber":"4111"}""", mapOf("X-User-Id" to "1")),
            app.post("/payments", """{"orderId":"  ","amount":1000,"cardNumber":"4111"}""", mapOf("X-User-Id" to "1")),
            app.post("/payments", """{not json""", mapOf("X-User-Id" to "1")),
            approve(orderId, userId = null),
            approve(orderId, userId = "1.5"),
            approve(orderId, userId = "abc"),
            approve(orderId, userId = "0"),
        )
        cases.forEach {
            assertThat(it.status).isEqualTo(400)
            assertThat(it.body["code"].asText()).isEqualTo("INVALID_REQUEST")
        }
        assertThat(rowCount(orderId)).isZero()
        assertThat(pg.get("/v1/payments/orders/$orderId").status).isEqualTo(404)
    }

    @Test
    fun `취소 · 조회 입력 검증 - 경로 id · cancelAmount · reason`() {
        val id = approve(newOrderId(), 10_000).body["paymentId"].asLong()
        listOf(
            app.post("/payments/abc/cancel", null),
            app.post("/payments/0/cancel", null),
            app.post("/payments/99999999999999999999/cancel", null),
            app.post("/payments/$id/cancel", """{"cancelAmount":0}"""),
            app.post("/payments/$id/cancel", """{"cancelAmount":1.5}"""),
            app.post("/payments/$id/cancel", """{"cancelAmount":"100"}"""),
            app.post("/payments/$id/cancel", """{"reason":"${"r".repeat(201)}"}"""),
            app.get("/payments/-1"),
        ).forEach {
            assertThat(it.status).isEqualTo(400)
            assertThat(it.body["code"].asText()).isEqualTo("INVALID_REQUEST")
        }
        assertThat(app.get("/payments/$id").body["status"].asText()).isEqualTo("APPROVED")
    }

    companion object {
        private val mockPg by lazy { MockPgLauncher.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("payment.pg.base-url") { MockPgLauncher.baseUrl(mockPg) }
        }
    }
}
