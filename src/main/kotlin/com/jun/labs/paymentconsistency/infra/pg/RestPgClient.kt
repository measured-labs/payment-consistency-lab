package com.jun.labs.paymentconsistency.infra.pg

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.Payment
import com.jun.labs.paymentconsistency.domain.PaymentException
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.OffsetDateTime

@Component
class RestPgClient(
    properties: PgProperties,
    builder: RestClient.Builder,
) : PgClient {
    private val restClient: RestClient = builder
        .baseUrl(properties.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(properties.connectTimeout)
                    .build(),
            ).apply { setReadTimeout(properties.readTimeout) },
        )
        .build()

    override fun approve(request: PgApproveRequest): PgApproveResult = call {
        restClient.post()
            .uri("/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange<PgApproveResult> { _, response ->
                val status = response.statusCode
                when {
                    status.is2xxSuccessful() -> {
                        val body = response.bodyTo(PgPaymentBody::class.java)
                        val paymentKey = body?.paymentKey
                        val approvedAt = body?.approvedAt
                        if (paymentKey.isNullOrBlank() || approvedAt == null) {
                            throw PaymentException(ErrorCode.PG_UNAVAILABLE, "PG 승인 응답에 paymentKey · approvedAt 없음")
                        }
                        // payment.payment_key는 64자 — 넘는 키는 받아들이면 커밋에서 DB 오류(500)가 난다. PG 응답 오류로 분류한다
                        if (paymentKey.length > Payment.PAYMENT_KEY_MAX) {
                            throw PaymentException(ErrorCode.PG_UNAVAILABLE, "PG 승인 응답 paymentKey ${paymentKey.length}자(최대 ${Payment.PAYMENT_KEY_MAX})")
                        }
                        PgApproveResult.Approved(paymentKey, approvedAt.toInstant())
                    }
                    status.is4xxClientError() -> readError(response, status.value()).let { PgApproveResult.Rejected(it.first, it.second) }
                    else -> throw PaymentException(ErrorCode.PG_UNAVAILABLE, "PG 응답 ${status.value()}")
                }
            }
    }

    override fun cancel(paymentKey: String, request: PgCancelRequest): PgCancelResult = call {
        restClient.post()
            .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange<PgCancelResult> { _, response ->
                val status = response.statusCode
                when {
                    status.is2xxSuccessful() -> PgCancelResult.Canceled
                    status.is4xxClientError() -> readError(response, status.value()).let { PgCancelResult.Rejected(it.first, it.second) }
                    else -> throw PaymentException(ErrorCode.PG_UNAVAILABLE, "PG 응답 ${status.value()}")
                }
            }
    }

    /** 업무 거절 본문 {code, message} — 읽지 못하면 HTTP 상태로 코드를 만든다 */
    private fun readError(response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse, status: Int): Pair<String, String?> {
        val body = runCatching { response.bodyTo(PgErrorBody::class.java) }.getOrNull()
        val code = body?.code?.takeIf { it.isNotBlank() } ?: "HTTP_$status"
        return code to body?.message
    }

    private fun <T : Any> call(block: () -> T?): T = try {
        block() ?: throw PaymentException(ErrorCode.PG_UNAVAILABLE, "PG 응답 없음")
    } catch (e: ResourceAccessException) {
        throw classify(e)
    } catch (e: RestClientException) {
        throw PaymentException(ErrorCode.PG_UNAVAILABLE, cause = e)
    }

    /** 원인 예외 문구(내부 주소 · paymentKey가 든 URL)는 응답에 싣지 않는다 — cause로만 남겨 서버 로그에 쓴다(ApiExceptionHandler) */
    /** 읽기 타임아웃 → PG_TIMEOUT(504) · 연결 실패(연결 타임아웃 포함) 등 그 밖의 I/O 오류 → PG_UNAVAILABLE(502) */
    private fun classify(e: ResourceAccessException): PaymentException {
        val causes = generateSequence<Throwable>(e) { it.cause }.take(10).toList()
        val connectTimeout = causes.any { it is HttpConnectTimeoutException }
        val readTimeout = causes.any { it is HttpTimeoutException || it is SocketTimeoutException }
        return if (readTimeout && !connectTimeout) {
            PaymentException(ErrorCode.PG_TIMEOUT, cause = e)
        } else {
            PaymentException(ErrorCode.PG_UNAVAILABLE, cause = e)
        }
    }

    internal data class PgPaymentBody(val paymentKey: String? = null, val approvedAt: OffsetDateTime? = null)

    internal data class PgErrorBody(val code: String? = null, val message: String? = null)
}
