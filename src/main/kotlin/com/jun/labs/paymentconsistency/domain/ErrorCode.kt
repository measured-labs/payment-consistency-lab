package com.jun.labs.paymentconsistency.domain

enum class ErrorCode(val message: String) {
    INVALID_REQUEST("요청 값이 올바르지 않습니다."),
    PAYMENT_NOT_FOUND("결제를 찾을 수 없습니다."),
    PAYMENT_NOT_CANCELABLE("취소할 수 없는 상태의 결제입니다."),
    CANCEL_AMOUNT_EXCEEDED("취소 금액이 남은 금액을 초과합니다."),
    /** 취소 요청에서 PG 업무 거절(승인 요청의 업무 거절은 예외가 아니라 FAILED 결과다) */
    PG_REJECTED("PG가 요청을 거절했습니다."),
    /** PG 5xx · 연결 실패 */
    PG_UNAVAILABLE("PG를 사용할 수 없습니다."),
    /** PG 읽기 타임아웃 — PG 처리 여부를 모른다 */
    PG_TIMEOUT("PG 응답 시간을 초과했습니다."),
    /** 계약 밖 서버 오류(예: 커넥션 풀 대기 초과) — 본문 모양만 {code, message}로 맞춘다 */
    INTERNAL_ERROR("서버 오류가 발생했습니다."),
}
