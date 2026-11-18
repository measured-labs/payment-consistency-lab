# log — payment-consistency-lab ADR-000 기준선

> 명세: `requirement-spec.md` (SPEC=1 · auto · stakes 높음) · 브랜치 `feat/adr-000-baseline`(main c41e8e0에서)

## 타임라인

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-11-18 01:2x | 인터뷰 Q1~Q11 · 사용자 지적(원장 · 결정적 주입은 진행하며 드러나야 함 → seat lab mock-pg 복사 안 함) · 웹훅 별도 ADR 동의 | 명세 작성 |
| 2026-11-18 01:3x | 사용자 "응 합의, 진행해줘" → set-state spec-approved · mode auto · 브랜치 feat/adr-000-baseline 생성 | SPEC=1 · MODE=auto |
| 2026-11-18 01:4x | 추가 결정(AskUserQuestion): 흉내 PG orderId 중복 = 설정 · 기본 거절(실제 PG와 같게) | ADR-000 §흉내 PG에 반영 |
| 2026-11-18 01:5x | task 01 ADR-000 초안(docs/adr/ADR-000-baseline-domain.md) · 설계 선검증 codex(medium) finding 3: ① 입력 길이 검증 없음(orderId 64 · reason 200) ② PG 취소 금액 생략 의미 미정 ③ PG · 앱 상태 이름 변환 규칙 없음 → 전부 채택 · 수정(§6.1 입력 검증 · 판정 순서 · 앱은 취소 금액 명시 · 앱 상태는 도메인 계산) | 설계 확정 |
| 2026-11-18 02:0x | test-contract.md 작성(모듈 · 메인 클래스 · 설정 이름 · blind 테스트 환경) · 워커 2 병렬 착수: 구현(task 03) ∥ blind 테스트(task 02) — 둘 다 빌드 · 실행 금지(ADR-017 캠페인 PC 부하 방지) · 서로의 폴더 열람 금지 | 진행 |
| 2026-11-18 02:1x | task 02 회수(blind 워커 · 108.7k 토큰 · 419초): 앱 blind 7파일 · mock-pg blind 5파일 · 미컴파일 · 구현 미열람 확인 · 계약 모호점 8 → 메인 결정 · ADR §6.1 · §7 · test-contract 반영(X-User-Id 승인만 · 엄격 정수 · cancelAmount null = 생략 · 깨진 JSON · Long 범위 400 · 거절 승인 미기록 · PG INVALID_REQUEST 정의 · 취소 경로 502/504는 구현 테스트 · mock-pg DB 무의존) · 구현 워커에 SendMessage로 전달 | 진행 |
| 2026-11-18 02:3x | task 03 회수(구현 워커): 앱(domain · service · infra/pg · api · V1) · mock-pg 모듈 · 구현 테스트 7 + mock-pg 1 · Dockerfile 2 · docker-compose.yml · Testcontainers 1.21.4로 올림(Docker 29 호환) · 미컴파일 · blind 미열람 · 모호점 4(연결 타임아웃 502 · drop은 거절 결과에도 · PG 판정 순서 · 응답 필드 없음 502) → 워커 해석 채택 · ADR §6 · §7 확정 | 빌드는 캠페인 뒤 |
| 2026-11-18 15:5x | ADR-017 캠페인 끝 · task 04 착수 — 전체 테스트 `cleanTest test --continue` 1분 38초: 앱 13클래스 84/0/0(blind 6 · 구현 7 포함) · mock-pg 5클래스 29/0/0 · 30분 넘은 XML 0 · 첫 실행 컴파일 오류 0 | green |
| 2026-11-18 16:0x | compose 빌드 실패: 앱 Dockerfile `./gradlew bootJar`가 하위 모듈 `:mock-pg:bootJar`까지 실행(앱 이미지엔 mock-pg 소스 없음 → mainClass 해석 실패) → `:bootJar`로 수정(task 03 파일) | 재빌드 ok |
| 2026-11-18 16:1x | 스모크 1(기본 · 주입 0): 승인 201 APPROVED → 조회 200 → 부분 취소 3000 PARTIALLY_CANCELED → 전체 취소(금액 생략 → 7000) CANCELED → 재취소 409 PAYMENT_NOT_CANCELABLE → 거절 카드 201 FAILED CARD_DECLINED → amount 0 400 · record 대조: DB payment 2행(CANCELED 10000/10000 · FAILED) · payment_cancel 2행(3000 · 7000) · PG 키 조회 CANCELED balance 0 취소 2 · PG smk-2 404(거절 미기록) · smk-3 DB 0행 | 일치 |
| 2026-11-18 16:1x | 가정 1(PG 지연 2초 · 동시 30): pg_stat_activity `idle in transaction` 10(풀 전부) · active 1 — 3회 표본 모두 · 30건 201 · 벽 7.5초 · 응답 min 3.39 · p50 5.42 · max 7.46초(PG 2초 → Hikari 대기가 응답에 더해짐) | 가정 1 실증 |
| 2026-11-18 16:1x | 가정 2(drop 1.0 · hold 15초): 앱 504 PG_TIMEOUT 10.02초 · PG 주문 조회 DONE 4200(돈 빠짐) · DB drop-1 0행(REQUESTED 롤백) — Q2 결과 모름 재현 · 관찰: 504 본문 message에 내부 PG URL · 예외 문구 노출 → 리뷰 후보 · 정리(compose down -v · 컨테이너 0) | 가정 2 실증 |
| 2026-11-18 16:3x | 리뷰 루프 1(codex medium 1 · low 2 — 83.7k 토큰 ∥ Opus 워커 187.9k 토큰 medium 3 · low 5) 종합 PL1-1~9 · 채택 6 · 이월 3 | 수정 착수 |
| 2026-11-18 16:5x | 루프 1 수정: RestPgClient 원인 문구를 응답에서 뺌(cause만) · ApiExceptionHandler 5xx WARN 로그 · 표 밖 오류 {code, message}(HTTP_<상태> · 500 INTERNAL_ERROR) · ErrorCode INTERNAL_ERROR · 엄격 정수(`[0-9]+` — '+5' · '+7' 400) · 카드번호 toString 가림 3곳 · 테스트: 새 ApiErrorContractTest 4(입력 오류 승인 PG 미호출 — 호출 수 대조 · 부호 숫자 · 404/405 본문 · 500 본문) · PaymentApi 실제 HTTP 취소 금액 명시(PG만 4000 먼저 → 생략 취소 409 PG_REJECTED) · 상태 단언 보강 2곳 · 타임아웃 응답 문구 단언 · ADR §6 오류 본문 3줄 | — |
| 2026-11-18 17:0x | 전체 `cleanTest test --continue`: 앱 14클래스 89/0/0 · mock-pg 29/0/0 · 10분 넘은 XML 0 · red 확인: PG_TIMEOUT에 e.message를 되돌리면 ApprovePgTimeoutIntegrationTest:38 실패 → 원복 · green(첫 red 시도는 `--tests`가 :mock-pg:test에도 걸려 'No tests found'로 무효 — `:test`로 재실행) | green |
| 2026-11-18 17:2x | 리뷰 루프 2(codex: PL1-1~6 fixed · 신규 2 ∥ Opus 워커 93.1k 토큰: 신규 medium 1 · low 4 · info 1) 종합 PL2-1~6 | 수정 |
| 2026-11-18 17:3x | 루프 2 수정: 5xx 응답 message = 코드 고정 문구(세부는 WARN 로그) · 502 문구 단언 · 테스트 Hikari 풀 4(컨텍스트 13 × 10 > max_connections 100) · 주석 정정 · toString 카드번호 단언 · Spring 표준 5xx 로그 · 405 Allow 헤더 유지 + 단언 · ADR §6 415 · 406 예외 문구 · 전체 `cleanTest test --continue`: 앱 14클래스 90/0/0 · mock-pg 29/0/0 · 10분 넘은 XML 0 | green |
| 2026-11-18 17:4x | post-fix 재점검(codex): PL2-1~6 해결 · 신규 없음 → 리뷰 마감(2루프 + 재점검) · 최종 compose 스모크: drop 1.0 → 504 본문 고정 문구 · WARN 로그 · 로그 속 카드번호 0 · 정상 승인 201 · DELETE → 405 + Allow 헤더 · 정리(컨테이너 0) | 검증 완료 |
| 2026-11-18 17:5x | 코드 커밋 58ca09d(58파일 — docs 제외) · ADR-000 채택 · §10.1 결과 · NEXT.md · measurement-log 신설 | docs 커밋 |

