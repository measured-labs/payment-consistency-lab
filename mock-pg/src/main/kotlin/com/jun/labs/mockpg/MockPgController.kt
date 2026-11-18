package com.jun.labs.mockpg

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ApproveRequest(val orderId: String?, val amount: Long?, val cardNumber: String?)

data class CancelRequest(val cancelAmount: Long? = null, val cancelReason: String? = null)

data class PgError(val code: String, val message: String)

@RestController
@RequestMapping("/v1/payments")
class MockPgController(
    private val store: PgPaymentStore,
    private val faults: FaultInjector,
) {
    @PostMapping
    fun approve(@RequestBody request: ApproveRequest): ResponseEntity<Any> = withFaults {
        val orderId = request.orderId
        val amount = request.amount
        val cardNumber = request.cardNumber
        if (orderId.isNullOrBlank() || amount == null || amount < 1 || cardNumber.isNullOrBlank()) {
            throw invalidRequest()
        }
        store.approve(orderId, amount, cardNumber)
    }

    @PostMapping("/{paymentKey}/cancel")
    fun cancel(
        @PathVariable paymentKey: String,
        @RequestBody(required = false) request: CancelRequest?,
    ): ResponseEntity<Any> = withFaults {
        val cancelAmount = request?.cancelAmount
        if (cancelAmount != null && cancelAmount < 1) throw invalidRequest()
        store.cancel(paymentKey, cancelAmount, request?.cancelReason)
    }

    @GetMapping("/{paymentKey}")
    fun get(@PathVariable paymentKey: String): ResponseEntity<Any> = respond { store.findByPaymentKey(paymentKey) }

    @GetMapping("/orders/{orderId}")
    fun getByOrderId(@PathVariable orderId: String): ResponseEntity<Any> = respond { store.findByOrderId(orderId) }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<PgError> = ResponseEntity.badRequest().body(PgError("INVALID_REQUEST", "요청 본문을 읽을 수 없습니다."))

    /** 승인 · 취소: 지연 → (error) 처리하지 않고 500 → 처리 → (drop) 처리한 뒤 응답을 쥔다 */
    private fun withFaults(process: () -> PgPayment): ResponseEntity<Any> {
        faults.delay()
        if (faults.shouldError()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(PgError("INTERNAL_ERROR", "일시적인 오류입니다."))
        }
        val response = respond(process)
        faults.holdIfDropped()
        return response
    }

    private fun respond(process: () -> PgPayment): ResponseEntity<Any> = try {
        ResponseEntity.ok(process())
    } catch (e: PgRejection) {
        ResponseEntity.status(e.httpStatus).body(PgError(e.code, e.message))
    }

    private fun invalidRequest() = PgRejection(400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.")
}
