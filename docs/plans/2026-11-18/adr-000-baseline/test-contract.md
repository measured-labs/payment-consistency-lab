# 테스트 계약 (blind 테스트 ↔ 구현 공통 — ADR-000 §6 · §7과 함께 읽는다)

구현 · 테스트 양쪽이 이 이름을 그대로 쓴다. 바꾸려면 이 문서를 먼저 고친다.

## 빌드 · 모듈

- `settings.gradle.kts`: `include("mock-pg")` — Gradle 경로 `:mock-pg`, 폴더 `mock-pg/`.
- 루트(앱) `build.gradle.kts`: `testImplementation(project(":mock-pg"))` · Testcontainers(`org.testcontainers:postgresql` · `junit-jupiter`) · `spring-boot-testcontainers` · PostgreSQL 드라이버 · Flyway(`flyway-core` · `flyway-database-postgresql`) · JPA.
- 앱 메인: `com.jun.labs.paymentconsistency.PaymentConsistencyApplication`.
- 흉내 PG 메인: `com.jun.labs.mockpg.MockPgApplication`(패키지 `com.jun.labs.mockpg`) · 기본 포트 8110.

## 설정 이름 (테스트가 속성으로 덮어쓴다)

- 앱: `payment.pg.base-url` · `payment.pg.connect-timeout`(Duration, 기본 3s) · `payment.pg.read-timeout`(Duration, 기본 10s) · DB는 `spring.datasource.*`.
- 흉내 PG: `server.port` · `mockpg.reject-duplicate-order-id`(기본 true) · `mockpg.fault.latency-min-ms` · `mockpg.fault.latency-max-ms` · `mockpg.fault.error-rate` · `mockpg.fault.drop-rate` · `mockpg.fault.drop-hold-ms`(기본 15000).

## 테스트 환경 (blind 워커가 스스로 만든다 — 구현 코드에 기대지 않는다)

- DB: Testcontainers `postgres:16`(앱 테스트당 공유 가능).
- 흉내 PG: 테스트 JVM 안에서 `SpringApplicationBuilder(MockPgApplication::class.java).properties("server.port=0", …).run()`으로 띄우고, 실제 포트를 `local.server.port`로 읽어 앱의 `payment.pg.base-url`에 넣는다(`@DynamicPropertySource`). 장애 설정이 다른 시나리오는 다른 흉내 PG 인스턴스(다른 테스트 클래스)로.
- 흉내 PG 모듈은 **DB에 기대지 않는다**(JPA · DataSource · Flyway 의존 없음). 다만 앱 테스트 JVM classpath에는 JPA · Flyway가 있으므로, 테스트에서 흉내 PG를 띄울 때는 DataSource · Hibernate JPA · JPA 리포지토리 · Flyway 자동 구성을 끄고, 속성은 `--key=value` 명령행 인자로 넘긴다(흉내 PG의 `application.yml`보다 우선).
- 앱: `@SpringBootTest(webEnvironment = RANDOM_PORT)` · HTTP로만 부른다(블랙박스). DB 확인은 `JdbcTemplate`로 ADR-000 §5 테이블을 직접 읽어도 된다.
- 타임아웃 시나리오는 `payment.pg.read-timeout=1s` · `mockpg.fault.drop-hold-ms=3000`처럼 줄여서 쓴다.
- 흉내 PG 단독 테스트는 `mock-pg/src/test/...`에서 HTTP로.

## 파일 위치

- blind 테스트: `src/test/kotlin/com/jun/labs/paymentconsistency/blind/` · `mock-pg/src/test/kotlin/com/jun/labs/mockpg/blind/` — 구현 워커는 이 폴더를 열지 않는다.
- 구현 테스트: 그 밖의 test 경로.
