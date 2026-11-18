rootProject.name = "payment-consistency-lab"

// mock-pg = 흉내 PG(ADR-000 §7) — 별도 프로세스로 띄우고, 앱 테스트는 같은 JVM 랜덤 포트로 띄운다
include("mock-pg")
