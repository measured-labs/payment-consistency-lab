package com.jun.labs.mockpg

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PgPaymentStoreTest {
    private fun store(rejectDuplicate: Boolean = true) =
        PgPaymentStore(MockPgProperties(rejectDuplicateOrderId = rejectDuplicate), Clock.systemUTC())

    private fun rejectionCode(block: () -> Unit): String? =
        runCatching(block).exceptionOrNull()?.let { (it as PgRejection).code }

    @Test
    fun `승인 - DONE · 잔액 = 총액 · paymentKey · orderId 조회`() {
        val store = store()
        val p = store.approve("o-1", 10_000, "4111")
        assertThat(p.status).isEqualTo(PgStatus.DONE)
        assertThat(p.balanceAmount).isEqualTo(10_000)
        assertThat(store.findByPaymentKey(p.paymentKey)).isEqualTo(p)
        assertThat(store.findByOrderId("o-1").paymentKey).isEqualTo(p.paymentKey)
    }

    @Test
    fun `시험 카드 거절은 기록하지 않는다 - 같은 orderId로 다시 승인된다`() {
        val store = store()
        assertThat(rejectionCode { store.approve("o-1", 1, PgPaymentStore.DECLINED_CARD) }).isEqualTo("CARD_DECLINED")
        assertThat(rejectionCode { store.approve("o-1", 1, PgPaymentStore.INSUFFICIENT_FUNDS_CARD) }).isEqualTo("INSUFFICIENT_FUNDS")
        assertThat(rejectionCode { store.findByOrderId("o-1") }).isEqualTo("NOT_FOUND_PAYMENT")
        assertThat(store.approve("o-1", 1, "4111").status).isEqualTo(PgStatus.DONE)
    }

    @Test
    fun `주문번호 중복 거절은 원자적 - 동시 10건 중 승인 1건`() {
        val store = store()
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val futures = (1..10).map {
            pool.submit(Callable { start.await(); runCatching { store.approve("o-dup", 1_000, "4111") } })
        }
        start.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.mapNotNull { (it.exceptionOrNull() as? PgRejection)?.code }).hasSize(9).containsOnly("DUPLICATED_ORDER_ID")
    }

    @Test
    fun `중복 거절을 끄면 같은 orderId로 여러 번 승인 · 조회는 가장 최근 승인`() {
        val store = store(rejectDuplicate = false)
        store.approve("o-1", 1_000, "4111")
        val second = store.approve("o-1", 2_000, "4111")
        assertThat(store.findByOrderId("o-1").paymentKey).isEqualTo(second.paymentKey)
    }

    @Test
    fun `부분 취소 → 생략 취소(남은 금액 전부) → 이미 취소`() {
        val store = store()
        val key = store.approve("o-1", 10_000, "4111").paymentKey
        val partial = store.cancel(key, 3_000, "부분")
        assertThat(partial.status).isEqualTo(PgStatus.PARTIAL_CANCELED)
        assertThat(partial.balanceAmount).isEqualTo(7_000)

        assertThat(rejectionCode { store.cancel(key, 7_001, null) }).isEqualTo("EXCEED_CANCEL_AMOUNT")

        val full = store.cancel(key, null, null)
        assertThat(full.status).isEqualTo(PgStatus.CANCELED)
        assertThat(full.balanceAmount).isZero()
        assertThat(full.cancels.map { it.cancelAmount }).containsExactly(3_000L, 7_000L)

        assertThat(rejectionCode { store.cancel(key, null, null) }).isEqualTo("ALREADY_CANCELED")
        assertThat(rejectionCode { store.cancel("pk_none", null, null) }).isEqualTo("NOT_FOUND_PAYMENT")
    }

    @Test
    fun `장애 설정 범위 검사`() {
        assertThatThrownBy { MockPgProperties.Fault(errorRate = 1.5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MockPgProperties.Fault(latencyMinMs = 10, latencyMaxMs = 5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(MockPgProperties().fault.dropHoldMs).isEqualTo(15_000)
    }
}
