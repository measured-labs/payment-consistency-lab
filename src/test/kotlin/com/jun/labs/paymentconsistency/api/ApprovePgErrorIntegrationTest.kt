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

/** PG 5xx(error 1.0) → 502 PG_UNAVAILABLE · 롤백(행 없음) · PG도 처리하지 않았다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovePgErrorIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `PG 5xx - 502 PG_UNAVAILABLE · 행 없음`() {
        val orderId = "order-" + UUID.randomUUID()
        val res = Http(rest).post(
            "/payments",
            """{"orderId":"$orderId","amount":10000,"cardNumber":"4111111111111111"}""",
            mapOf("X-User-Id" to "1"),
        )

        assertThat(res.status).isEqualTo(502)
        assertThat(res.body["code"].asText()).isEqualTo("PG_UNAVAILABLE")
        assertThat(res.body["message"].asText()).isEqualTo("PG를 사용할 수 없습니다.") // 'PG 응답 500' 같은 세부는 로그에만
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Int::class.javaObjectType, orderId)).isZero()
        assertThat(Http(rest, MockPgLauncher.baseUrl(mockPg)).get("/v1/payments/orders/$orderId").status).isEqualTo(404)
    }

    companion object {
        private val mockPg by lazy { MockPgLauncher.start("mockpg.fault.error-rate=1.0") }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("payment.pg.base-url") { MockPgLauncher.baseUrl(mockPg) }
        }
    }
}
