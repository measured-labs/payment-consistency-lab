package com.jun.labs.mockpg.blind

import com.jun.labs.mockpg.MockPgApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * drop-rate 1.0 · drop-hold 3s — ADR-000 §7 "처리(승인 · 취소 기록)한 뒤 drop-hold-ms 동안 응답하지 않는다" · 조회는 주입 없음.
 */
@SpringBootTest(
    classes = [MockPgApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mockpg.reject-duplicate-order-id=true",
        "mockpg.fault.latency-min-ms=0",
        "mockpg.fault.latency-max-ms=0",
        "mockpg.fault.error-rate=0",
        "mockpg.fault.drop-rate=1.0",
        "mockpg.fault.drop-hold-ms=3000",
    ],
)
class MockPgDropRateBlindTest : MockPgBlindTestBase() {

    @Test
    fun `승인 응답 유실 - 클라이언트는 타임아웃, 그러나 승인은 기록되어 조회된다`() {
        val orderId = newOrderId()

        assertThatThrownBy { approve(orderId, 7_000L, timeout = Duration.ofSeconds(1)) }
            .isInstanceOf(HttpTimeoutException::class.java)

        val found = byOrder(orderId)
        assertThat(found.status).describedAs(found.body).isEqualTo(200)
        assertThat(found.json.textOrNull("orderId")).isEqualTo(orderId)
        assertPayment(found, "DONE", 7_000L, 7_000L, 0)
    }

    @Test
    fun `취소 응답 유실 - 클라이언트는 타임아웃, 그러나 취소는 기록된다`() {
        val orderId = newOrderId()
        assertThatThrownBy { approve(orderId, 5_000L, timeout = Duration.ofSeconds(1)) }
            .isInstanceOf(HttpTimeoutException::class.java)
        val key = byOrder(orderId).json.textOrNull("paymentKey")!!

        assertThatThrownBy {
            send("POST", "/v1/payments/$key/cancel", pgJson("cancelAmount" to 2_000L), Duration.ofSeconds(1))
        }.isInstanceOf(HttpTimeoutException::class.java)

        assertPayment(byKey(key), "PARTIAL_CANCELED", 5_000L, 3_000L, 1)
    }
}
