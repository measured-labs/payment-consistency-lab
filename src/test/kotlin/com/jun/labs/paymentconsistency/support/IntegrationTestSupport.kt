package com.jun.labs.paymentconsistency.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.mockpg.MockPgApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer

/** JVM당 PostgreSQL 16 하나 — 테스트 클래스끼리 공유한다(orderId를 매번 새로 만들어 섞이지 않게) */
object Postgres {
    private val container: PostgreSQLContainer<*> by lazy { PostgreSQLContainer<Nothing>("postgres:16").apply { start() } }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { container.jdbcUrl }
        registry.add("spring.datasource.username") { container.username }
        registry.add("spring.datasource.password") { container.password }
        // 테스트 클래스마다 @DynamicPropertySource가 달라 컨텍스트가 클래스 수만큼 캐시된다 — 기본 풀 10 × 13 > max_connections 100
        registry.add("spring.datasource.hikari.maximum-pool-size") { "4" }
    }
}

/** 흉내 PG를 같은 JVM 랜덤 포트로 띄운다. 속성은 명령행 인자(앱 application.yml보다 우선) */
object MockPgLauncher {
    fun start(vararg properties: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(MockPgApplication::class.java)
            .run("--server.port=0", "--spring.main.banner-mode=off", *properties.map { "--$it" }.toTypedArray())

    fun baseUrl(context: ConfigurableApplicationContext): String =
        "http://localhost:" + context.environment.getProperty("local.server.port")
}

class HttpResult(val status: Int, val body: JsonNode, val headers: HttpHeaders = HttpHeaders())

/** 블랙박스 HTTP 호출 — 원시 JSON 문자열을 그대로 보낸다(1.5 · "1000" 같은 형식 검증용) */
class Http(private val rest: TestRestTemplate, private val baseUrl: String = "") {
    private val mapper = ObjectMapper()

    fun post(path: String, json: String?, headers: Map<String, String> = emptyMap()): HttpResult =
        exchange(HttpMethod.POST, path, json, headers)

    fun get(path: String): HttpResult = exchange(HttpMethod.GET, path, null, emptyMap())

    private fun exchange(method: HttpMethod, path: String, json: String?, headers: Map<String, String>): HttpResult {
        val httpHeaders = HttpHeaders().apply {
            if (json != null) contentType = MediaType.APPLICATION_JSON
            headers.forEach { (k, v) -> set(k, v) }
        }
        val response = rest.exchange(baseUrl + path, method, HttpEntity(json, httpHeaders), String::class.java)
        val body = response.body?.let { mapper.readTree(it) } ?: mapper.nullNode()
        return HttpResult(response.statusCode.value(), body, response.headers)
    }
}
