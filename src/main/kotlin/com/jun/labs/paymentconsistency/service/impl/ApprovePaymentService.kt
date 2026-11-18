package com.jun.labs.paymentconsistency.service.impl

import com.jun.labs.paymentconsistency.domain.Payment
import com.jun.labs.paymentconsistency.domain.repository.PaymentRepository
import com.jun.labs.paymentconsistency.infra.pg.PgApproveRequest
import com.jun.labs.paymentconsistency.infra.pg.PgApproveResult
import com.jun.labs.paymentconsistency.infra.pg.PgClient
import com.jun.labs.paymentconsistency.service.ApprovePaymentCommand
import com.jun.labs.paymentconsistency.service.ApprovePaymentUseCase
import com.jun.labs.paymentconsistency.service.PaymentResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 기준선(ADR-000 §3): 한 트랜잭션 안에서 저장 → PG 승인 호출 → 결과 반영.
 * PG를 기다리는 동안 DB 커넥션을 쥔다(Q7). PG 기술 실패는 예외 → 전체 롤백, 행이 남지 않는다(Q2 · Q6).
 */
@Service
class ApprovePaymentService(
    private val paymentRepository: PaymentRepository,
    private val pgClient: PgClient,
    private val clock: Clock,
) : ApprovePaymentUseCase {

    @Transactional
    override fun approve(command: ApprovePaymentCommand): PaymentResult {
        val payment = paymentRepository.save(
            Payment.request(command.orderId, command.userId, command.amount, clock.instant()),
        )
        when (val result = pgClient.approve(PgApproveRequest(command.orderId, command.amount, command.cardNumber))) {
            is PgApproveResult.Approved -> payment.approve(result.paymentKey, result.approvedAt, clock.instant())
            is PgApproveResult.Rejected -> payment.fail(result.code, result.message, clock.instant())
        }
        return PaymentResult.from(payment)
    }
}
