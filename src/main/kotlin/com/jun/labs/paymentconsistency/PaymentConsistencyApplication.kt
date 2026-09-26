package com.jun.labs.paymentconsistency

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PaymentConsistencyApplication

fun main(args: Array<String>) {
    runApplication<PaymentConsistencyApplication>(*args)
}
