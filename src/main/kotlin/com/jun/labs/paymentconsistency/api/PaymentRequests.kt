package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.PaymentException
import com.jun.labs.paymentconsistency.service.ApprovePaymentCommand
import com.jun.labs.paymentconsistency.service.CancelPaymentCommand

/** 필드는 모두 nullable — 빠진 값도 INVALID_REQUEST(400)로 직접 판정한다(ADR-000 §6.1) */
data class ApprovePaymentRequest(
    val orderId: String? = null,
    val amount: Long? = null,
    val cardNumber: String? = null,
) {
    fun toCommand(userId: Long): ApprovePaymentCommand {
        val orderId = orderId
        val amount = amount
        val cardNumber = cardNumber
        if (orderId.isNullOrBlank() || orderId.length > ORDER_ID_MAX) throw invalid("orderId는 공백 아님 · 1~${ORDER_ID_MAX}자")
        if (amount == null || amount < 1) throw invalid("amount는 1 이상의 정수")
        if (cardNumber.isNullOrBlank() || cardNumber.length > CARD_NUMBER_MAX) throw invalid("cardNumber는 공백 아님 · 1~${CARD_NUMBER_MAX}자")
        return ApprovePaymentCommand(orderId, userId, amount, cardNumber)
    }

    /** 카드번호는 로그에 남기지 않는다 */
    override fun toString() = "ApprovePaymentRequest(orderId=$orderId, amount=$amount, cardNumber=***)"
}

/** cancelAmount 생략 또는 null = 남은 금액 전부 */
data class CancelPaymentRequest(
    val cancelAmount: Long? = null,
    val reason: String? = null,
) {
    fun toCommand(paymentId: Long): CancelPaymentCommand {
        if (cancelAmount != null && cancelAmount < 1) throw invalid("cancelAmount는 1 이상의 정수")
        if (reason != null && reason.length > REASON_MAX) throw invalid("reason은 ${REASON_MAX}자 이하")
        return CancelPaymentCommand(paymentId, cancelAmount, reason)
    }
}

const val ORDER_ID_MAX = 64
const val CARD_NUMBER_MAX = 32
const val REASON_MAX = 200

/** X-User-Id — 승인에서만 필수 · 양의 정수 */
fun parseUserId(header: String?): Long =
    header?.takeIf { DIGITS.matches(it) }?.toLongOrNull()?.takeIf { it > 0 } ?: throw invalid("X-User-Id는 양의 정수")

/** 경로 {id} — 양의 정수 · Long 범위 */
fun parsePaymentId(raw: String): Long =
    raw.takeIf { DIGITS.matches(it) }?.toLongOrNull()?.takeIf { it > 0 } ?: throw invalid("결제 id는 양의 정수")

/** 숫자만 — toLongOrNull은 앞의 '+'를 받아 준다 */
private val DIGITS = Regex("[0-9]+")

private fun invalid(detail: String) = PaymentException(ErrorCode.INVALID_REQUEST, detail)