## 리뷰 ledger

| id | 루프 | 출처 | 위치 | 판정 | 상태 |
|----|------|------|------|------|------|
| P0-1 | 선검증 | codex | ADR-000:126 · 144 · 156 | 채택 — 입력 길이 검증 · 400 | fixed |
| P0-2 | 선검증 | codex | ADR-000:119 · 173 | 채택 — 취소 금액 생략 의미 · 앱은 명시 | fixed |
| P0-3 | 선검증 | codex | ADR-000:58 · 173 | 채택 — 상태는 도메인 계산 · 이름 대응 참고 | fixed |
| PL1-1 | 1 | codex · opus | RestPgClient.kt:84 · 93 · 95 | 채택 — 오류 본문에 내부 PG URL · 예외 원문(스모크 관측) | fixed |
| PL1-2 | 1 | opus | ApiExceptionHandler.kt | 채택 — 502 · 504 원인 로그 없음 | fixed |
| PL1-3 | 1 | opus | ApiExceptionHandler.kt | 채택 — 표 밖 오류 본문 모양(500 · 404 · 405) · ADR §6 | fixed |
| PL1-4 | 1 | opus | PaymentRequests.kt:43 · 47 | 채택 — '+5' · '+7' 통과 | fixed |
| PL1-5 | 1 | opus | PaymentRequests · ApprovePaymentCommand · PgApproveRequest | 채택 — 카드번호 toString | fixed |
| PL1-6 | 1 | codex · opus | ValidationBlindTest:46 · CancelPgFailure:104-118 · PaymentApi:85 | 채택 — PG 미호출 증명 · 취소 금액 명시 실제 HTTP · 상태 단언(blind 파일은 그대로 · 구현 테스트로 보강) | fixed |
| PL1-7 | 1 | opus | RestPgClient.kt:43 | 이월 — 2xx 본문 수신 중 타임아웃이 502(지금 PG는 헤더 전에 붙잡아 도달 불가) → 결정적 주입 ADR | deferred |
| PL1-8 | 1 | opus | ApprovePgConnectionRefusedIntegrationTest:42 · 컨텍스트 캐시 연결 수 | 이월 — 확률 낮음 · 테스트 클래스 늘 때 재확인 | deferred |
| PL1-9 | 1 | codex | PgDropTimeoutBlindTest:24 | 이월 — PG는 hold 전에 기록(1초 안 미처리 확률 낮음) · flaky 관측 시 처리 | deferred |
| PL2-1 | 2 | codex · opus | RestPgClient.kt:47 · 52 · 67 · 80 · ApiExceptionHandler.kt:25 | 채택 — 502 문구가 ADR 고정 문구와 다름 · 502 단언 없음 | fixed |
| PL2-2 | 2 | codex · opus | ApiErrorContractTest · CancelPgFailureIntegrationTest | 채택(부분) — '컨텍스트 공유' 주석 거짓 · 컨텍스트 13 × 풀 10 > 100 → 테스트 풀 4 · 공유 상태 경합은 컨텍스트가 따로라 없음 | fixed |
| PL2-3 | 2 | opus | toString 3곳 · 로그 | 채택(부분) — toString 단언 · 로그 캡처 테스트는 생략(선택 항목) | fixed |
| PL2-4 | 2 | opus | ApiExceptionHandler.kt:39-42 | 채택 — Spring 표준 5xx 무로그 | fixed |
| PL2-5 | 2 | opus | ApiExceptionHandler.kt:41 | 채택 — 405 Allow 헤더 버림 | fixed |
| PL2-6 | 2 | opus | ApiExceptionHandler.kt:34 · ADR §6 | 채택 — 406 보장 서술 · 415 예외 문서 | fixed |

