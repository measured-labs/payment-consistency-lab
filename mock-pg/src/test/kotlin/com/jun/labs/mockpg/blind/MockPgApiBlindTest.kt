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
 * 흉내 PG HTTP 계약 — ADR-000 §7 표 · 주문번호 중복 거절(on, 원자적) · 거절 시험 카드 · 취소 · 조회.
 */
@SpringBootTest(
    classes = [MockPgApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mockpg.reject-duplicate-order-id=true",
        "mockpg.fault.latency-min-ms=0",
        "mockpg.fault.latency-max-ms=0",
        "mockpg.fault.error-rate=0",
        "mockpg.fault.drop-rate=0",
    ],
)
class MockPgApiBlindTest : MockPgBlindTestBase() {

    // ---- 승인 ----

    @Test
    fun `승인 - 200 DONE, paymentKey 발급, totalAmount = balanceAmount = 요청 금액, cancels 비어 있음`() {
        val orderId = newOrderId()
        val res = approve(orderId, 15_000L)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("paymentKey")).isNotBlank()
        assertThat(res.json.textOrNull("orderId")).isEqualTo(orderId)
        assertThat(res.json.textOrNull("approvedAt")).isNotNull()
        assertPayment(res, "DONE", 15_000L, 15_000L, 0)
    }

    @Test
    fun `승인마다 paymentKey가 다르다`() {
        val a = approveOk().textOrNull("paymentKey")
        val b = approveOk().textOrNull("paymentKey")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `거절 카드 - 400 CARD_DECLINED, 승인 기록 없음`() {
        val orderId = newOrderId()
        val res = approve(orderId, card = DECLINED_CARD)
        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("CARD_DECLINED")
        assertThat(byOrder(orderId).status).isEqualTo(404)
    }

    @Test
    fun `잔액 부족 카드 - 400 INSUFFICIENT_FUNDS, 승인 기록 없음`() {
        val orderId = newOrderId()
        val res = approve(orderId, card = INSUFFICIENT_FUNDS_CARD)
        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("INSUFFICIENT_FUNDS")
        assertThat(byOrder(orderId).status).isEqualTo(404)
    }

    @Test
    fun `잘못된 승인 요청(금액 0) - 400 INVALID_REQUEST`() {
        val orderId = newOrderId()
        val res = approve(orderId, amount = 0L)
        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("INVALID_REQUEST")
        assertThat(byOrder(orderId).status).isEqualTo(404)
    }

    @Test
    fun `주문번호 중복(on) - 두 번째 승인은 400 DUPLICATED_ORDER_ID, 조회는 첫 승인`() {
        val orderId = newOrderId()
        val first = approveOk(orderId, 5_000L)

        val second = approve(orderId, 5_000L)
        assertThat(second.status).describedAs(second.body).isEqualTo(400)
        assertThat(second.code).isEqualTo("DUPLICATED_ORDER_ID")

        val found = byOrder(orderId)
        assertThat(found.status).isEqualTo(200)
        assertThat(found.json.textOrNull("paymentKey")).isEqualTo(first.textOrNull("paymentKey"))
    }

    @Test
    fun `주문번호 중복(on) - 같은 orderId 동시 10건이어도 승인은 정확히 1건`() {
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

            val ok = results.filter { it.status == 200 }
            val dup = results.filter { it.status == 400 && it.code == "DUPLICATED_ORDER_ID" }
            assertThat(ok).describedAs(results.toString()).hasSize(1)
            assertThat(dup).describedAs(results.toString()).hasSize(n - 1)

            val found = byOrder(orderId)
            assertThat(found.status).isEqualTo(200)
            assertThat(found.json.textOrNull("paymentKey")).isEqualTo(ok.single().json.textOrNull("paymentKey"))
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- 취소 ----

    @Test
    fun `부분 취소 - PARTIAL_CANCELED, balanceAmount 감소, cancels 추가`() {
        val key = approveOk(amount = 10_000L).textOrNull("paymentKey")!!

        val first = cancel(key, pgJson("cancelAmount" to 3_000L, "cancelReason" to "부분"))
        assertPayment(first, "PARTIAL_CANCELED", 10_000L, 7_000L, 1)

        val second = cancel(key, pgJson("cancelAmount" to 2_000L))
        assertPayment(second, "PARTIAL_CANCELED", 10_000L, 5_000L, 2)

        assertPayment(byKey(key), "PARTIAL_CANCELED", 10_000L, 5_000L, 2)
    }

    @Test
    fun `부분 취소로 남은 금액을 정확히 0으로 만들면 CANCELED`() {
        val key = approveOk(amount = 10_000L).textOrNull("paymentKey")!!
        assertPayment(cancel(key, pgJson("cancelAmount" to 4_000L)), "PARTIAL_CANCELED", 10_000L, 6_000L, 1)
        assertPayment(cancel(key, pgJson("cancelAmount" to 6_000L)), "CANCELED", 10_000L, 0L, 2)
    }

    @Test
    fun `전체 금액 명시 취소 - CANCELED, balanceAmount 0`() {
        val key = approveOk(amount = 8_000L).textOrNull("paymentKey")!!
        assertPayment(cancel(key, pgJson("cancelAmount" to 8_000L)), "CANCELED", 8_000L, 0L, 1)
        assertPayment(byKey(key), "CANCELED", 8_000L, 0L, 1)
    }

    @Test
    fun `금액 생략 취소 - 남은 금액 전부, CANCELED`() {
        val key = approveOk(amount = 10_000L).textOrNull("paymentKey")!!
        assertPayment(cancel(key, pgJson("cancelAmount" to 4_000L)), "PARTIAL_CANCELED", 10_000L, 6_000L, 1)

        val rest = cancel(key, pgJson("cancelReason" to "나머지"))
        assertPayment(rest, "CANCELED", 10_000L, 0L, 2)
    }

    @Test
    fun `금액 생략 취소(빈 본문 객체) - 승인 직후면 전액 CANCELED`() {
        val key = approveOk(amount = 3_000L).textOrNull("paymentKey")!!
        assertPayment(cancel(key, "{}"), "CANCELED", 3_000L, 0L, 1)
    }

    @Test
    fun `남은 금액 초과 취소 - 400 EXCEED_CANCEL_AMOUNT, 기록 불변`() {
        val key = approveOk(amount = 10_000L).textOrNull("paymentKey")!!
        val res = cancel(key, pgJson("cancelAmount" to 10_001L))
        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("EXCEED_CANCEL_AMOUNT")
        assertPayment(byKey(key), "DONE", 10_000L, 10_000L, 0)

        assertPayment(cancel(key, pgJson("cancelAmount" to 6_000L)), "PARTIAL_CANCELED", 10_000L, 4_000L, 1)
        val over = cancel(key, pgJson("cancelAmount" to 4_001L))
        assertThat(over.status).isEqualTo(400)
        assertThat(over.code).isEqualTo("EXCEED_CANCEL_AMOUNT")
        assertPayment(byKey(key), "PARTIAL_CANCELED", 10_000L, 4_000L, 1)
    }

    @Test
    fun `이미 전부 취소된 결제 취소 - 400 ALREADY_CANCELED, 기록 불변`() {
        val key = approveOk(amount = 2_000L).textOrNull("paymentKey")!!
        assertPayment(cancel(key, "{}"), "CANCELED", 2_000L, 0L, 1)

        val omitted = cancel(key, "{}")
        assertThat(omitted.status).describedAs(omitted.body).isEqualTo(400)
        assertThat(omitted.code).isEqualTo("ALREADY_CANCELED")

        val explicit = cancel(key, pgJson("cancelAmount" to 1L))
        assertThat(explicit.status).describedAs(explicit.body).isEqualTo(400)
        assertThat(explicit.code).isEqualTo("ALREADY_CANCELED")

        assertPayment(byKey(key), "CANCELED", 2_000L, 0L, 1)
    }

    @Test
    fun `없는 paymentKey 취소 - 404 NOT_FOUND_PAYMENT`() {
        val res = cancel("no-such-key-" + newOrderId(), pgJson("cancelAmount" to 1_000L))
        assertThat(res.status).isEqualTo(404)
        assertThat(res.code).isEqualTo("NOT_FOUND_PAYMENT")
    }

    // ---- 조회 ----

    @Test
    fun `paymentKey 조회와 주문번호 조회는 같은 승인을 돌려준다`() {
        val orderId = newOrderId()
        val approved = approveOk(orderId, 4_500L)
        val key = approved.textOrNull("paymentKey")!!

        val k = byKey(key)
        assertPayment(k, "DONE", 4_500L, 4_500L, 0)
        assertThat(k.json.textOrNull("orderId")).isEqualTo(orderId)
        assertThat(k.json.textOrNull("paymentKey")).isEqualTo(key)

        val o = byOrder(orderId)
        assertPayment(o, "DONE", 4_500L, 4_500L, 0)
        assertThat(o.json.textOrNull("paymentKey")).isEqualTo(key)
    }

    @Test
    fun `없는 paymentKey · 주문번호 조회 - 404 NOT_FOUND_PAYMENT`() {
        val k = byKey("no-such-key-" + newOrderId())
        assertThat(k.status).isEqualTo(404)
        assertThat(k.code).isEqualTo("NOT_FOUND_PAYMENT")

        val o = byOrder(newOrderId())
        assertThat(o.status).isEqualTo(404)
        assertThat(o.code).isEqualTo("NOT_FOUND_PAYMENT")
    }
}
