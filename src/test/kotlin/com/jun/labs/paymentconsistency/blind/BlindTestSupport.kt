package com.jun.labs.paymentconsistency.blind

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.mockpg.MockPgApplication
import com.jun.labs.paymentconsistency.PaymentConsistencyApplication
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

val BLIND_MAPPER = ObjectMapper()

const val APPROVE_CARD = "4111111111111111"
const val DECLINED_CARD = "4000000000000002"
const val INSUFFICIENT_FUNDS_CARD = "4000000000009995"

/** JSON 본문에서 키 자체를 빼고 싶을 때 쓰는 표시. */
object Omit

fun json(vararg pairs: Pair<String, Any?>): String =
    BLIND_MAPPER.writeValueAsString(linkedMapOf(*pairs.filter { it.second !== Omit }.toTypedArray()))

fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull || it.isMissingNode }?.asText()

data class HttpResult(val status: Int, val body: String) {
    val json: JsonNode by lazy { BLIND_MAPPER.readTree(body.ifBlank { "{}" }) }
    val code: String? get() = json.textOrNull("code")
}

class JsonHttp(private val baseUrl: String, private val timeout: Duration = Duration.ofSeconds(30)) {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(path: String, headers: Map<String, String> = emptyMap()) = send("GET", path, null, headers)

    fun post(path: String, body: String?, headers: Map<String, String> = emptyMap()) = send("POST", path, body, headers)

    private fun send(method: String, path: String, body: String?, headers: Map<String, String>): HttpResult {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        builder.method(method, publisher)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body() ?: "")
    }
}

/** 테스트 JVM 하나에 postgres:16 컨테이너 하나. */
object BlindPostgres {
    val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16").also { it.start() }
    }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { container.jdbcUrl }
        registry.add("spring.datasource.username") { container.username }
        registry.add("spring.datasource.password") { container.password }
    }
}

/**
 * 흉내 PG를 테스트 JVM 안에서 실제 HTTP 서버로 띄운다(포트 0).
 * 설정은 명령행 인자로 넘겨 classpath의 application.yml(앱 · 흉내 PG 어느 쪽이든)보다 앞서게 한다.
 * 같은 JVM classpath에 JPA · Flyway가 있으므로 흉내 PG 컨텍스트에서는 DB 자동 구성을 끈다.
 */
class MockPgInstance private constructor(private val context: ConfigurableApplicationContext) : AutoCloseable {
    val port: Int = context.environment.getProperty("local.server.port")!!.toInt()
    val baseUrl: String get() = "http://localhost:$port"

    override fun close() = context.close()

    companion object {
        private val DB_AUTO_CONFIG_EXCLUDES = listOf(
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        ).joinToString(",")

        fun start(vararg overrides: Pair<String, String>): MockPgInstance {
            val props = linkedMapOf(
                "server.port" to "0",
                "mockpg.reject-duplicate-order-id" to "true",
                "mockpg.fault.latency-min-ms" to "0",
                "mockpg.fault.latency-max-ms" to "0",
                "mockpg.fault.error-rate" to "0",
                "mockpg.fault.drop-rate" to "0",
                "mockpg.fault.drop-hold-ms" to "15000",
                "spring.autoconfigure.exclude" to DB_AUTO_CONFIG_EXCLUDES,
            )
            overrides.forEach { (k, v) -> props[k] = v }
            val args = props.map { (k, v) -> "--$k=$v" }.toTypedArray()
            val context = SpringApplicationBuilder(MockPgApplication::class.java)
                .properties(HashMap<String, Any>(props))
                .run(*args)
            return MockPgInstance(context)
        }
    }
}

/** 테스트 클래스마다 흉내 PG 하나 — 처음 쓰일 때 띄우고 @AfterAll에서 닫는다. */
class MockPgHolder(private vararg val overrides: Pair<String, String>) {
    private var instance: MockPgInstance? = null

    @Synchronized
    fun get(): MockPgInstance = instance ?: MockPgInstance.start(*overrides).also { instance = it }

    @Synchronized
    fun close() {
        instance?.close()
        instance = null
    }
}

