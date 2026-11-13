package com.openminis.app.offload

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B50-spawn-gate] The device-grep contract: the app side of slot
 * serialization was exonerated in code, so the on-device proof is these
 * lines. The batch line must state the whole admission chain (cpu ceiling,
 * LLM stream cap, pacing state) and the child line must carry "after=<ms>"
 * — three children clustering within ~1s means the app dispatched
 * concurrently and the stagger is the relay's. The format is pinned so a
 * device-side grep never breaks between builds.
 */
class SpawnGateLogTest {

    @Test
    fun `batch line states the admission chain`() {
        val line = SubagentExecutor.spawnGateBatchLine(
            batchSize = 3, serial = false,
            cpuCeiling = 6, thermalStatus = 0, llmStreams = 32, pacingEnabled = false,
        )
        assertTrue(line.startsWith("[SpawnGate] "))
        assertTrue("batch=3" in line)
        assertTrue("serial=false" in line)
        assertTrue("cpuCeiling=6" in line)
        assertTrue("thermal=0" in line)
        assertTrue("llmStreams=32" in line)
        assertTrue("pacing=off" in line)
    }

    @Test
    fun `thermal moderate pins the why behind a ceiling of two`() {
        // [B50-spawn-gate] The vc165 device truth: cpuCeiling=2 with three
        // children meant the third waited 180s for a cutoff-freed slot —
        // and the cause was thermal MODERATE, invisible until this field
        // rode the line itself.
        val line = SubagentExecutor.spawnGateBatchLine(
            batchSize = 3, serial = false,
            cpuCeiling = 2, thermalStatus = 2, llmStreams = 32, pacingEnabled = false,
        )
        assertTrue("cpuCeiling=2" in line)
        assertTrue("thermal=2" in line)
    }

    @Test
    fun `pacing enabled flips the flag, not the shape`() {
        val line = SubagentExecutor.spawnGateBatchLine(2, true, 2, 2, 32, true)
        assertTrue("pacing=on" in line)
        assertTrue("serial=true" in line)
    }

    @Test
    fun `child line carries index, role and the after stamp`() {
        val line = SubagentExecutor.spawnGateChildLine(0, 3, "SENIOR_IMPLEMENTER", 137L)
        assertTrue(line.startsWith("[SpawnGate] "))
        assertTrue("child 1/3" in line)
        assertTrue("role=SENIOR_IMPLEMENTER" in line)
        assertTrue("after=137ms" in line)
        // No pool pick → no model token (the old grep contract stays intact).
        assertTrue("model=" !in line)
    }

    @Test
    fun `child line names the pool model when one is pinned`() {
        // [B53-parallel-pool] The device-proof of distribution: the line shows
        // which pool model the member runs on, so a round-robin across hosts
        // is verifiable in the log without trust.
        val line = SubagentExecutor.spawnGateChildLine(1, 3, "SENIOR_IMPLEMENTER", 4L, "glm-fast")
        assertTrue("child 2/3" in line)
        assertTrue("model=glm-fast " in line)
        assertTrue(
            "model must precede the stamp",
            line.indexOf("model=glm-fast") < line.indexOf("after="),
        )
        assertTrue("after=4ms" in line)
    }

    @Test
    fun `zero delay is an honest stamp not a blank`() {
        val line = SubagentExecutor.spawnGateChildLine(2, 3, "EXPLORE", 0L)
        assertTrue("child 3/3" in line)
        assertTrue("after=0ms" in line)
    }
}
