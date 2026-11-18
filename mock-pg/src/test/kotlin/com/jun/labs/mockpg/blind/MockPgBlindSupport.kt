package com.jun.labs.mockpg.blind

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

val PG_MAPPER = ObjectMapper()

const val OK_CARD = "4111111111111111"
const val DECLINED_CARD = "4000000000000002"
const val INSUFFICIENT_FUNDS_CARD = "4000000000009995"

fun pgJson(vararg pairs: Pair<String, Any?>): String = PG_MAPPER.writeValueAsString(linkedMapOf(*pairs))

fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull || it.isMissingNode }?.asText()

data class PgResult(val status: Int, val body: String) {
    val json: JsonNode by lazy { PG_MAPPER.readTree(body.ifBlank { "{}" }) }
    val code: String? get() = json.textOrNull("code")
}

abstract class MockPgBlindTestBase {

    @LocalServerPort
    protected var port: Int = 0

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    protected fun newOrderId(): String = "pg-blind-" + UUID.randomUUID()

    protected fun send(method: String, path: String, body: String?, timeout: Duration = Duration.ofSeconds(30)): PgResult {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .timeout(timeout)
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return PgResult(response.statusCode(), response.body() ?: "")
    }

    protected fun approve(orderId: String, amount: Any? = 10_000L, card: String = OK_CARD, timeout: Duration = Duration.ofSeconds(30)) =
        send("POST", "/v1/payments", pgJson("orderId" to orderId, "amount" to amount, "cardNumber" to card), timeout)

    protected fun approveOk(orderId: String = newOrderId(), amount: Long = 10_000L): JsonNode {
        val res = approve(orderId, amount)
        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("DONE")
        return res.json
    }

    protected fun cancel(paymentKey: String, body: String = "{}") = send("POST", "/v1/payments/$paymentKey/cancel", body)

    protected fun byKey(paymentKey: String) = send("GET", "/v1/payments/$paymentKey", null)

    protected fun byOrder(orderId: String) = send("GET", "/v1/payments/orders/$orderId", null)

    protected fun assertPayment(res: PgResult, status: String, total: Long, balance: Long, cancels: Int) {
        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo(status)
        assertThat(res.json["totalAmount"].asLong()).isEqualTo(total)
        assertThat(res.json["balanceAmount"].asLong()).isEqualTo(balance)
        assertThat(res.json["cancels"].size()).isEqualTo(cancels)
    }
}
