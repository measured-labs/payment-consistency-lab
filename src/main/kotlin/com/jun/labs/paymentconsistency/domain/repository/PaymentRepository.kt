package com.jun.labs.paymentconsistency.domain.repository

import com.jun.labs.paymentconsistency.domain.Payment
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long>
