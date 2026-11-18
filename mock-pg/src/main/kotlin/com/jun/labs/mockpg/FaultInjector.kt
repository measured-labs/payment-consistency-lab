package com.jun.labs.mockpg

import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

/** 시드 없는 무작위 — 같은 장애를 다시 만들 수 없다(기준선 · ADR-000 §7) */
@Component
class FaultInjector(properties: MockPgProperties) {
    private val fault = properties.fault

    /** 응답 전 지연 ~ U[min, max] */
    fun delay() {
        if (fault.latencyMaxMs <= 0) return
        val ms = ThreadLocalRandom.current().nextLong(fault.latencyMinMs, fault.latencyMaxMs + 1)
        if (ms > 0) Thread.sleep(ms)
    }

    fun shouldError(): Boolean = hit(fault.errorRate)

    /** 처리한 뒤 부른다 — 걸리면 drop-hold-ms 동안 응답을 쥔다 */
    fun holdIfDropped() {
        if (hit(fault.dropRate)) Thread.sleep(fault.dropHoldMs)
    }

    private fun hit(rate: Double): Boolean = rate > 0.0 && ThreadLocalRandom.current().nextDouble() < rate
}
