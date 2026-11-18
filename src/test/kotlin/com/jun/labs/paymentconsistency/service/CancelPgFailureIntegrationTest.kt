package com.jun.labs.paymentconsistency.service

import com.jun.labs.paymentconsistency.domain.ErrorCode
import com.jun.labs.paymentconsistency.domain.PaymentException
import com.jun.labs.paymentconsistency.infra.pg.PgApproveRequest
import com.jun.labs.paymentconsistency.infra.pg.PgApproveResult
import com.jun.labs.paymentconsistency.infra.pg.PgCancelRequest
import com.jun.labs.paymentconsistency.infra.pg.PgCancelResult
import com.jun.labs.paymentconsistency.infra.pg.PgClient
import com.jun.labs.paymentconsistency.support.Http
import com.jun.labs.paymentconsistency.support.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.UUID

/**
 * 취소 경로의 PG 기술 실패 · 업무 거절 — 흉내 PG 주입은 승인 · 취소에 똑같이 걸려 블랙박스로 못 만든다(ADR-000 §7).
 * 가짜 PgClient로: 502 · 504 · 409 PG_REJECTED 모두 롤백(상태 · 취소 내역 불변).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CancelPgFailureIntegrationTest.FakePgConfig::class)
class CancelPgFailureIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var fakePg: FakePgClient

    @Autowired
    lateinit var cancelUseCase: CancelPaymentUseCase

    private val app by lazy { Http(rest) }

    @AfterEach
    fun reset() {
        fakePg.onCancel = { PgCancelResult.Canceled }
        fakePg.lastCancel = null
        fakePg.approveCalls.set(0)
    }

    private fun approvedPaymentId(amount: Long = 10_000): Long {
        val res = app.post(
            "/payments",
            """{"orderId":"order-${UUID.randomUUID()}","amount":$amount,"cardNumber":"4111111111111111"}""",
            mapOf("X-User-Id" to "1"),
        )
        assertThat(res.body["status"].asText()).isEqualTo("APPROVED")
        return res.body["paymentId"].asLong()
    }

    private fun assertUnchanged(id: Long) {
        val got = app.get("/payments/$id").body
        assertThat(got["status"].asText()).isEqualTo("APPROVED")
        assertThat(got["canceledAmount"].asLong()).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_cancel WHERE payment_id = ?", Int::class.javaObjectType, id)).isZero()
    }

    @Test
    fun `취소 중 PG 5xx · 연결 실패 - 502 · 롤백`() {
        val id = approvedPaymentId()
        fakePg.onCancel = { throw PaymentException(ErrorCode.PG_UNAVAILABLE) }

        val res = app.post("/payments/$id/cancel", """{"cancelAmount":1000}""")
        assertThat(res.status).isEqualTo(502)
        assertThat(res.body["code"].asText()).isEqualTo("PG_UNAVAILABLE")
        assertUnchanged(id)
    }

    @Test
    fun `취소 중 PG 읽기 타임아웃 - 504 · 롤백`() {
        val id = approvedPaymentId()
        fakePg.onCancel = { throw PaymentException(ErrorCode.PG_TIMEOUT) }

        val res = app.post("/payments/$id/cancel", null)
        assertThat(res.status).isEqualTo(504)
        assertThat(res.body["code"].asText()).isEqualTo("PG_TIMEOUT")
        assertUnchanged(id)
    }

    @Test
    fun `취소를 PG가 업무 거절 - 409 PG_REJECTED · 상태 불변`() {
        val id = approvedPaymentId()
        fakePg.onCancel = { PgCancelResult.Rejected("ALREADY_CANCELED", "이미 취소된 결제입니다.") }

        val res = app.post("/payments/$id/cancel", null)
        assertThat(res.status).isEqualTo(409)
        assertThat(res.body["code"].asText()).isEqualTo("PG_REJECTED")
        assertUnchanged(id)
    }

    @Test
    fun `취소 금액 생략 - PG에는 우리 스냅샷의 남은 금액을 명시해 보낸다`() {
        val id = approvedPaymentId(10_000)
        app.post("/payments/$id/cancel", """{"cancelAmount":2500}""")
        app.post("/payments/$id/cancel", "{}")
        assertThat(fakePg.lastCancel?.cancelAmount).isEqualTo(7_500)
    }

    @Test
    fun `앞 단계에서 거부되면 PG를 부르지 않는다`() {
        val id = approvedPaymentId(10_000)
        assertThat(app.post("/payments/$id/cancel", """{"cancelAmount":10001}""").body["code"].asText()).isEqualTo("CANCEL_AMOUNT_EXCEEDED")
        assertThat(app.post("/payments/${Long.MAX_VALUE}/cancel", null).body["code"].asText()).isEqualTo("PAYMENT_NOT_FOUND")
        assertThat(app.post("/payments/$id/cancel", """{"cancelAmount":0}""").body["code"].asText()).isEqualTo("INVALID_REQUEST")
        assertThat(fakePg.lastCancel).isNull()
    }

    @Test
    fun `API 밖 진입점의 200자 넘는 취소 사유는 PG 호출 전에 INVALID_REQUEST`() {
        val id = approvedPaymentId(10_000)
        val error = runCatching { cancelUseCase.cancel(CancelPaymentCommand(id, 1_000, "r".repeat(201))) }.exceptionOrNull()
        assertThat((error as PaymentException).errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(fakePg.lastCancel).isNull()
        assertUnchanged(id)
        // 입력 검증이 존재 판정보다 먼저다
        val missing = runCatching { cancelUseCase.cancel(CancelPaymentCommand(Long.MAX_VALUE, null, "r".repeat(201))) }.exceptionOrNull()
        assertThat((missing as PaymentException).errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    class FakePgClient : PgClient {
        @Volatile
        var onCancel: () -> PgCancelResult = { PgCancelResult.Canceled }

        @Volatile
        var lastCancel: PgCancelRequest? = null

        val approveCalls = java.util.concurrent.atomic.AtomicInteger()

        /** 승인 호출 직전에 실행 — 계약 밖 예외(500) 시험용 */
        @Volatile
        var beforeApprove: () -> Unit = {}

        override fun approve(request: PgApproveRequest): PgApproveResult =
            approveCalls.incrementAndGet().also { beforeApprove() }.let { _ -> PgApproveResult.Approved("pk_fake_" + UUID.randomUUID().toString().replace("-", ""), Instant.now()) }

        override fun cancel(paymentKey: String, request: PgCancelRequest): PgCancelResult {
            lastCancel = request
            return onCancel()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakePgConfig {
        @Bean
        @Primary
        fun fakePgClient(): FakePgClient = FakePgClient()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
        }
    }
}
