package com.jun.labs.mockpg.blind

import com.jun.labs.mockpg.MockPgApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 주문번호 중복 거절 off — ADR-000 §7 "false면 같은 orderId로 여러 번 승인(이중 승인 관측용)" · "주문번호 조회는 가장 최근 승인".
 */
@SpringBootTest(
    classes = [MockPgApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mockpg.reject-duplicate-order-id=false",
        "mockpg.fault.latency-min-ms=0",
        "mockpg.fault.latency-max-ms=0",
        "mockpg.fault.error-rate=0",
        "mockpg.fault.drop-rate=0",
    ],
)
class MockPgDuplicateAllowedBlindTest : MockPgBlindTestBase() {

    @Test
    fun `같은 orderId 두 번 승인 - 둘 다 200, 다른 paymentKey, 주문번호 조회는 가장 최근 승인`() {
        val orderId = newOrderId()
        val first = approveOk(orderId, 1_000L)
        val second = approveOk(orderId, 2_000L)
        val firstKey = first.textOrNull("paymentKey")!!
        val secondKey = second.textOrNull("paymentKey")!!
        assertThat(firstKey).isNotEqualTo(secondKey)

        val latest = byOrder(orderId)
        assertThat(latest.status).isEqualTo(200)
        assertThat(latest.json.textOrNull("paymentKey")).isEqualTo(secondKey)
        assertThat(latest.json["totalAmount"].asLong()).isEqualTo(2_000L)

        // 두 승인 모두 paymentKey로 남아 있다
        assertPayment(byKey(firstKey), "DONE", 1_000L, 1_000L, 0)
        assertPayment(byKey(secondKey), "DONE", 2_000L, 2_000L, 0)
    }

    @Test
    fun `같은 orderId 동시 10건 - 전부 승인(이중 승인), paymentKey 10개`() {
        val orderId = newOrderId()
        val n = 10
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (1..n).map {
                pool.submit(Callable {
                    ready.countDown()
                    go.await()
                    approve(orderId, 1_000L)
                })
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            go.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertThat(results.map { it.status }).describedAs(results.toString()).containsOnly(200)
            assertThat(results.map { it.json.textOrNull("paymentKey") }.toSet()).hasSize(n)
        } finally {
            pool.shutdownNow()
        }
    }
}