@SpringBootTest(
    classes = [PaymentConsistencyApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
abstract class BlindAppTestBase {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    /** 이 클래스가 쓰는 흉내 PG. 흉내 PG가 없는 시나리오(연결 실패)는 쓰지 않는다. */
    protected abstract fun mockPg(): MockPgInstance?

    protected val app: JsonHttp get() = JsonHttp("http://localhost:$port")
    protected val pg: JsonHttp get() = JsonHttp(mockPg()!!.baseUrl)

    protected fun newOrderId(): String = "blind-" + UUID.randomUUID()

    protected fun userHeader(userId: String? = "1"): Map<String, String> =
        if (userId == null) emptyMap() else mapOf("X-User-Id" to userId)

    protected fun approve(
        orderId: Any? = newOrderId(),
        amount: Any? = 10_000L,
        cardNumber: Any? = APPROVE_CARD,
        userId: String? = "1",
    ): HttpResult = app.post(
        "/payments",
        json("orderId" to orderId, "amount" to amount, "cardNumber" to cardNumber),
        userHeader(userId),
    )

    protected fun approveOk(orderId: String = newOrderId(), amount: Long = 10_000L): JsonNode {
        val res = approve(orderId = orderId, amount = amount)
        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertThat(res.json.textOrNull("status")).isEqualTo("APPROVED")
        return res.json
    }

    protected fun approveDeclined(orderId: String = newOrderId(), amount: Long = 10_000L): JsonNode {
        val res = approve(orderId = orderId, amount = amount, cardNumber = DECLINED_CARD)
        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertThat(res.json.textOrNull("status")).isEqualTo("FAILED")
        return res.json
    }

    protected fun cancel(id: Any, body: String = "{}", userId: String? = "1"): HttpResult =
        app.post("/payments/$id/cancel", body, userHeader(userId))

    protected fun getPayment(id: Any, userId: String? = "1"): HttpResult =
        app.get("/payments/$id", userHeader(userId))

    protected fun pgByOrder(orderId: String): HttpResult = pg.get("/v1/payments/orders/$orderId")

    protected fun pgByKey(paymentKey: String): HttpResult = pg.get("/v1/payments/$paymentKey")

    protected fun rowCount(orderId: String): Long =
        jdbc.queryForObject("select count(*) from payment where order_id = ?", Long::class.java, orderId)!!

    protected fun totalRowCount(): Long =
        jdbc.queryForObject("select count(*) from payment", Long::class.java)!!

    protected fun paymentRow(id: Long): Map<String, Any?> =
        jdbc.queryForMap("select * from payment where id = ?", id)

    protected fun cancelRowSum(paymentId: Long): Long =
        jdbc.queryForObject(
            "select coalesce(sum(amount), 0) from payment_cancel where payment_id = ?",
            Long::class.java,
            paymentId,
        )!!

    protected fun cancelRowCount(paymentId: Long): Long =
        jdbc.queryForObject("select count(*) from payment_cancel where payment_id = ?", Long::class.java, paymentId)!!

    /** 앱 결제가 바뀌지 않았는지(상태 · 취소액 · 취소 내역 수) — 응답과 DB 양쪽. */
    protected fun assertAppUnchanged(paymentId: Long, status: String, canceledAmount: Long, cancelCount: Int) {
        val now = getPayment(paymentId)
        assertThat(now.status).isEqualTo(200)
        assertThat(now.json.textOrNull("status")).isEqualTo(status)
        assertThat(now.json["canceledAmount"].asLong()).isEqualTo(canceledAmount)
        assertThat(now.json["cancels"].size()).isEqualTo(cancelCount)
        val row = paymentRow(paymentId)
        assertThat(row["status"]).isEqualTo(status)
        assertThat((row["canceled_amount"] as Number).toLong()).isEqualTo(canceledAmount)
        assertThat(cancelRowCount(paymentId)).isEqualTo(cancelCount.toLong())
    }

    /** 흉내 PG 쪽 결제가 바뀌지 않았는지(상태 · 남은 금액 · 취소 수). */
    protected fun assertPgUnchanged(paymentKey: String, status: String, balanceAmount: Long, cancelCount: Int) {
        val now = pgByKey(paymentKey)
        assertThat(now.status).isEqualTo(200)
        assertThat(now.json.textOrNull("status")).isEqualTo(status)
        assertThat(now.json["balanceAmount"].asLong()).isEqualTo(balanceAmount)
        assertThat(now.json["cancels"].size()).isEqualTo(cancelCount)
    }
}
