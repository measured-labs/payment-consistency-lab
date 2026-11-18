package com.jun.labs.paymentconsistency.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/** Payment 애그리거트의 내부 엔티티 — 전체 · 부분 취소 1건마다 1행. 생성은 [Payment.cancel]만 한다 */
@Entity
@Table(name = "payment_cancel")
class PaymentCancel internal constructor(
    payment: Payment,
    amount: Long,
    reason: String?,
    canceledAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    var payment: Payment = payment
        protected set

    @Column(nullable = false)
    var amount: Long = amount
        protected set

    @Column(length = 200)
    var reason: String? = reason
        protected set

    @Column(name = "canceled_at", nullable = false)
    var canceledAt: Instant = canceledAt
        protected set
}
