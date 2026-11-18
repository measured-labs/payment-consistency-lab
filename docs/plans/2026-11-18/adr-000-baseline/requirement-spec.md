# 요구사항 명세서 (requirement-spec)

> 작성일: 2026-11-18 · 작업 폴더: `payment-consistency-lab/docs/plans/2026-11-18/adr-000-baseline/`

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "다른 lab들도 슬슬 도메인을 만들어볼까" → 순서 논의(갈래 A 02 → 04 → 03 · 갈래 B 06 → 05 → 07) → "payment-consistency-lab부터 순서 정해보자" → "진행하자".
- Q/A (2026-11-18):
  1. 범위 = **ADR-000 기준선**: 도메인 설계 문서 + 평범한 구현 + 기능 테스트. 판정기 · 부하 하네스는 다음 작업(ADR-001).
  2. 기준선 성격 = **평범한 구현**: 멱등키 · 유니크 제약 · 아웃박스 없이 `@Transactional` 안에서 PG 동기 호출. README Q1~Q7이 실제로 깨지는 것을 이후 ADR에서 관측하고 하나씩 해결한다.
  3. 흉내 PG = 사용자 지적 원문: "1번으로 가져오면 결국 원장이 왜 필요한지 결정적 주입이 왜 필요한지 여기서 못보는 거 아니야? 결국 원장과 결정적 주입도 진행하면서 나타나는 부분일텐데" → **최소 흉내 PG를 같은 repo 별도 모듈로 새로 만든다**(승인 · 취소 · 조회 + 무작위 지연 · 실패). 대조용 원장 내보내기 · 결정적(시드) 주입은 필요가 드러나는 ADR에서 추가. seat-reservation-lab mock-pg는 복사하지 않는다(설계 비교 참고만).
  4. 도메인 = **결제 단독**: 주문 없이 가맹점 주문번호(orderId) + 금액. 주문 · 재고는 03 order-saga.
  5. 기록 = **repo docs**(ADR은 `docs/adr`, 작업 기록은 `docs/plans`) · study-note 반영은 사이클 마감 때 따로 확인.
  6. DB = **PostgreSQL 16 + JPA + Flyway** · 테스트 Testcontainers · Redis · Kafka 없음.
  7. stakes = **높음(정책 하한 — 결제)**.
  8. 승인 흐름 = **한 트랜잭션 안에서 PG 호출**: `@Transactional { 결제 저장 → PG 승인 호출 → 결과로 상태 갱신 }`.
  9. 기능 = 승인 · 전체 취소 · 조회 · **부분 취소** · 흉내 PG 주문번호 조회 API. **웹훅은 제외** — 사용자: "웹훅 수신도 나중에 PG가 결과를 다시 알려주는 흐름은 별도 ADR로 하는게 맞지 않아?"(동의 — Q4 · Q6 ADR에서).
  10. 브랜치 `feat/adr-000-baseline`(main에서 로컬) · 자율성 **auto**.
  12. (재합의 2026-11-18 · 병합 전 도메인 재리뷰 뒤) 사용자 "둘 다 지금 고침" → 범위 추가: ① orderId 문자 집합 `[A-Za-z0-9_-]` 1~64자(API · 도메인 — 이후 PG 주문번호 조회 경로 안전) ② 길이 검사를 경계 · 도메인으로: PG 승인 응답 paymentKey 64자 초과 = PG_UNAVAILABLE(502) · 취소 사유 200자 초과 = 도메인 INVALID_REQUEST(PG 호출 전). 문서: 동시 부분 취소의 갱신 유실 · 취소 PG 키 부재 · 취소 결과 불명 · failureCode 50자 자르기를 ADR §2.1 · §9 · NEXT에 사실대로.
  11. 문서 형식 = seat lab 관례(한국어 ADR · mermaid/ASCII 도식 · 상태 전이 · 시퀀스 · 이 기준선에서 깨질 것(Q1~Q7 가설 ADR 목록)).

