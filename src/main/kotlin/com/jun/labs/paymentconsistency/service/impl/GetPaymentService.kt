package com.jun.labs.paymentconsistency.service.impl

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.PaymentException
import com.jun.labs.paymentconsistency.domain.repository.PaymentRepository
import com.jun.labs.paymentconsistency.service.GetPaymentUseCase
import com.jun.labs.paymentconsistency.service.PaymentResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetPaymentService(
    private val paymentRepository: PaymentRepository,
) : GetPaymentUseCase {

    @Transactional(readOnly = true)
    override fun get(paymentId: Long): PaymentResult = paymentRepository.findById(paymentId)
        .map { PaymentResult.from(it) }
        .orElseThrow { PaymentException(ErrorCode.PAYMENT_NOT_FOUND) }
}
