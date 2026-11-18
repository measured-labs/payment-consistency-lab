package com.jun.labs.paymentconsistency

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class PaymentConsistencyApplication {
    /** 시각은 애플리케이션 Clock에서 얻는다(ADR-000 §3 — 테스트에서 고정 가능) */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

/**
 * 포트 8102(ADR-000 §4)는 application.yml이 아니라 기본 속성으로 둔다 — 앱 테스트 JVM에서 띄우는 흉내 PG가
 * 같은 클래스패스의 application.yml을 읽어 그 포트를 덮어쓰지 않게(SERVER_PORT · --server.port로 바꿀 수 있다).
 */
const val DEFAULT_PORT = 8102

fun main(args: Array<String>) {
    runApplication<PaymentConsistencyApplication>(*args) {
        setDefaultProperties(mapOf("server.port" to DEFAULT_PORT))
    }
}
