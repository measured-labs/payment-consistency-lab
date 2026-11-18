package com.jun.labs.paymentconsistency.domain

/**
 * 전이(ADR-000 §3): REQUESTED → APPROVED | FAILED · APPROVED → PARTIALLY_CANCELED | CANCELED ·
 * PARTIALLY_CANCELED → PARTIALLY_CANCELED | CANCELED. 기준선에서 REQUESTED는 커밋되어 보이지 않는다.
 */
enum class PaymentStatus {
    REQUESTED,
    APPROVED,
    PARTIALLY_CANCELED,
    CANCELED,
    FAILED,
    ;

    val cancelable: Boolean get() = this == APPROVED || this == PARTIALLY_CANCELED
}
