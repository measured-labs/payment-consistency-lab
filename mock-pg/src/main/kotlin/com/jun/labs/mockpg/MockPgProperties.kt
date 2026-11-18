package com.jun.labs.mockpg

import org.springframework.boot.context.properties.ConfigurationProperties

/** 기동 설정으로만 바꾼다(관리 API 없음 — ADR-000 §7) */
@ConfigurationProperties("mockpg")
data class MockPgProperties(
    /** 같은 orderId의 두 번째 승인을 DUPLICATED_ORDER_ID로 거절한다(실제 PG와 같게). false면 이중 승인 관측용 */
    val rejectDuplicateOrderId: Boolean = true,
    val fault: Fault = Fault(),
) {
    /** 승인 · 취소에만 적용(조회는 주입 없음). 기본값 모두 0 · 시드 없는 무작위 */
    data class Fault(
        val latencyMinMs: Long = 0,
        val latencyMaxMs: Long = 0,
        /** 이 확률로 처리하지 않고 500 */
        val errorRate: Double = 0.0,
        /** 이 확률로 처리한 뒤 dropHoldMs 동안 응답하지 않는다(응답 유실) */
        val dropRate: Double = 0.0,
        val dropHoldMs: Long = 15_000,
    ) {
        init {
            require(latencyMinMs >= 0 && latencyMaxMs >= latencyMinMs) { "mockpg.fault.latency: 0 <= min <= max" }
            require(errorRate in 0.0..1.0) { "mockpg.fault.error-rate: 0..1" }
            require(dropRate in 0.0..1.0) { "mockpg.fault.drop-rate: 0..1" }
            require(dropHoldMs >= 0) { "mockpg.fault.drop-hold-ms >= 0" }
        }
    }
}
