package com.jun.labs.paymentconsistency.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * PG 연결 실패(아무도 듣지 않는 포트) — ADR-000 §3 기술 실패 롤백 · §6 502 PG_UNAVAILABLE(연결 실패).
 */
class PgConnectionFailureBlindTest : BlindAppTestBase() {

    override fun mockPg(): MockPgInstance? = null

    @Test
    fun `PG 연결 실패 - 502 PG_UNAVAILABLE, 결제 행 없음`() {
        val orderId = newOrderId()
        val before = totalRowCount()

        val res = approve(orderId = orderId, amount = 10_000L)

        assertThat(res.status).describedAs(res.body).isEqualTo(502)
        assertThat(res.code).isEqualTo("PG_UNAVAILABLE")
        assertThat(rowCount(orderId)).isEqualTo(0L)
        assertThat(totalRowCount()).isEqualTo(before)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            BlindPostgres.register(registry)
            // 포트 1 — 로컬에서 듣는 프로세스가 없어 즉시 연결 거부
            registry.add("payment.pg.base-url") { "http://localhost:1" }
            registry.add("payment.pg.connect-timeout") { "1s" }
        }
    }
}
