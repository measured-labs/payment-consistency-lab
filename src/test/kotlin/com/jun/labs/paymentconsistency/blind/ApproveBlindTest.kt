package com.jun.labs.paymentconsistency.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 승인 정상 경로 — 흉내 PG 장애 0 · 주문번호 중복 거절 on(기본).
 * spec §2 정상 경로 불변식(승인 성공 · 업무 거절) · ADR-000 §2.1 ④⑤ · §3 · §6 · §7.
 */
class ApproveBlindTest : BlindAppTestBase() {

    override fun mockPg(): MockPgInstance = PG.get()

    @Test
    fun `승인 성공 - 201 APPROVED, 응답 · DB · PG가 같은 paymentKey와 금액을 가진다`() {
        val orderId = newOrderId()
        val res = approve(orderId = orderId, amount = 15_000L, userId = "42")

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        val body = res.json
        val paymentId = body["paymentId"].asLong()
        val paymentKey = body.textOrNull("paymentKey")
        assertThat(body.textOrNull("status")).isEqualTo("APPROVED")
        assertThat(body.textOrNull("orderId")).isEqualTo(orderId)
        assertThat(body["userId"].asLong()).isEqualTo(42L)
        assertThat(body["amount"].asLong()).isEqualTo(15_000L)
        assertThat(body["canceledAmount"].asLong()).isEqualTo(0L)
        assertThat(paymentKey).isNotBlank()
        assertThat(body.textOrNull("approvedAt")).isNotNull()
        assertThat(body.textOrNull("requestedAt")).isNotNull()
        assertThat(body.textOrNull("failureCode")).isNull()
        assertThat(body["cancels"].size()).isEqualTo(0)

        // DB 행
        val row = paymentRow(paymentId)
        assertThat(row["order_id"]).isEqualTo(orderId)
        assertThat((row["user_id"] as Number).toLong()).isEqualTo(42L)
        assertThat((row["amount"] as Number).toLong()).isEqualTo(15_000L)
        assertThat((row["canceled_amount"] as Number).toLong()).isEqualTo(0L)
        assertThat(row["status"]).isEqualTo("APPROVED")
        assertThat(row["payment_key"]).isEqualTo(paymentKey)
        assertThat(row["approved_at"]).isNotNull()
        assertThat(row["failure_code"]).isNull()
        assertThat(rowCount(orderId)).isEqualTo(1L)

        // PG — paymentKey 조회
        val byKey = pgByKey(paymentKey!!)
        assertThat(byKey.status).isEqualTo(200)
        assertThat(byKey.json.textOrNull("paymentKey")).isEqualTo(paymentKey)
        assertThat(byKey.json.textOrNull("orderId")).isEqualTo(orderId)
        assertThat(byKey.json.textOrNull("status")).isEqualTo("DONE")
        assertThat(byKey.json["totalAmount"].asLong()).isEqualTo(15_000L)
        assertThat(byKey.json["balanceAmount"].asLong()).isEqualTo(15_000L)

        // PG — 주문번호 조회
        val byOrder = pgByOrder(orderId)
        assertThat(byOrder.status).isEqualTo(200)
        assertThat(byOrder.json.textOrNull("paymentKey")).isEqualTo(paymentKey)
        assertThat(byOrder.json["totalAmount"].asLong()).isEqualTo(15_000L)

        // 앱 조회 = 승인 응답
        val fetched = getPayment(paymentId)
        assertThat(fetched.status).isEqualTo(200)
        assertThat(fetched.json["paymentId"].asLong()).isEqualTo(paymentId)
        assertThat(fetched.json.textOrNull("paymentKey")).isEqualTo(paymentKey)
        assertThat(fetched.json.textOrNull("status")).isEqualTo("APPROVED")
        assertThat(fetched.json["amount"].asLong()).isEqualTo(15_000L)
    }

    @Test
    fun `경계값 - orderId 64자 · cardNumber 32자 · amount 1은 승인된다`() {
        val orderId = "o".repeat(64)
        val res = approve(orderId = orderId, amount = 1L, cardNumber = "4".repeat(32))
        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertThat(res.json.textOrNull("status")).isEqualTo("APPROVED")
        assertThat(res.json["amount"].asLong()).isEqualTo(1L)
        assertThat(pgByOrder(orderId).status).isEqualTo(200)
    }

