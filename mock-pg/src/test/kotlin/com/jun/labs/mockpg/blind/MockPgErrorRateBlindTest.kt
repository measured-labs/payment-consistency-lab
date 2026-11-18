package com.jun.labs.mockpg.blind

import com.jun.labs.mockpg.MockPgApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * error-rate 1.0 — ADR-000 §7 "이 확률로 처리하지 않고 500" · 조회는 주입 없음.
 */
@SpringBootTest(
    classes = [MockPgApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mockpg.reject-duplicate-order-id=true",
        "mockpg.fault.latency-min-ms=0",
        "mockpg.fault.latency-max-ms=0",
        "mockpg.fault.error-rate=1.0",
        "mockpg.fault.drop-rate=0",
    ],
)
class MockPgErrorRateBlindTest : MockPgBlindTestBase() {

    @Test
    fun `승인 - 500, 처리하지 않으므로 주문번호 조회 404`() {
        val orderId = newOrderId()
        val res = approve(orderId, 10_000L)
        assertThat(res.status).describedAs(res.body).isEqualTo(500)

        val found = byOrder(orderId)
        assertThat(found.status).isEqualTo(404)
        assertThat(found.code).isEqualTo("NOT_FOUND_PAYMENT")
    }

    @Test
    fun `처리하지 않았으므로 같은 orderId 재시도도 중복이 아니라 다시 500`() {
        val orderId = newOrderId()
        assertThat(approve(orderId, 10_000L).status).isEqualTo(500)
        assertThat(approve(orderId, 10_000L).status).isEqualTo(500)
        assertThat(byOrder(orderId).status).isEqualTo(404)
    }
}
