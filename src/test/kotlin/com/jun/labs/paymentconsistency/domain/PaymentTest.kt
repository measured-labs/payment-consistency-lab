package com.jun.labs.paymentconsistency.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class PaymentTest {
    private val t0 = Instant.parse("2026-11-18T00:00:00Z")
    private val t1 = t0.plusSeconds(1)

    private fun approved(amount: Long = 10_000): Payment =
        Payment.request("order-1", 1, amount, t0).apply { approve("pk_1", t1, t1) }

    private fun errorCodeOf(block: () -> Unit): ErrorCode? =
        runCatching(block).exceptionOrNull()?.let { (it as PaymentException).errorCode }

    @Test
    fun `요청은 REQUESTED · 취소액 0`() {
        val p = Payment.request("order-1", 7, 10_000, t0)
        assertThat(p.status).isEqualTo(PaymentStatus.REQUESTED)
        assertThat(p.canceledAmount).isZero()
        assertThat(p.paymentKey).isNull()
        assertThat(p.requestedAt).isEqualTo(t0)
    }

    @Test
    fun `요청 입력 위반은 INVALID_REQUEST`() {
        assertThat(errorCodeOf { Payment.request(" ", 1, 1, t0) }).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(errorCodeOf { Payment.request("x".repeat(65), 1, 1, t0) }).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(errorCodeOf { Payment.request("o", 0, 1, t0) }).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(errorCodeOf { Payment.request("o", 1, 0, t0) }).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `승인하면 APPROVED · paymentKey가 있다`() {
        val p = approved()
        assertThat(p.status).isEqualTo(PaymentStatus.APPROVED)
        assertThat(p.paymentKey).isEqualTo("pk_1")
        assertThat(p.approvedAt).isEqualTo(t1)
        assertThat(p.updatedAt).isEqualTo(t1)
    }

    @Test
    fun `실패는 FAILED · 코드 있음 · 메시지 앞 200자만`() {
        val p = Payment.request("order-1", 1, 10_000, t0)
        p.fail("CARD_DECLINED", "m".repeat(300), t1)
        assertThat(p.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(p.failureCode).isEqualTo("CARD_DECLINED")
        assertThat(p.failureMessage).hasSize(200)
        assertThat(p.cancels).isEmpty()
    }

    @Test
    fun `승인 · 실패는 REQUESTED에서만`() {
        val p = approved()
        assertThatThrownBy { p.approve("pk_2", t1, t1) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { p.fail("X", null, t1) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `부분 취소 뒤 남은 금액 전부 취소하면 CANCELED · 취소액 = 내역 합`() {
        val p = approved(10_000)
        p.cancel(3_000, "부분", t1)
        assertThat(p.status).isEqualTo(PaymentStatus.PARTIALLY_CANCELED)
        assertThat(p.canceledAmount).isEqualTo(3_000)
        assertThat(p.remainingAmount()).isEqualTo(7_000)

        p.cancel(2_000, null, t1)
        assertThat(p.status).isEqualTo(PaymentStatus.PARTIALLY_CANCELED)

        p.cancel(5_000, "나머지", t1)
        assertThat(p.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(p.canceledAmount).isEqualTo(10_000)
        assertThat(p.cancels.sumOf { it.amount }).isEqualTo(p.canceledAmount)
        assertThat(p.cancels.map { it.reason }).containsExactly("부분", null, "나머지")
    }

    @Test
    fun `전체 취소는 바로 CANCELED`() {
        val p = approved(10_000)
        p.cancel(10_000, null, t1)
        assertThat(p.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(p.cancels).hasSize(1)
    }

    @Test
    fun `남은 금액 초과는 CANCEL_AMOUNT_EXCEEDED · 상태 불변`() {
        val p = approved(10_000)
        p.cancel(4_000, null, t1)
        assertThat(errorCodeOf { p.cancel(6_001, null, t1) }).isEqualTo(ErrorCode.CANCEL_AMOUNT_EXCEEDED)
        assertThat(p.canceledAmount).isEqualTo(4_000)
        assertThat(p.cancels).hasSize(1)
    }

    @Test
    fun `승인되지 않았거나 전부 취소된 결제는 PAYMENT_NOT_CANCELABLE`() {
        val failed = Payment.request("o", 1, 10_000, t0).apply { fail("CARD_DECLINED", null, t1) }
        assertThat(errorCodeOf { failed.cancel(1, null, t1) }).isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE)
        assertThat(failed.cancels).isEmpty()

        val requested = Payment.request("o", 1, 10_000, t0)
        assertThat(errorCodeOf { requested.assertCancelable(1) }).isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE)

        val canceled = approved(10_000).apply { cancel(10_000, null, t1) }
        assertThat(errorCodeOf { canceled.cancel(1, null, t1) }).isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE)
    }

    @Test
    fun `상태 판정이 금액 판정보다 먼저`() {
        val canceled = approved(10_000).apply { cancel(10_000, null, t1) }
        assertThat(errorCodeOf { canceled.assertCancelable(99_999) }).isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE)
    }

    @Test
    fun `취소 금액 0 이하는 INVALID_REQUEST`() {
        assertThat(errorCodeOf { approved().assertCancelable(0) }).isEqualTo(ErrorCode.INVALID_REQUEST)
    }
}
