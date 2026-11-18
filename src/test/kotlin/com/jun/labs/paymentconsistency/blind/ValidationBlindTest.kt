package com.jun.labs.paymentconsistency.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.stream.Stream

/**
 * 입력 검증과 판정 순서 — spec §2 "입력 검증 → 400" · ADR-000 §6 판정 순서 · §6.1 표 전체.
 * 위반이면 400 INVALID_REQUEST, PG를 부르지 않고(PG 주문번호 조회 404 · 취소 내역 불변) 행이 생기지 않는다.
 */
class ValidationBlindTest : BlindAppTestBase() {

    override fun mockPg(): MockPgInstance = PG.get()

    /** orderId 자리에 FRESH를 두면 케이스마다 새 주문번호(유효)를 쓴다. */
    data class ApproveCase(
        val label: String,
        val orderId: Any?,
        val amount: Any?,
        val cardNumber: Any?,
        val userId: String?,
    ) {
        override fun toString() = label
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidApproveCases")
    fun `승인 입력 검증 위반 - 400 INVALID_REQUEST, 행 없음, PG 미호출`(case: ApproveCase) {
        val orderId = if (case.orderId === FRESH) newOrderId() else case.orderId
        val before = totalRowCount()

        val res = approve(orderId = orderId, amount = case.amount, cardNumber = case.cardNumber, userId = case.userId)

        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("INVALID_REQUEST")
        assertThat(totalRowCount()).isEqualTo(before)
        if (orderId is String && orderId.isNotBlank()) {
            assertThat(rowCount(orderId)).isEqualTo(0L)
            assertThat(pgByOrder(orderId).status).describedAs("PG가 호출되지 않아야 한다").isEqualTo(404)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCancelBodies")
    fun `취소 입력 검증 위반 - 400 INVALID_REQUEST, 상태 불변, PG 미호출`(label: String, body: String) {
        val approved = approveOk(amount = 10_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!

        val res = cancel(id, body)

        assertThat(res.status).describedAs("$label → ${res.body}").isEqualTo(400)
        assertThat(res.code).isEqualTo("INVALID_REQUEST")
        assertAppUnchanged(id, "APPROVED", 0L, 0)
        assertPgUnchanged(key, "DONE", 10_000L, 0)
    }

    @ParameterizedTest(name = "경로 id = {0}")
    @ValueSource(strings = ["abc", "0", "-5", "1.5"])
    fun `경로 id가 양의 정수가 아니면 취소 · 조회 모두 400 INVALID_REQUEST`(rawId: String) {
        val c = cancel(rawId, json("cancelAmount" to 1_000L))
        assertThat(c.status).describedAs(c.body).isEqualTo(400)
        assertThat(c.code).isEqualTo("INVALID_REQUEST")

        val g = getPayment(rawId)
        assertThat(g.status).describedAs(g.body).isEqualTo(400)
        assertThat(g.code).isEqualTo("INVALID_REQUEST")
    }

    // ---- 판정 순서: 입력 검증(400) → 존재(404) → 상태(NOT_CANCELABLE) → 금액(EXCEEDED) → PG ----

    @Test
    fun `판정 순서 - 없는 결제에 잘못된 취소액은 404가 아니라 400`() {
        val res = cancel(Long.MAX_VALUE, json("cancelAmount" to 0L))
        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `판정 순서 - 없는 결제에 201자 reason은 404가 아니라 400`() {
        val res = cancel(Long.MAX_VALUE, json("reason" to "r".repeat(201)))
        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `판정 순서 - FAILED 결제에 잘못된 취소액은 409가 아니라 400`() {
        val failed = approveDeclined()
        val id = failed["paymentId"].asLong()
        val res = cancel(id, json("cancelAmount" to -1L))
        assertThat(res.status).describedAs(res.body).isEqualTo(400)
        assertThat(res.code).isEqualTo("INVALID_REQUEST")
        assertAppUnchanged(id, "FAILED", 0L, 0)
    }

    @Test
    fun `판정 순서 - FAILED 결제에 금액 초과 취소는 CANCEL_AMOUNT_EXCEEDED가 아니라 PAYMENT_NOT_CANCELABLE`() {
        val failed = approveDeclined(amount = 5_000L)
        val id = failed["paymentId"].asLong()
        val res = cancel(id, json("cancelAmount" to 5_001L))
        assertThat(res.status).describedAs(res.body).isEqualTo(409)
        assertThat(res.code).isEqualTo("PAYMENT_NOT_CANCELABLE")
        assertAppUnchanged(id, "FAILED", 0L, 0)
    }

    @Test
    fun `판정 순서 - CANCELED 결제에 금액 초과 취소는 PAYMENT_NOT_CANCELABLE`() {
        val approved = approveOk(amount = 2_000L)
        val id = approved["paymentId"].asLong()
        val key = approved.textOrNull("paymentKey")!!
        assertThat(cancel(id, "{}").status).isEqualTo(200)

        val res = cancel(id, json("cancelAmount" to 99_999L))
        assertThat(res.status).describedAs(res.body).isEqualTo(409)
        assertThat(res.code).isEqualTo("PAYMENT_NOT_CANCELABLE")
        assertAppUnchanged(id, "CANCELED", 2_000L, 1)
        assertPgUnchanged(key, "CANCELED", 0L, 1)
    }

    @Test
    fun `판정 순서 - 잘못된 경로 id와 잘못된 취소액이 함께면 400`() {
        val res = cancel("abc", json("cancelAmount" to 0L))
        assertThat(res.status).isEqualTo(400)
        assertThat(res.code).isEqualTo("INVALID_REQUEST")
    }

    companion object {
        private val PG = MockPgHolder()

        /** 케이스마다 새 주문번호를 쓰라는 표시. */
        private val FRESH = Any()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            BlindPostgres.register(registry)
            registry.add("payment.pg.base-url") { PG.get().baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun stopPg() = PG.close()

        @JvmStatic
        fun invalidApproveCases(): Stream<ApproveCase> = Stream.of(
            // X-User-Id
            ApproveCase("X-User-Id 없음", FRESH, 1_000L, APPROVE_CARD, null),
            ApproveCase("X-User-Id 숫자 아님", FRESH, 1_000L, APPROVE_CARD, "abc"),
            ApproveCase("X-User-Id 0", FRESH, 1_000L, APPROVE_CARD, "0"),
            ApproveCase("X-User-Id 음수", FRESH, 1_000L, APPROVE_CARD, "-1"),
            ApproveCase("X-User-Id 소수", FRESH, 1_000L, APPROVE_CARD, "1.5"),
            // orderId
            ApproveCase("orderId 생략", Omit, 1_000L, APPROVE_CARD, "1"),
            ApproveCase("orderId null", null, 1_000L, APPROVE_CARD, "1"),
            ApproveCase("orderId 빈 문자열", "", 1_000L, APPROVE_CARD, "1"),
            ApproveCase("orderId 공백", "   ", 1_000L, APPROVE_CARD, "1"),
            ApproveCase("orderId 65자", "x".repeat(65), 1_000L, APPROVE_CARD, "1"),
            // amount
            ApproveCase("amount 생략", FRESH, Omit, APPROVE_CARD, "1"),
            ApproveCase("amount null", FRESH, null, APPROVE_CARD, "1"),
            ApproveCase("amount 0", FRESH, 0L, APPROVE_CARD, "1"),
            ApproveCase("amount 음수", FRESH, -1L, APPROVE_CARD, "1"),
            ApproveCase("amount 숫자 아님", FRESH, "abc", APPROVE_CARD, "1"),
            ApproveCase("amount 소수", FRESH, 1.5, APPROVE_CARD, "1"),
            // cardNumber
            ApproveCase("cardNumber 생략", FRESH, 1_000L, Omit, "1"),
            ApproveCase("cardNumber null", FRESH, 1_000L, null, "1"),
            ApproveCase("cardNumber 빈 문자열", FRESH, 1_000L, "", "1"),
            ApproveCase("cardNumber 공백", FRESH, 1_000L, "   ", "1"),
            ApproveCase("cardNumber 33자", FRESH, 1_000L, "4".repeat(33), "1"),
        )

        @JvmStatic
        fun invalidCancelBodies(): Stream<Arguments> = Stream.of(
            Arguments.of("cancelAmount 0", json("cancelAmount" to 0L)),
            Arguments.of("cancelAmount 음수", json("cancelAmount" to -1L)),
            Arguments.of("cancelAmount 숫자 아님", json("cancelAmount" to "abc")),
            Arguments.of("cancelAmount 소수", json("cancelAmount" to 1.5)),
            Arguments.of("reason 201자", json("cancelAmount" to 1_000L, "reason" to "r".repeat(201))),
        )
    }
}