    @Test
    fun `거절 카드 CARD_DECLINED - 201 FAILED, failureCode 기록, 승인액 0, PG 승인 없음`() {
        assertDeclined(DECLINED_CARD, "CARD_DECLINED")
    }

    @Test
    fun `잔액 부족 카드 INSUFFICIENT_FUNDS - 201 FAILED, failureCode 기록, 승인액 0, PG 승인 없음`() {
        assertDeclined(INSUFFICIENT_FUNDS_CARD, "INSUFFICIENT_FUNDS")
    }

    private fun assertDeclined(card: String, expectedCode: String) {
        val orderId = newOrderId()
        val res = approve(orderId = orderId, amount = 7_000L, cardNumber = card)

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        val body = res.json
        val paymentId = body["paymentId"].asLong()
        assertThat(body.textOrNull("status")).isEqualTo("FAILED")
        assertThat(body.textOrNull("failureCode")).isEqualTo(expectedCode)
        assertThat(body.textOrNull("paymentKey")).isNull()
        assertThat(body["canceledAmount"].asLong()).isEqualTo(0L)
        assertThat(body["cancels"].size()).isEqualTo(0)

        val row = paymentRow(paymentId)
        assertThat(row["status"]).isEqualTo("FAILED")
        assertThat(row["failure_code"]).isEqualTo(expectedCode)
        assertThat(row["payment_key"]).isNull()
        assertThat(row["approved_at"]).isNull()
        assertThat(cancelRowCount(paymentId)).isEqualTo(0L)
        assertThat(rowCount(orderId)).isEqualTo(1L)

        // PG에 이 주문번호의 승인이 없다
        val byOrder = pgByOrder(orderId)
        assertThat(byOrder.status).isEqualTo(404)
        assertThat(byOrder.code).isEqualTo("NOT_FOUND_PAYMENT")

        val fetched = getPayment(paymentId)
        assertThat(fetched.status).isEqualTo(200)
        assertThat(fetched.json.textOrNull("status")).isEqualTo("FAILED")
        assertThat(fetched.json.textOrNull("failureCode")).isEqualTo(expectedCode)
    }

    @Test
    fun `같은 orderId 재요청 - 두 번째는 201 FAILED DUPLICATED_ORDER_ID, 기준선은 막지 않아 DB에 2행`() {
        val orderId = newOrderId()
        val first = approve(orderId = orderId, amount = 5_000L)
        assertThat(first.status).describedAs(first.body).isEqualTo(201)
        assertThat(first.json.textOrNull("status")).isEqualTo("APPROVED")
        val firstKey = first.json.textOrNull("paymentKey")

        val second = approve(orderId = orderId, amount = 5_000L)
        assertThat(second.status).describedAs(second.body).isEqualTo(201)
        assertThat(second.json.textOrNull("status")).isEqualTo("FAILED")
        assertThat(second.json.textOrNull("failureCode")).isEqualTo("DUPLICATED_ORDER_ID")
        assertThat(second.json["paymentId"].asLong()).isNotEqualTo(first.json["paymentId"].asLong())

        // 기준선은 중복을 막지 않는다 — 같은 주문번호로 행 2개(ADR-000 §2 "결제 1행 = 시도 1건")
        assertThat(rowCount(orderId)).isEqualTo(2L)
        val statuses = jdbc.queryForList("select status from payment where order_id = ?", String::class.java, orderId)
        assertThat(statuses).containsExactlyInAnyOrder("APPROVED", "FAILED")

        // PG에는 승인 1건(첫 번째)
        val byOrder = pgByOrder(orderId)
        assertThat(byOrder.status).isEqualTo(200)
        assertThat(byOrder.json.textOrNull("paymentKey")).isEqualTo(firstKey)
    }

    @Test
    fun `없는 결제 조회는 404 PAYMENT_NOT_FOUND`() {
        val res = getPayment(Long.MAX_VALUE)
        assertThat(res.status).isEqualTo(404)
        assertThat(res.code).isEqualTo("PAYMENT_NOT_FOUND")
    }

    companion object {
        private val PG = MockPgHolder()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            BlindPostgres.register(registry)
            registry.add("payment.pg.base-url") { PG.get().baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun stopPg() = PG.close()
    }
}
