package com.jun.labs.paymentconsistency

import com.jun.labs.paymentconsistency.support.MockPgLauncher
import com.jun.labs.paymentconsistency.support.Postgres
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
class PaymentConsistencyApplicationTests {

    @Test
    fun contextLoads() {
    }

    companion object {
        private val mockPg by lazy { MockPgLauncher.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("payment.pg.base-url") { MockPgLauncher.baseUrl(mockPg) }
        }
    }
}
