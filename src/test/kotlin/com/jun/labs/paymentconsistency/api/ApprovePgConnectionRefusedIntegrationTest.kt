package com.jun.labs.paymentconsistency.api

import com.jun.labs.paymentconsistency.support.Http
import com.jun.labs.paymentconsistency.support.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ServerSocket
import java.util.UUID

/** PG 연결 실패 → 502 PG_UNAVAILABLE · 행 없음 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovePgConnectionRefusedIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `연결 실패 - 502 PG_UNAVAILABLE`() {
        val orderId = "order-" + UUID.randomUUID()
        val res = Http(rest).post(
            "/payments",
            """{"orderId":"$orderId","amount":10000,"cardNumber":"4111111111111111"}""",
            mapOf("X-User-Id" to "1"),
        )

        assertThat(res.status).isEqualTo(502)
        assertThat(res.body["code"].asText()).isEqualTo("PG_UNAVAILABLE")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Int::class.javaObjectType, orderId)).isZero()
    }

    companion object {
        /** 잠깐 열었다 닫은 포트 — 아무도 듣지 않는다 */
        private val closedPort: Int by lazy { ServerSocket(0).use { it.localPort } }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("payment.pg.base-url") { "http://localhost:$closedPort" }
        }
    }
}