---

## 1. 목표·대상 (필수)

`payment-consistency-lab` repo에서 ADR-000 기준선이 끝나는 상태: ① `docs/adr/ADR-000-baseline-domain.md`(도메인 모델 · 상태 전이 · 시퀀스 · API · 가설 ADR-001~ 목록) ② 앱(포트 8102): 결제 승인 `POST /payments` · 취소 `POST /payments/{id}/cancel`(전체 · 부분) · 조회 `GET /payments/{id}` ③ 흉내 PG 하위 모듈 `mock-pg`(승인 · 취소(부분) · paymentKey 조회 · 주문번호 조회 · 무작위 지연 · 실패 설정) ④ 기능 테스트 green ⑤ 로컬 docker compose 스모크(앱 · PG · DB).

## 2. 경계·불변식 (필수)

- **정상 경로 기능 불변식**(테스트로 고정):
  - 승인 성공 → 결제 `APPROVED` · PG에 같은 orderId · 금액의 승인 기록이 있다 · 응답과 DB가 같은 paymentKey를 가진다.
  - PG가 승인 거절(4xx 업무 실패) → 결제 `FAILED`(사유 기록) · 승인액 0.
  - 취소: 누적 취소액 ≤ 승인액 · 남은 금액 0이 되면 `CANCELED`, 아니면 `PARTIALLY_CANCELED` · 앱과 PG의 취소 누적액이 같다.
  - 승인되지 않은 결제(`FAILED` 등) · 이미 전부 취소된 결제의 취소 = 거부(4xx) · 상태 불변.
  - 입력 검증: 금액 > 0 · 취소액 > 0 · orderId 비어 있지 않음 → 위반은 400.
- **의도적 부재(기준선 원칙 — 문제를 가리지 않는다)**: 멱등키 · orderId 유니크 제약 · 락 · 아웃박스 · 재시도 · 결과 불명 처리 · 웹훅 · 정산 대조. 스키마에는 PK(와 필요한 FK)만 둔다. 이 부재를 테스트로 "막는다"고 단정하지 않는다(ADR-000에 가설로 적는다).
- **흉내 PG 경계**: 결제 기록은 PG 프로세스 메모리(재기동 시 사라짐을 ADR에 명시) · 장애 주입은 설정(지연 · 실패율 · 응답 유실 확률) 기본 0 · **시드 없는 무작위**(재현 불가를 그대로 둔다) · 관리용 원장 덤프 · 결정적 주입 API 없음.
- 흉내 PG 장애가 0인 기본 구성에서 모든 기능 테스트가 결정적으로 통과한다.

## 3. 기준소스 (필수)

- `payment-consistency-lab/README.md`(Questions Q1~Q7 · Architecture · Load Profile · Consistency Checks).
- 사용자 인터뷰 답(§0).
- 빌드 기준: 현재 `build.gradle.kts`(Kotlin 2.2.20 · Spring Boot 3.5.5 · JDK 21) · 포트 8102(상위 README 표).
- 구조 참고(읽기만): `seat-reservation-lab/docs/adr/ADR-000-baseline-domain.md`(문서 형식 · 기준선 원칙) · seat lab의 compose · Testcontainers 구성.

## 4. 금지영역 (필수)

- `seat-reservation-lab` 전부 수정 금지(ADR-017 캠페인 진행 중 — 읽기만) · 다른 lab repo · `/home/jun/project/study-note`.
- 서버 .164 · .9 · .158 사용 안 함(이번 작업은 로컬만).
- **ADR-017 캠페인이 끝나기 전(예상 2026-11-18 09시 전후)에는 이 PC에서 gradle 빌드 · 테스트 · docker 실행을 하지 않는다**(k6 PC 부하 → 캠페인 회차 env-send-lag 오염 방지). 그 전에는 문서 작업(ADR-000 설계 · 설계 선검증 · blind 테스트 계약)만.
- 웹훅 · 아웃박스 · 멱등키 · 유니크 제약 · 결정적 주입 · PG 원장 덤프 넣지 않음(§2 의도적 부재).
- push · PR · 원격 브랜치 생성은 결과 보고 뒤 사용자 확인. 커밋 · 발행 본문에 AI attribution 금지 · 검증 출처(codex · 리뷰)를 커밋 · 주석에 쓰지 않음 · code 커밋과 docs 커밋 분리.

