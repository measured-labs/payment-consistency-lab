package com.jun.labs.paymentconsistency.domain

class PaymentException(
    val errorCode: ErrorCode,
    detail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(detail ?: errorCode.message, cause)
