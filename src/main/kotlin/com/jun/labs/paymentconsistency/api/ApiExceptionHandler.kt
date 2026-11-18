package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.PaymentException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.ErrorResponse as SpringErrorResponse

/** 오류 본문은 언제나 {code, message}(ADR-000 §6) */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PaymentException::class)
    fun handle(e: PaymentException): ResponseEntity<ErrorResponse> {
        val status = e.errorCode.httpStatus()
        // 5xx(PG 기술 실패 502 · 504)는 세부 · 원인을 서버 로그에만 남긴다 — 응답에는 코드의 고정 문구(ADR-000 §6)
        if (status.is5xxServerError) {
            log.warn("{} — {}", e.errorCode, e.message, e.cause)
            return ResponseEntity.status(status).body(ErrorResponse(e.errorCode.name, e.errorCode.message))
        }
        return ResponseEntity.status(status).body(ErrorResponse(e.errorCode.name, e.message ?: e.errorCode.message))
    }

    /** JSON으로 읽을 수 없는 본문 · 정수 자리의 1.5 · "1000" · JSON이 아닌 Content-Type */
    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun unreadable(): ResponseEntity<ErrorResponse> =
        handle(PaymentException(ErrorCode.INVALID_REQUEST, "요청 본문을 JSON으로 읽을 수 없습니다."))

    /**
     * 그 밖의 오류 — 본문 모양만 맞춘다. Spring MVC 표준 오류(없는 경로 404 · 405 등 — 헤더(Allow 등)도 그대로)는 그 상태 그대로 code `HTTP_<상태>`,
     * 나머지(예: 커넥션 풀 대기 초과)는 500 INTERNAL_ERROR · 원인은 서버 로그에만.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is SpringErrorResponse) {
            val status = e.statusCode
            if (status.is5xxServerError) log.error("Spring MVC 서버 오류 {}", status.value(), e)
            return ResponseEntity.status(status).headers(e.headers).body(ErrorResponse("HTTP_${status.value()}", HttpStatus.resolve(status.value())?.reasonPhrase ?: "오류"))
        }
        log.error("처리하지 못한 예외", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse(ErrorCode.INTERNAL_ERROR.name, ErrorCode.INTERNAL_ERROR.message))
    }
}

fun ErrorCode.httpStatus(): HttpStatus = when (this) {
    ErrorCode.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
    ErrorCode.PAYMENT_NOT_FOUND -> HttpStatus.NOT_FOUND
    ErrorCode.PAYMENT_NOT_CANCELABLE,
    ErrorCode.CANCEL_AMOUNT_EXCEEDED,
    ErrorCode.PG_REJECTED,
    -> HttpStatus.CONFLICT
    ErrorCode.PG_UNAVAILABLE -> HttpStatus.BAD_GATEWAY
    ErrorCode.PG_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
    ErrorCode.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
}
