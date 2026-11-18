package com.jun.labs.paymentconsistency.infra.pg

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.PaymentException
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress

/** PG 승인 응답의 paymentKey가 저장 열(64자)보다 길면 받아들이지 않고 PG 응답 오류(PG_UNAVAILABLE · 502)로 분류한다 */
class RestPgClientPaymentKeyTest {

    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun clientAnswering(paymentKey: String): RestPgClient {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/v1/payments") { exchange ->
            val body = """{"paymentKey":"$paymentKey","approvedAt":"2026-11-18T00:00:00+09:00"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        server = http
        return RestPgClient(PgProperties(baseUrl = "http://127.0.0.1:${http.address.port}"), RestClient.builder())
    }

    private val request = PgApproveRequest("order-1", 1_000, "4111111111111111")

    @Test
    fun `65자 paymentKey는 PG_UNAVAILABLE`() {
        val error = runCatching { clientAnswering("k".repeat(65)).approve(request) }.exceptionOrNull()
        assertThat((error as PaymentException).errorCode).isEqualTo(ErrorCode.PG_UNAVAILABLE)
    }

    @Test
    fun `64자 paymentKey는 승인`() {
        val result = clientAnswering("k".repeat(64)).approve(request)
        assertThat(result).isInstanceOf(PgApproveResult.Approved::class.java)
        assertThat((result as PgApproveResult.Approved).paymentKey).hasSize(64)
    }
}
