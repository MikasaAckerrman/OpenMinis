package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-send-gate-deadlock] Контракты гейта отправки. Каждый тест соответствует
 * одному реально найденному пути в мёртвое состояние.
 */
class SendGatePolicyTest {

    @Test
    fun `полная история — отправка разрешена молча`() {
        val s = SendGatePolicy.stateOf(fullHistoryReady = true, degraded = false)
        assertEquals(SendGatePolicy.HistoryState.READY, s)
        assertEquals(SendGatePolicy.Decision.ALLOW,
            SendGatePolicy.decide(s, warningAlreadyShown = false))
    }

    @Test
    fun `история грузится — отправка заблокирована (это временно и честно)`() {
        val s = SendGatePolicy.stateOf(fullHistoryReady = false, degraded = false)
        assertEquals(SendGatePolicy.HistoryState.LOADING, s)
        assertEquals(SendGatePolicy.Decision.BLOCK_LOADING,
            SendGatePolicy.decide(s, warningAlreadyShown = false))
        assertFalse(SendGatePolicy.maySend(s))
    }

    @Test
    fun `загрузка провалилась — отправка РАЗРЕШЕНА с предупреждением, сессия не мертва`() {
        // Это и есть исходный дефект: раньше здесь была вечная блокировка.
        val s = SendGatePolicy.stateOf(fullHistoryReady = false, degraded = true)
        assertEquals(SendGatePolicy.HistoryState.DEGRADED, s)
        assertEquals(SendGatePolicy.Decision.ALLOW_WITH_WARNING,
            SendGatePolicy.decide(s, warningAlreadyShown = false))
        assertTrue(SendGatePolicy.maySend(s))
    }

    @Test
    fun `degraded — перезапись истории ЗАПРЕЩЕНА (иначе новый баг: потеря сообщений)`() {
        // compactAll считает якорь по agentHistory в памяти и удаляет строки из
        // БД. При неполной истории это удалило бы то, чего в памяти нет.
        val s = SendGatePolicy.HistoryState.DEGRADED
        assertEquals(SendGatePolicy.Decision.BLOCK_UNSAFE_REWRITE,
            SendGatePolicy.decide(s, warningAlreadyShown = false,
                operation = SendGatePolicy.Operation.REWRITE_HISTORY))
        assertEquals(SendGatePolicy.Decision.BLOCK_UNSAFE_REWRITE,
            SendGatePolicy.decide(s, warningAlreadyShown = true,
                operation = SendGatePolicy.Operation.REWRITE_HISTORY))
        assertFalse(SendGatePolicy.mayRewriteHistory(s))
    }

    @Test
    fun `перезапись истории разрешена ТОЛЬКО при полной истории`() {
        assertTrue(SendGatePolicy.mayRewriteHistory(SendGatePolicy.HistoryState.READY))
        assertFalse(SendGatePolicy.mayRewriteHistory(SendGatePolicy.HistoryState.LOADING))
        assertFalse(SendGatePolicy.mayRewriteHistory(SendGatePolicy.HistoryState.DEGRADED))
        assertEquals(SendGatePolicy.Decision.ALLOW,
            SendGatePolicy.decide(SendGatePolicy.HistoryState.READY, false,
                SendGatePolicy.Operation.REWRITE_HISTORY))
    }

    @Test
    fun `смягчение НЕ протекло в перезапись — SEND и REWRITE решаются по-разному`() {
        val s = SendGatePolicy.HistoryState.DEGRADED
        val send = SendGatePolicy.decide(s, false, SendGatePolicy.Operation.SEND)
        val rewrite = SendGatePolicy.decide(s, false, SendGatePolicy.Operation.REWRITE_HISTORY)
        assertTrue("отправка должна пройти", send != SendGatePolicy.Decision.BLOCK_LOADING &&
            send != SendGatePolicy.Decision.BLOCK_UNSAFE_REWRITE)
        assertEquals(SendGatePolicy.Decision.BLOCK_UNSAFE_REWRITE, rewrite)
    }

    @Test
    fun `предупреждение о неполном контексте показывается один раз`() {
        val s = SendGatePolicy.HistoryState.DEGRADED
        assertEquals(SendGatePolicy.Decision.ALLOW_WITH_WARNING,
            SendGatePolicy.decide(s, warningAlreadyShown = false))
        assertEquals(SendGatePolicy.Decision.ALLOW,
            SendGatePolicy.decide(s, warningAlreadyShown = true))
    }

    @Test
    fun `degraded имеет приоритет над ready`() {
        // Частичные данные доехали, но загрузка упала — «готовности» нет.
        assertEquals(SendGatePolicy.HistoryState.DEGRADED,
            SendGatePolicy.stateOf(fullHistoryReady = true, degraded = true))
    }

    @Test
    fun `ни одно состояние кроме LOADING не блокирует отправку навсегда`() {
        // Инвариант против возврата дефекта: единственная блокировка — LOADING.
        for (s in SendGatePolicy.HistoryState.values()) {
            val blocked = SendGatePolicy.decide(s, warningAlreadyShown = true) ==
                SendGatePolicy.Decision.BLOCK_LOADING
            assertEquals("состояние $s: блокировка допустима только при LOADING",
                s == SendGatePolicy.HistoryState.LOADING, blocked)
        }
    }

    @Test
    fun `отправка не блокируется НИ В ОДНОМ состоянии кроме LOADING`() {
        // Полный обход матрицы: для SEND единственный отказ — честная загрузка.
        for (s in SendGatePolicy.HistoryState.values()) {
            for (shown in listOf(false, true)) {
                val d = SendGatePolicy.decide(s, shown, SendGatePolicy.Operation.SEND)
                val refused = d == SendGatePolicy.Decision.BLOCK_LOADING ||
                    d == SendGatePolicy.Decision.BLOCK_UNSAFE_REWRITE
                assertEquals("SEND в $s (warned=$shown)",
                    s == SendGatePolicy.HistoryState.LOADING, refused)
            }
        }
    }
}
