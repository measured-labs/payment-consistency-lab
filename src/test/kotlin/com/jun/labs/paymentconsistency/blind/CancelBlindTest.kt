package com.jun.labs.paymentconsistency.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 취소 — 흉내 PG 장애 0.
 * spec §2 취소 불변식(누적 ≤ 승인액 · CANCELED/PARTIALLY_CANCELED · 앱 = PG 누적 · 거부 시 상태 불변)
 * · ADR-000 §2.1 ②③⑤ · §3 · §6(취소 · 판정 순서 · 앱은 PG에 금액을 명시) · §7 취소.
 */
class CancelBlindTest : BlindAppTestBase() {

    override fun mockPg(): MockPgInstance = PG.get()

    @Test
    fun `부분 취소 두 번 - PARTIALLY_CANCELED, 누적 = 취소 내역 합, PG 남은 금액 일치`() {
        val approved = approveOk(amount = 10_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!

        val first = cancel(id, json("cancelAmount" to 3_000L, "reason" to "부분1"))
        assertThat(first.status).describedAs(first.body).isEqualTo(200)
        assertThat(first.json.textOrNull("status")).isEqualTo("PARTIALLY_CANCELED")
        assertThat(first.json["canceledAmount"].asLong()).isEqualTo(3_000L)
        assertThat(first.json["cancels"].size()).isEqualTo(1)
        assertThat(first.json["cancels"][0]["amount"].asLong()).isEqualTo(3_000L)
        assertThat(first.json["cancels"][0].textOrNull("reason")).isEqualTo("부분1")
        assertThat(first.json["cancels"][0].textOrNull("canceledAt")).isNotNull()

        val second = cancel(id, json("cancelAmount" to 2_000L))
        assertThat(second.status).describedAs(second.body).isEqualTo(200)
        assertThat(second.json.textOrNull("status")).isEqualTo("PARTIALLY_CANCELED")
        assertThat(second.json["canceledAmount"].asLong()).isEqualTo(5_000L)
        assertThat(second.json["cancels"].size()).isEqualTo(2)

        // DB: canceled_amount = Σ payment_cancel.amount
        val row = paymentRow(id)
        assertThat(row["status"]).isEqualTo("PARTIALLY_CANCELED")
        assertThat((row["canceled_amount"] as Number).toLong()).isEqualTo(5_000L)
        assertThat(cancelRowSum(id)).isEqualTo(5_000L)
        assertThat(cancelRowCount(id)).isEqualTo(2L)

        // PG: 남은 금액 = 승인액 − 앱 누적 취소액
        val pgNow = pgByKey(key)
        assertThat(pgNow.status).isEqualTo(200)
        assertThat(pgNow.json.textOrNull("status")).isEqualTo("PARTIAL_CANCELED")
        assertThat(pgNow.json["balanceAmount"].asLong()).isEqualTo(10_000L - 5_000L)
        assertThat(pgNow.json["cancels"].size()).isEqualTo(2)

        // 남은 금액 생략 취소 → CANCELED
        val rest = cancel(id, "{}")
        assertThat(rest.status).describedAs(rest.body).isEqualTo(200)
        assertThat(rest.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(rest.json["canceledAmount"].asLong()).isEqualTo(10_000L)
        assertThat(rest.json["cancels"].size()).isEqualTo(3)
        assertThat(rest.json["cancels"][2]["amount"].asLong()).isEqualTo(5_000L)
        assertThat(cancelRowSum(id)).isEqualTo(10_000L)
        assertPgUnchanged(key, "CANCELED", 0L, 3)
    }

    @Test
    fun `부분 취소로 남은 금액을 정확히 0으로 만들면 CANCELED`() {
        val approved = approveOk(amount = 10_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!

        assertThat(cancel(id, json("cancelAmount" to 4_000L)).status).isEqualTo(200)
        val last = cancel(id, json("cancelAmount" to 6_000L))
        assertThat(last.status).describedAs(last.body).isEqualTo(200)
        assertThat(last.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(last.json["canceledAmount"].asLong()).isEqualTo(10_000L)
        assertAppUnchanged(id, "CANCELED", 10_000L, 2)
        assertPgUnchanged(key, "CANCELED", 0L, 2)
    }

    @Test
    fun `전체 취소(금액 생략) - CANCELED, 누적 = 승인액, PG CANCELED 남은 금액 0`() {
        val approved = approveOk(amount = 8_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!

        val res = cancel(id, json("reason" to "고객 요청"))
        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(res.json["canceledAmount"].asLong()).isEqualTo(8_000L)
        assertThat(res.json["cancels"].size()).isEqualTo(1)
        assertThat(res.json["cancels"][0]["amount"].asLong()).isEqualTo(8_000L)
        assertThat(res.json["cancels"][0].textOrNull("reason")).isEqualTo("고객 요청")

        assertAppUnchanged(id, "CANCELED", 8_000L, 1)
        assertThat(cancelRowSum(id)).isEqualTo(8_000L)
        assertPgUnchanged(key, "CANCELED", 0L, 1)
    }

    @Test
    fun `reason 200자는 허용된다`() {
        val approved = approveOk(amount = 1_000L)
        val res = cancel(approved["paymentId"].asLong(), json("cancelAmount" to 100L, "reason" to "r".repeat(200)))
        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["cancels"][0].textOrNull("reason")).hasSize(200)
    }

    @Test
    fun `승인액 초과 취소 - 409 CANCEL_AMOUNT_EXCEEDED, 상태 불변, PG 미호출`() {
        val approved = approveOk(amount = 10_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!

        val res = cancel(id, json("cancelAmount" to 10_001L))
        assertThat(res.status).describedAs(res.body).isEqualTo(409)
        assertThat(res.code).isEqualTo("CANCEL_AMOUNT_EXCEEDED")

        assertAppUnchanged(id, "APPROVED", 0L, 0)
        assertPgUnchanged(key, "DONE", 10_000L, 0)
    }

    @Test
    fun `부분 취소 뒤 남은 금액 초과 취소 - 409 CANCEL_AMOUNT_EXCEEDED, 상태 불변, PG 미호출`() {
        val approved = approveOk(amount = 10_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!
        assertThat(cancel(id, json("cancelAmount" to 6_000L)).status).isEqualTo(200)

        val res = cancel(id, json("cancelAmount" to 4_001L))
        assertThat(res.status).describedAs(res.body).isEqualTo(409)
        assertThat(res.code).isEqualTo("CANCEL_AMOUNT_EXCEEDED")

        assertAppUnchanged(id, "PARTIALLY_CANCELED", 6_000L, 1)
        assertPgUnchanged(key, "PARTIAL_CANCELED", 4_000L, 1)
    }

    @Test
    fun `FAILED 결제 취소 - 409 PAYMENT_NOT_CANCELABLE, 상태 불변`() {
        val failed = approveDeclined()
        val id = failed["paymentId"].asLong()

        val res = cancel(id, "{}")
        assertThat(res.status).describedAs(res.body).isEqualTo(409)
        assertThat(res.code).isEqualTo("PAYMENT_NOT_CANCELABLE")
        assertAppUnchanged(id, "FAILED", 0L, 0)
    }

    @Test
    fun `이미 전부 취소된 결제 취소 - 409 PAYMENT_NOT_CANCELABLE, 상태 불변, PG 미호출`() {
        val approved = approveOk(amount = 3_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!
        assertThat(cancel(id, "{}").status).isEqualTo(200)

        val again = cancel(id, json("cancelAmount" to 1L))
        assertThat(again.status).describedAs(again.body).isEqualTo(409)
        assertThat(again.code).isEqualTo("PAYMENT_NOT_CANCELABLE")
        val againOmitted = cancel(id, "{}")
        assertThat(againOmitted.status).isEqualTo(409)
        assertThat(againOmitted.code).isEqualTo("PAYMENT_NOT_CANCELABLE")

        assertAppUnchanged(id, "CANCELED", 3_000L, 1)
        assertPgUnchanged(key, "CANCELED", 0L, 1)
    }

    @Test
    fun `없는 결제 취소 - 404 PAYMENT_NOT_FOUND`() {
        val res = cancel(Long.MAX_VALUE, json("cancelAmount" to 1_000L))
        assertThat(res.status).isEqualTo(404)
        assertThat(res.code).isEqualTo("PAYMENT_NOT_FOUND")
    }

    @Test
    fun `우리 스냅샷은 통과했지만 PG가 거절 - 409 PG_REJECTED, 앱 상태 불변`() {
        val approved = approveOk(amount = 9_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!

        // 앱을 거치지 않고 PG에서 직접 전부 취소 — 우리 DB는 여전히 APPROVED
        val direct = pg.post("/v1/payments/$key/cancel", json("cancelReason" to "direct"))
        assertThat(direct.status).describedAs(direct.body).isEqualTo(200)

        val res = cancel(id, "{}")
        assertThat(res.status).describedAs(res.body).isEqualTo(409)
        assertThat(res.code).isEqualTo("PG_REJECTED")

        assertAppUnchanged(id, "APPROVED", 0L, 0)
        assertPgUnchanged(key, "CANCELED", 0L, 1)
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
