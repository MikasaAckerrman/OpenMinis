package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-wake-bridge] The wake prompt a background subagent injects
 * into the parent's queue — ZCode semantics: the parent agent continues
 * autonomously with the worker's result. Contract: the model must see
 * (a) WHO finished (role), (b) the report body (capped), (c) an explicit
 * instruction to continue — phrased as a user-channel system continuation.
 */
class SubagentWakeTest {

    @Test
    fun `wake text names the role, the report and the continuation instruction`() {
        val t = SubagentWake.buildWakeText("CODE_REVIEWER", "Тело отчёта: всё чисто.")
        assertTrue(t.startsWith("[Субагент code_reviewer] фоновый прогон завершён."))
        assertTrue(t.contains("Отчёт:\nТело отчёта: всё чисто."))
        assertTrue(t.trim().endsWith("Продолжай работу с учётом этого результата."))
    }

    @Test
    fun `oversize report is capped with a truncation marker`() {
        val big = "x".repeat(SubagentWake.REPORT_CAP + 500)
        val t = SubagentWake.buildWakeText("EXPLORE", big)
        assertTrue(t.contains("…(отчёт усечён"))
        // body = cap + marker line, never the full raw report
        assertFalse(t.contains("x".repeat(SubagentWake.REPORT_CAP + 100)))
    }

    @Test
    fun `short report passes verbatim without marker`() {
        val t = SubagentWake.buildWakeText("ORCHESTRATOR", "короткий отчёт")
        assertFalse(t.contains("усечён"))
        assertTrue(t.contains("короткий отчёт"))
    }

    @Test
    fun `prompt ids are unique wake-prefixed`() {
        val a = SubagentWake.promptId()
        val b = SubagentWake.promptId()
        assertTrue(a.startsWith("wake_"))
        assertTrue(b.startsWith("wake_"))
        assertTrue(a != b)
    }
}
