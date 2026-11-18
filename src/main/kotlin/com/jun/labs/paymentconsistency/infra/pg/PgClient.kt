package com.jun.labs.paymentconsistency.infra.pg

import java.time.Instant

/**
 * PG 어댑터 경계. 업무 거절(4xx)은 결과로 돌려주고, 기술 실패는 예외로 던진다:
 * 5xx · 연결 실패 → PaymentException(PG_UNAVAILABLE) · 읽기 타임아웃 → PaymentException(PG_TIMEOUT).
 */
interface PgClient {
    fun approve(request: PgApproveRequest): PgApproveResult

    /** 앱은 언제나 취소 금액을 명시해서 보낸다(ADR-000 §6) */
    fun cancel(paymentKey: String, request: PgCancelRequest): PgCancelResult
}

data class PgApproveRequest(val orderId: String, val amount: Long, val cardNumber: String) {
    /** 카드번호는 로그에 남기지 않는다(RestClient DEBUG의 Writing [...]) */
    override fun toString() = "PgApproveRequest(orderId=$orderId, amount=$amount, cardNumber=***)"
}

data class PgCancelRequest(val cancelAmount: Long, val cancelReason: String?)

sealed interface PgApproveResult {
    data class Approved(val paymentKey: String, val approvedAt: Instant) : PgApproveResult

    data class Rejected(val code: String, val message: String?) : PgApproveResult
}

/** 앱 상태는 도메인 계산으로 정한다 — PG 응답의 status · balanceAmount는 쓰지 않는다 */
sealed interface PgCancelResult {
    data object Canceled : PgCancelResult

    data class Rejected(val code: String, val message: String?) : PgCancelResult
}
