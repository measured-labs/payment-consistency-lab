package com.jun.labs.mockpg

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * 흉내 PG(ADR-000 §7). DB를 쓰지 않는다 — 앱 테스트 JVM(클래스패스에 JPA · Flyway · 앱 application.yml이 있다)에서 띄워도
 * DB 자동 구성이 붙지 않게 막는다. 설정 기본값은 코드에 둔다(application.yml 없음 — 같은 JVM의 앱 설정 파일에 가려지므로).
 */
@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@ConfigurationPropertiesScan
class MockPgApplication {
    @Bean
    fun mockPgClock(): Clock = Clock.systemUTC()
}

const val DEFAULT_PORT = 8110

fun main(args: Array<String>) {
    runApplication<MockPgApplication>(*args) {
        setDefaultProperties(mapOf("server.port" to DEFAULT_PORT))
    }
}
