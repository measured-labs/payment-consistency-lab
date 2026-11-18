package com.jun.labs.paymentconsistency.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant

/**
 * Payment 애그리거트 루트 — 가맹점 주문번호 하나에 대한 결제 시도 1건(ADR-000 §2.1).
 * 불변식: ① 전이는 [PaymentStatus] 표대로만 ② canceledAmount = Σ 취소 내역 ≤ amount
 * ③ CANCELED ⇔ canceledAmount = amount · PARTIALLY_CANCELED ⇔ 0 < canceledAmount < amount
 * ④ APPROVED 이상이면 paymentKey가 있다 ⑤ FAILED면 failureCode가 있고 취소 내역이 없다.
 * 동시성은 막지 않는다 — 두 취소가 같은 스냅샷을 읽으면 둘 다 통과한다(기준선).
 */
@Entity
@Table(name = "payment")
class Payment protected constructor(
    orderId: String,
    userId: Long,
    amount: Long,
    requestedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "order_id", nullable = false, length = 64)
    var orderId: String = orderId
        protected set

    @Column(name = "user_id", nullable = false)
    var userId: Long = userId
        protected set

    @Column(nullable = false)
    var amount: Long = amount
        protected set

    @Column(name = "canceled_amount", nullable = false)
    var canceledAmount: Long = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: PaymentStatus = PaymentStatus.REQUESTED
        protected set

    @Column(name = "payment_key", length = 64)
    var paymentKey: String? = null
        protected set

    @Column(name = "failure_code", length = 50)
    var failureCode: String? = null
        protected set

    @Column(name = "failure_message", length = FAILURE_MESSAGE_MAX)
    var failureMessage: String? = null
        protected set

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant = requestedAt
        protected set

    @Column(name = "approved_at")
    var approvedAt: Instant? = null
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = requestedAt
        protected set

    /** 취소 내역은 루트를 통해서만 생긴다([cancel]) */
    @OneToMany(mappedBy = "payment", cascade = [CascadeType.ALL])
    @OrderBy("id")
    protected var cancelHistory: MutableList<PaymentCancel> = mutableListOf()

    val cancels: List<PaymentCancel> get() = cancelHistory.toList()

    fun remainingAmount(): Long = amount - canceledAmount

    fun approve(paymentKey: String, approvedAt: Instant, now: Instant) {
        check(status == PaymentStatus.REQUESTED) { "승인은 REQUESTED에서만: $status" }
        require(paymentKey.isNotBlank()) { "paymentKey 없음" }
        require(paymentKey.length <= PAYMENT_KEY_MAX) { "paymentKey ${PAYMENT_KEY_MAX}자 초과" }
        this.status = PaymentStatus.APPROVED
        this.paymentKey = paymentKey
        this.approvedAt = approvedAt
        this.updatedAt = now
    }

    /** PG 업무 거절 — 메시지는 앞 200자만 저장한다 */
    fun fail(code: String, message: String?, now: Instant) {
        check(status == PaymentStatus.REQUESTED) { "실패는 REQUESTED에서만: $status" }
        require(code.isNotBlank()) { "failureCode 없음" }
        this.status = PaymentStatus.FAILED
        this.failureCode = code.take(FAILURE_CODE_MAX)
        this.failureMessage = message?.take(FAILURE_MESSAGE_MAX)
        this.updatedAt = now
    }

    /** 판정 순서: 금액 형식 → 상태 → 남은 금액. PG 호출 전 우리 스냅샷으로 검사한다(check-then-act) */
    fun assertCancelable(cancelAmount: Long, reason: String? = null) {
        if (cancelAmount < 1) throw PaymentException(ErrorCode.INVALID_REQUEST, "cancelAmount는 1 이상")
        assertCancelReason(reason)
        if (!status.cancelable) throw PaymentException(ErrorCode.PAYMENT_NOT_CANCELABLE)
        if (cancelAmount > remainingAmount()) throw PaymentException(ErrorCode.CANCEL_AMOUNT_EXCEEDED)
    }

    fun cancel(cancelAmount: Long, reason: String?, canceledAt: Instant) {
        assertCancelable(cancelAmount, reason)
        cancelHistory.add(PaymentCancel(this, cancelAmount, reason, canceledAt))
        canceledAmount += cancelAmount
        status = if (canceledAmount == amount) PaymentStatus.CANCELED else PaymentStatus.PARTIALLY_CANCELED
        updatedAt = canceledAt
    }

    companion object {
        const val ORDER_ID_MAX = 64
        const val PAYMENT_KEY_MAX = 64
        const val CANCEL_REASON_MAX = 200

        /** 영문 · 숫자 · `_` · `-` 1~64자 — PG 주문번호 조회(`/orders/{orderId}`) 경로에 그대로 쓸 수 있는 문자만 */
        val ORDER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,$ORDER_ID_MAX}")
        const val FAILURE_CODE_MAX = 50
        const val FAILURE_MESSAGE_MAX = 200

        /** 사유는 payment_cancel.reason(200자)에 저장된다 — API 밖 진입점도 PG 호출 전에 거른다(PG 취소 뒤 커밋 실패 방지) */
        fun assertCancelReason(reason: String?) {
            if (reason != null && reason.length > CANCEL_REASON_MAX) throw PaymentException(ErrorCode.INVALID_REQUEST, "reason은 ${CANCEL_REASON_MAX}자 이하")
        }

        fun request(orderId: String, userId: Long, amount: Long, requestedAt: Instant): Payment {
            if (!ORDER_ID_PATTERN.matches(orderId)) throw PaymentException(ErrorCode.INVALID_REQUEST, "orderId")
            if (userId < 1) throw PaymentException(ErrorCode.INVALID_REQUEST, "userId")
            if (amount < 1) throw PaymentException(ErrorCode.INVALID_REQUEST, "amount")
            return Payment(orderId, userId, amount, requestedAt)
        }
    }
}
