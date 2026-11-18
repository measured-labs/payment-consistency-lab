package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.service.PaymentResult
import java.time.Instant

data class PaymentResponse(
    val paymentId: Long,
    val orderId: String,
    val userId: Long,
    val amount: Long,
    val canceledAmount: Long,
    val status: String,
    val paymentKey: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val approvedAt: Instant?,
    val cancels: List<PaymentCancelResponse>,
) {
    companion object {
        fun from(result: PaymentResult) = PaymentResponse(
            paymentId = result.paymentId,
            orderId = result.orderId,
            userId = result.userId,
            amount = result.amount,
            canceledAmount = result.canceledAmount,
            status = result.status.name,
            paymentKey = result.paymentKey,
            failureCode = result.failureCode,
            failureMessage = result.failureMessage,
            requestedAt = result.requestedAt,
            approvedAt = result.approvedAt,
            cancels = result.cancels.map { PaymentCancelResponse(it.amount, it.reason, it.canceledAt) },
        )
    }
}

data class PaymentCancelResponse(
    val amount: Long,
    val reason: String?,
    val canceledAt: Instant,
)

data class ErrorResponse(val code: String, val message: String)
