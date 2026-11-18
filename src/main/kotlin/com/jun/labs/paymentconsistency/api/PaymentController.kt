package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.PaymentException
import com.jun.labs.paymentconsistency.service.ApprovePaymentUseCase
import com.jun.labs.paymentconsistency.service.CancelPaymentUseCase
import com.jun.labs.paymentconsistency.service.GetPaymentUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

const val USER_ID_HEADER = "X-User-Id"

@RestController
@RequestMapping("/payments")
class PaymentController(
    private val approvePaymentUseCase: ApprovePaymentUseCase,
    private val cancelPaymentUseCase: CancelPaymentUseCase,
    private val getPaymentUseCase: GetPaymentUseCase,
) {
    /** PG 업무 거절도 201(status = FAILED) — 결제 시도라는 자원은 만들어졌다 */
    @PostMapping
    fun approve(
        @RequestHeader(USER_ID_HEADER, required = false) userId: String?,
        @RequestBody(required = false) request: ApprovePaymentRequest?,
    ): ResponseEntity<PaymentResponse> {
        val command = (request ?: throw PaymentException(ErrorCode.INVALID_REQUEST, "본문 없음"))
            .toCommand(parseUserId(userId))
        val result = approvePaymentUseCase.approve(command)
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(result))
    }

    /** 본문 생략 = 남은 금액 전부 취소 */
    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: String,
        @RequestBody(required = false) request: CancelPaymentRequest?,
    ): PaymentResponse {
        val command = (request ?: CancelPaymentRequest()).toCommand(parsePaymentId(id))
        return PaymentResponse.from(cancelPaymentUseCase.cancel(command))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): PaymentResponse =
        PaymentResponse.from(getPaymentUseCase.get(parsePaymentId(id)))
}
