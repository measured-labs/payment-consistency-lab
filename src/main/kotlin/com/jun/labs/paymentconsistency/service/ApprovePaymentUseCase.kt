package com.jun.labs.paymentconsistency.service

interface ApprovePaymentUseCase {
    /** PG 업무 거절은 예외가 아니라 FAILED 결과다 */
    fun approve(command: ApprovePaymentCommand): PaymentResult
}

/** cardNumber는 PG에 넘기기만 하고 저장하지 않는다 */
data class ApprovePaymentCommand(
    val orderId: String,
    val userId: Long,
    val amount: Long,
    val cardNumber: String,
) {
    override fun toString() = "ApprovePaymentCommand(orderId=$orderId, userId=$userId, amount=$amount, cardNumber=***)"
}