## 생략한 검증

- (없음)

## 완료 요약 (2026-11-18)

- ADR-000 기준선 구현 · 검증 끝 — 코드 커밋 58ca09d · 브랜치 `feat/adr-000-baseline`(push 안 함).
- 핵심 코드(실파일):
  - `ApprovePaymentService.kt:26-31` — 기준선 그대로 한 트랜잭션 안 PG 호출:
    ```kotlin
    @Transactional
    override fun approve(command: ApprovePaymentCommand): PaymentResult {
        val payment = paymentRepository.save(
            Payment.request(command.orderId, command.userId, command.amount, clock.instant()),
        )
        when (val result = pgClient.approve(PgApproveRequest(command.orderId, command.amount, command.cardNumber))) {
    ```
  - 리뷰 수정 before → after: `RestPgClient.kt:94` `PaymentException(ErrorCode.PG_TIMEOUT, e.message, e)` → `PaymentException(ErrorCode.PG_TIMEOUT, cause = e)` · `ApiExceptionHandler.kt:24-27`:
    ```kotlin
    if (status.is5xxServerError) {
        log.warn("{} — {}", e.errorCode, e.message, e.cause)
        return ResponseEntity.status(status).body(ErrorResponse(e.errorCode.name, e.errorCode.message))
    }
    ```
- 검증: 테스트 앱 90 · mock-pg 29(실패 0) · compose 스모크 2회(record 대조) · load-bearing 가정 2개 실증(Q7 풀 10 전부 idle in transaction · Q2/Q6 504 + PG만 승인) · 듀얼 리뷰 2루프 + 재점검(채택 12 · 이월 3) · red 확인 1.
- 이월: PL1-7(본문 수신 중 타임아웃 분류) · PL1-8(포트 재사용 · 컨텍스트 수 — 풀 4로 완화) · PL1-9(drop 테스트 타이밍) → NEXT.
- CS 이슈 아카이브: 보류 — 명세 §4가 study-note 수정 금지(이번 작업 범위 밖). 후보 1건: '응답 유실 = 돈은 빠졌는데 우리 DB엔 없음'(Q2/Q6 재현) — 사용자 확인 뒤.
- 남은 것: push · PR(사용자 확인).

