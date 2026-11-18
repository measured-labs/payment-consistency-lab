package com.jun.labs.mockpg

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** PG 쪽 결제 상태 이름(토스페이먼츠 표기) */
enum class PgStatus { DONE, PARTIAL_CANCELED, CANCELED }

data class PgCancel(val cancelAmount: Long, val cancelReason: String?, val canceledAt: OffsetDateTime)

/** 응답용 스냅샷 */
data class PgPayment(
    val paymentKey: String,
    val orderId: String,
    val status: PgStatus,
    val totalAmount: Long,
    val balanceAmount: Long,
    val approvedAt: OffsetDateTime,
    val cancels: List<PgCancel>,
)

/** 업무 거절 — 400 · 404 {code, message} */
class PgRejection(val httpStatus: Int, val code: String, override val message: String) : RuntimeException(message)

/**
 * 결제 기록 — 프로세스 메모리(재기동하면 사라진다). 쓰기는 한 잠금 안에서 하므로
 * 주문번호 중복 판정은 원자적이다(동시 10건이어도 승인 1건).
 */
@Component
class PgPaymentStore(
    private val properties: MockPgProperties,
    private val clock: Clock,
) {
    private class Record(
        val paymentKey: String,
        val orderId: String,
        val totalAmount: Long,
        val approvedAt: OffsetDateTime,
    ) {
        var balanceAmount: Long = totalAmount
        var status: PgStatus = PgStatus.DONE
        val cancels: MutableList<PgCancel> = mutableListOf()

        fun snapshot() = PgPayment(paymentKey, orderId, status, totalAmount, balanceAmount, approvedAt, cancels.toList())
    }

    private val lock = Any()
    private val byPaymentKey = HashMap<String, Record>()
    /** orderId → 가장 최근 승인의 paymentKey */
    private val latestByOrderId = HashMap<String, String>()

    fun approve(orderId: String, amount: Long, cardNumber: String): PgPayment {
        declineReason(cardNumber)?.let { throw PgRejection(400, it.first, it.second) }
        synchronized(lock) {
            if (properties.rejectDuplicateOrderId && orderId in latestByOrderId) {
                throw PgRejection(400, "DUPLICATED_ORDER_ID", "이미 승인된 주문번호입니다.")
            }
            val record = Record(newPaymentKey(), orderId, amount, OffsetDateTime.now(clock))
            byPaymentKey[record.paymentKey] = record
            latestByOrderId[orderId] = record.paymentKey
            return record.snapshot()
        }
    }

    /** [cancelAmount] null = 남은 금액(balanceAmount) 전부 */
    fun cancel(paymentKey: String, cancelAmount: Long?, cancelReason: String?): PgPayment = synchronized(lock) {
        val record = byPaymentKey[paymentKey] ?: throw notFound()
        if (record.status == PgStatus.CANCELED) {
            throw PgRejection(400, "ALREADY_CANCELED", "이미 취소된 결제입니다.")
        }
        val amount = cancelAmount ?: record.balanceAmount
        if (amount > record.balanceAmount) {
            throw PgRejection(400, "EXCEED_CANCEL_AMOUNT", "취소 금액이 남은 금액을 초과합니다.")
        }
        record.balanceAmount -= amount
        record.cancels += PgCancel(amount, cancelReason, OffsetDateTime.now(clock))
        record.status = if (record.balanceAmount == 0L) PgStatus.CANCELED else PgStatus.PARTIAL_CANCELED
        record.snapshot()
    }

    fun findByPaymentKey(paymentKey: String): PgPayment = synchronized(lock) {
        byPaymentKey[paymentKey]?.snapshot() ?: throw notFound()
    }

    fun findByOrderId(orderId: String): PgPayment = synchronized(lock) {
        latestByOrderId[orderId]?.let { byPaymentKey[it] }?.snapshot() ?: throw notFound()
    }

    private fun declineReason(cardNumber: String): Pair<String, String>? = when (cardNumber) {
        DECLINED_CARD -> "CARD_DECLINED" to "카드사에서 거절했습니다."
        INSUFFICIENT_FUNDS_CARD -> "INSUFFICIENT_FUNDS" to "잔액이 부족합니다."
        else -> null
    }

    private fun notFound() = PgRejection(404, "NOT_FOUND_PAYMENT", "결제를 찾을 수 없습니다.")

    private fun newPaymentKey() = "pk_" + UUID.randomUUID().toString().replace("-", "")

    companion object {
        const val DECLINED_CARD = "4000000000000002"
        const val INSUFFICIENT_FUNDS_CARD = "4000000000009995"
    }
}
