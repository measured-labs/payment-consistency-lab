package com.jun.labs.paymentconsistency.service.impl

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.PaymentException
import com.jun.labs.paymentconsistency.domain.repository.PaymentRepository
import com.jun.labs.paymentconsistency.infra.pg.PgCancelRequest
import com.jun.labs.paymentconsistency.infra.pg.PgCancelResult
import com.jun.labs.paymentconsistency.infra.pg.PgClient
import com.jun.labs.paymentconsistency.service.CancelPaymentCommand
import com.jun.labs.paymentconsistency.service.CancelPaymentUseCase
import com.jun.labs.paymentconsistency.service.PaymentResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 기준선(ADR-000 §3): 한 트랜잭션 안에서 로드 → 검사(우리 스냅샷 · check-then-act) → PG 취소 호출 → 반영.
 * 판정 순서: 존재(404) → 상태 → 금액 → PG. 락이 없어 두 취소가 같은 남은 금액을 읽을 수 있다(기준선).
 */
@Service
class CancelPaymentService(
    private val paymentRepository: PaymentRepository,
    private val pgClient: PgClient,
    private val clock: Clock,
) : CancelPaymentUseCase {

    @Transactional
    override fun cancel(command: CancelPaymentCommand): PaymentResult {
        val payment = paymentRepository.findById(command.paymentId)
            .orElseThrow { PaymentException(ErrorCode.PAYMENT_NOT_FOUND) }
        if (!payment.status.cancelable) throw PaymentException(ErrorCode.PAYMENT_NOT_CANCELABLE)
        val cancelAmount = command.cancelAmount ?: payment.remainingAmount()
        payment.assertCancelable(cancelAmount)

        val paymentKey = checkNotNull(payment.paymentKey) { "승인된 결제에 paymentKey 없음: ${payment.id}" }
        when (val result = pgClient.cancel(paymentKey, PgCancelRequest(cancelAmount, command.reason))) {
            // 우리가 보낸 취소 금액으로 도메인이 상태를 정한다(PG 응답의 status · balanceAmount는 쓰지 않는다)
            PgCancelResult.Canceled -> payment.cancel(cancelAmount, command.reason, clock.instant())
            is PgCancelResult.Rejected -> throw PaymentException(
                ErrorCode.PG_REJECTED,
                listOfNotNull(result.code, result.message).joinToString(": "),
            )
        }
        return PaymentResult.from(payment)
    }
}
