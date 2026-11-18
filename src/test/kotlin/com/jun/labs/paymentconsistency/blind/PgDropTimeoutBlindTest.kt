package com.jun.labs.paymentconsistency.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * PG 응답 유실(drop-rate 1.0 · drop-hold 3s · 앱 read-timeout 1s).
 * ADR-000 §3 "PG가 실제로는 승인했어도 우리 DB에는 흔적이 없다" · §6 504 PG_TIMEOUT · §7 drop-rate · §9 Q2/Q6.
 *
 * 이 테스트는 기준선이 남겨 둔 약점을 **문서화된 기준선 동작으로** 고정한다 — 해결 ADR이 들어오면 기대가 바뀐다.
 */
class PgDropTimeoutBlindTest : BlindAppTestBase() {

    override fun mockPg(): MockPgInstance = PG.get()

    @Test
    fun `PG 응답 유실 - 504 PG_TIMEOUT, 결제 행 없음, 그러나 PG에는 승인이 있다(기준선 Q2·Q6)`() {
        val orderId = newOrderId()
        val before = totalRowCount()

        val res = approve(orderId = orderId, amount = 12_345L)

        assertThat(res.status).describedAs(res.body).isEqualTo(504)
        assertThat(res.code).isEqualTo("PG_TIMEOUT")
        assertThat(rowCount(orderId)).isEqualTo(0L)
        assertThat(totalRowCount()).isEqualTo(before)

        // 흉내 PG는 처리(승인 기록)한 뒤 응답을 쥐고 있었다 — 조회에는 장애 주입이 없다
        val byOrder = pgByOrder(orderId)
        assertThat(byOrder.status).describedAs(byOrder.body).isEqualTo(200)
        assertThat(byOrder.json.textOrNull("orderId")).isEqualTo(orderId)
        assertThat(byOrder.json.textOrNull("status")).isEqualTo("DONE")
        assertThat(byOrder.json["totalAmount"].asLong()).isEqualTo(12_345L)
        assertThat(byOrder.json.textOrNull("paymentKey")).isNotBlank()
    }

    companion object {
        private val PG = MockPgHolder(
            "mockpg.fault.drop-rate" to "1.0",
            "mockpg.fault.drop-hold-ms" to "3000",
        )

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            BlindPostgres.register(registry)
            registry.add("payment.pg.base-url") { PG.get().baseUrl }
            registry.add("payment.pg.read-timeout") { "1s" }
        }

        @JvmStatic
        @AfterAll
        fun stopPg() = PG.close()
    }
}
