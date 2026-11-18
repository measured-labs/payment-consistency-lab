package com.jun.labs.paymentconsistency.service

import com.jun.labs.paymentconsistency.domain.Payment
import com.jun.labs.paymentconsistency.domain.PaymentStatus
import java.time.Instant

data class PaymentResult(
    val paymentId: Long,
    val orderId: String,
    val userId: Long,
    val amount: Long,
    val canceledAmount: Long,
    val status: PaymentStatus,
    val paymentKey: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val approvedAt: Instant?,
    val cancels: List<PaymentCancelResult>,
) {
    companion object {
        fun from(payment: Payment) = PaymentResult(
            paymentId = checkNotNull(payment.id) { "저장 전 결제" },
            orderId = payment.orderId,
            userId = payment.userId,
            amount = payment.amount,
            canceledAmount = payment.canceledAmount,
            status = payment.status,
            paymentKey = payment.paymentKey,
            failureCode = payment.failureCode,
            failureMessage = payment.failureMessage,
            requestedAt = payment.requestedAt,
            approvedAt = payment.approvedAt,
            cancels = payment.cancels.map { PaymentCancelResult(it.amount, it.reason, it.canceledAt) },
        )
    }
}

data class PaymentCancelResult(
    val amount: Long,
    val reason: String?,
    val canceledAt: Instant,
)
