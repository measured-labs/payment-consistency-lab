package com.jun.labs.paymentconsistency.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * PG 5xx(error-rate 1.0) — ADR-000 §3 "PG 기술 실패 → 트랜잭션 전체 롤백 · 행 없음" · §6 502 PG_UNAVAILABLE · §7 error-rate.
 */
class PgErrorBlindTest : BlindAppTestBase() {

    override fun mockPg(): MockPgInstance = PG.get()

    @Test
    fun `PG 5xx - 502 PG_UNAVAILABLE, 결제 행 없음, PG에도 기록 없음`() {
        val orderId = newOrderId()
        val before = totalRowCount()

        val res = approve(orderId = orderId, amount = 10_000L)

        assertThat(res.status).describedAs(res.body).isEqualTo(502)
        assertThat(res.code).isEqualTo("PG_UNAVAILABLE")
        assertThat(rowCount(orderId)).isEqualTo(0L)
        assertThat(totalRowCount()).isEqualTo(before)
        // error-rate는 처리하지 않고 500 — PG에도 승인이 없다
        assertThat(pgByOrder(orderId).status).isEqualTo(404)
    }

    companion object {
        private val PG = MockPgHolder("mockpg.fault.error-rate" to "1.0")

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