## 5. 검증 방법 (필수)

- 설계 선검증(§2에 이 repo에 없던 동작 불변식이 있음 — 대상): ADR-000 설계를 구현 전 codex 교차 검증 1회.
- blind 테스트 워커: 이 명세 + ADR-000 + API 계약만 보고 테스트 작성(구현 미열람).
- 통합 테스트(Testcontainers PostgreSQL 16 · 흉내 PG는 실제 HTTP 서버로 띄움) + mock-pg 단위 테스트 · `./gradlew test` 전체 green(cleanTest로 실제 실행 확인).
- 로컬 스모크: docker compose(앱 · mock-pg · postgres) → 승인 → 조회 → 부분 취소 → 전체 취소 → 거부 경로 1건을 curl로 실행 · DB 행과 PG 조회 결과를 record-level로 대조.
- 듀얼 리뷰 루프(Opus 워커 ∥ codex → 종합 → 감사 → 재리뷰, 최대 3루프).
- 머지 전 안전선 ①~⑥(core §4).

## 6. stakes (필수)

- 판정: **높음** — 근거: 사용자 지정(정책 하한 — 결제 도메인 · public API 계약 신설). 실제 돈은 아니지만 이후 모든 ADR이 이 기준선 위에 얹힌다.

---

## 7. 자율성 (모드 — stakes와 별개 축)

- [x] auto (사용자 선택 2026-11-18)
- [ ] lazy

## 8. load-bearing 가정 (1~2개)

1. 한 트랜잭션 안 PG 동기 호출 기준선은 기능상 정상 경로를 모두 만족하면서도, 이후 부하 · 장애 주입에서 Q1(중복 승인) · Q2(PG 성공 + 커밋 실패) · Q7(PG 지연 → 커넥션 풀 포화)을 실제로 드러낼 수 있다 — 스모크에서 흉내 PG 지연 설정만으로 Hikari 대기가 생기는지 1회 확인.
2. 흉내 PG를 별도 프로세스(HTTP)로 두면 앱 쪽 타임아웃(연결 3초 · 읽기 10초 — README)으로 '응답 유실 = 결과 모름'을 재현할 수 있다 — 스모크에서 응답 유실 확률 1.0으로 1건 확인.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | ADR-000 설계 문서(도메인 · 상태 전이 · 시퀀스 · API · 흉내 PG 계약 · 가설 ADR 목록) | — | 문서 완성 · 설계 선검증(codex) 반영 |
| 02 | blind 테스트 계약 · 테스트 작성(워커 — 구현 미열람) | 01 | 테스트 파일 · 계약 모호점 목록 |
| 03 | 구현: 앱(엔티티 · Flyway V1 · 서비스 · API) + mock-pg 모듈 + compose (**캠페인 끝난 뒤 빌드**) | 01 | 컴파일 · 단위 테스트 |
| 04 | 테스트 green(blind + 구현 테스트) · 로컬 스모크 · load-bearing 가정 2개 확인 | 02 · 03 | 전체 green · 스모크 record 대조 |
| 05 | 듀얼 리뷰 루프 ≤3 → 수정 | 04 | ledger 마감 |
| 06 | 마감: 커밋(code · docs 분리) · 완료 요약 · NEXT.md · 측정로그 · push · PR은 확인 뒤 | 05 | 커밋 SHA · 보고 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입 (빈 칸 없음)
- [x] 사용자 합의 → SPEC=1 (2026-11-18 "응 합의, 진행해줘" · set-state 기록)
- [x] 자율성 선택 → auto
