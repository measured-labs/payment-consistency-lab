package com.jun.labs.paymentconsistency.service

interface GetPaymentUseCase {
    fun get(paymentId: Long): PaymentResult
}
