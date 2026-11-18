package com.jun.labs.paymentconsistency.service

interface CancelPaymentUseCase {
    fun cancel(command: CancelPaymentCommand): PaymentResult
}

/** [cancelAmount] null = 우리 스냅샷의 남은 금액 전부 */
data class CancelPaymentCommand(
    val paymentId: Long,
    val cancelAmount: Long?,
    val reason: String?,
)
