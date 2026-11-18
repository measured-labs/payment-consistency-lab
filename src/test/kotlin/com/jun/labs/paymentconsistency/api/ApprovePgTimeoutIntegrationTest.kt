package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.support.Http
import com.jun.labs.paymentconsistency.support.MockPgLauncher
import com.jun.labs.paymentconsistency.support.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/** 응답 유실(drop 1.0) → 읽기 타임아웃 504 · 롤백(행 없음) · PG에는 승인이 있다(Q6 기준선 모습) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovePgTimeoutIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `읽기 타임아웃 - 504 PG_TIMEOUT · 우리 DB 행 없음 · PG는 승인`() {
        val orderId = "order-" + UUID.randomUUID()
        val res = Http(rest).post(
            "/payments",
            """{"orderId":"$orderId","amount":10000,"cardNumber":"4111111111111111"}""",
            mapOf("X-User-Id" to "1"),
        )

        assertThat(res.status).isEqualTo(504)
        assertThat(res.body["code"].asText()).isEqualTo("PG_TIMEOUT")
        // 응답에는 고정 문구만 — 내부 PG 주소 · 예외 원문이 나가지 않는다
        assertThat(res.body["message"].asText()).isEqualTo("PG 응답 시간을 초과했습니다.")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Int::class.javaObjectType, orderId)).isZero()

        val pg = Http(rest, MockPgLauncher.baseUrl(mockPg)).get("/v1/payments/orders/$orderId")
        assertThat(pg.status).isEqualTo(200)
        assertThat(pg.body["status"].asText()).isEqualTo("DONE")
    }

    companion object {
        private val mockPg by lazy {
            MockPgLauncher.start("mockpg.fault.drop-rate=1.0", "mockpg.fault.drop-hold-ms=3000")
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("payment.pg.base-url") { MockPgLauncher.baseUrl(mockPg) }
            registry.add("payment.pg.read-timeout") { "1s" }
        }
    }
}
