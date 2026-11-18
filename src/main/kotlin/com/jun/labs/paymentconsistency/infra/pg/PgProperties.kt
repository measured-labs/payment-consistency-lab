package com.jun.labs.paymentconsistency.infra.pg

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** ADR-000 §4 — 연결 3초 · 읽기 10초(README Load Profile) */
@ConfigurationProperties("payment.pg")
data class PgProperties(
    val baseUrl: String = "http://localhost:8110",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(10),
)
