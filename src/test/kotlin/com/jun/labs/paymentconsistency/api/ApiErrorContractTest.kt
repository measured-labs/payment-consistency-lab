package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.infra.pg.PgApproveRequest
import com.jun.labs.paymentconsistency.service.CancelPgFailureIntegrationTest
import com.jun.labs.paymentconsistency.support.Http
import com.jun.labs.paymentconsistency.support.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 오류 본문 계약(ADR-000 §6 — 언제나 {code, message}) · 엄격 정수(§6.1) · 입력 오류면 PG 승인을 부르지 않는다.
 * 가짜 PgClient(호출 수를 센다)는 CancelPgFailureIntegrationTest와 같은 구성을 가져다 쓴다(@DynamicPropertySource가 클래스마다 달라 컨텍스트 · 빈은 따로다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CancelPgFailureIntegrationTest.FakePgConfig::class)
class ApiErrorContractTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var fakePg: CancelPgFailureIntegrationTest.FakePgClient

    private val app by lazy { Http(rest) }

    @AfterEach
    fun reset() {
        fakePg.approveCalls.set(0)
        fakePg.beforeApprove = {}
    }

    private fun approveWith(json: String, userId: String = "1") = app.post("/payments", json, mapOf("X-User-Id" to userId))

    @Test
    fun `입력 오류 승인 요청은 PG를 부르지 않는다`() {
        val bad = listOf(
            """{"orderId":"o-1","amount":0,"cardNumber":"4111111111111111"}""",
            """{"orderId":"","amount":1000,"cardNumber":"4111111111111111"}""",
            """{"orderId":"o-1","amount":1000}""",
            """{"orderId":"o-1","amount":1.5,"cardNumber":"4111111111111111"}""",
        )
        bad.forEach { assertThat(approveWith(it).status).`as`(it).isEqualTo(400) }
        assertThat(approveWith("""{"orderId":"o-1","amount":1000,"cardNumber":"4111111111111111"}""", userId = "+7").status).isEqualTo(400)
        assertThat(fakePg.approveCalls.get()).isZero()

        // 대조: 올바른 요청이면 정확히 한 번 부른다(세는 장치가 실제로 동작함)
        assertThat(approveWith("""{"orderId":"o-ok","amount":1000,"cardNumber":"4111111111111111"}""").status).isEqualTo(201)
        assertThat(fakePg.approveCalls.get()).isEqualTo(1)
    }

    @Test
    fun `부호 붙은 숫자는 양의 정수가 아니다 - 경로 id · X-User-Id`() {
        val res = app.get("/payments/+5")
        assertThat(res.status).isEqualTo(400)
        assertThat(res.body["code"].asText()).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `계약 밖 오류도 본문은 code · message - 없는 경로 404 · 메서드 405`() {
        val notFound = app.get("/no-such-path")
        assertThat(notFound.status).isEqualTo(404)
        assertThat(notFound.body["code"].asText()).isEqualTo("HTTP_404")
        assertThat(notFound.body.has("message")).isTrue()

        val wrongMethod = app.get("/payments")
        assertThat(wrongMethod.status).isEqualTo(405)
        assertThat(wrongMethod.body["code"].asText()).isEqualTo("HTTP_405")
        assertThat(wrongMethod.headers["Allow"]).contains("POST")
    }

    @Test
    fun `처리하지 못한 예외는 500 INTERNAL_ERROR · 원문은 응답에 없다`() {
        fakePg.beforeApprove = { throw IllegalStateException("내부 비밀 문구") }
        val res = approveWith("""{"orderId":"o-500","amount":1000,"cardNumber":"4111111111111111"}""")
        assertThat(res.status).isEqualTo(500)
        assertThat(res.body["code"].asText()).isEqualTo("INTERNAL_ERROR")
        assertThat(res.body["message"].asText()).doesNotContain("내부 비밀 문구")
    }

    @Test
    fun `카드번호는 요청 · 명령 · PG 요청의 문자열 표현에 없다`() {
        val card = "4111111111111111"
        val request = ApprovePaymentRequest("o-1", 1000, card)
        listOf(request.toString(), request.toCommand(1).toString(), PgApproveRequest("o-1", 1000, card).toString())
            .forEach { assertThat(it).doesNotContain(card).contains("***") }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
        }
    }
}
