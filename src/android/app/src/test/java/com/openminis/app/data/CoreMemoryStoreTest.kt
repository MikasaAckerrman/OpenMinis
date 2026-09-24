package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-letta-core-memory] Contract tests for the 4th history layer. The store
 * is pointed at a real temp dir (the same JSON file layout as production),
 * so persistence, atomicity of the write path, caps, ordering and the
 * injector semantics are all pinned against the actual code paths — no
 * fakes, the real [CoreMemoryStore].
 */
class CoreMemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun freshStore() {
        CoreMemoryStore.primeDirForTest(File(tmp.newFolder("memory${System.nanoTime()}").path))
    }

    private fun upsertOk(id: String, label: String, value: String, pinned: Boolean = false) {
        val error = CoreMemoryStore.upsert(id, label, value, pinned, "00:00 model")
        assertNull("upsert($id) must succeed, got: $error", error)
    }

    @Test
    fun `upsert then blocks roundtrips through the file`() {
        freshStore()
        upsertOk("b1", "User prefs", "prefers Kotlin, hates boilerplate")
        upsertOk("b2", "Project", "OpenMinis repo, feat/message-rewrite-visual")
        val blocks = CoreMemoryStore.blocks()
        assertEquals(2, blocks.size)
        assertEquals("b1", blocks[0].id)
        assertEquals("prefers Kotlin, hates boilerplate", blocks[0].value)
        assertEquals("b2", blocks[1].label)
    }

    @Test
    fun `update overwrites the same id in place`() {
        freshStore()
        upsertOk("b1", "State", "draft")
        upsertOk("b1", "State", "final")
        val blocks = CoreMemoryStore.blocks()
        assertEquals(1, blocks.size)
        assertEquals("final", blocks[0].value)
        assertEquals("State", blocks[0].label)
    }

    @Test
    fun `value cap is rejected with an explicit error`() {
        freshStore()
        val tooLong = "x".repeat(CoreMemoryStore.MAX_VALUE_CHARS + 1)
        val error = CoreMemoryStore.upsert("b1", "L", tooLong, false, "00:00 model")
        assertNotNull("oversized value must be rejected", error)
        assertTrue(error!!.contains("too long"))
        assertTrue("nothing persisted on rejection", CoreMemoryStore.blocks().isEmpty())
    }

    @Test
    fun `label cap is rejected`() {
        freshStore()
        val error = CoreMemoryStore.upsert("b1", "L".repeat(CoreMemoryStore.MAX_LABEL_CHARS + 1), "v", false, "00:00 model")
        assertNotNull(error)
    }

    @Test
    fun `block count cap rejects the 17th block`() {
        freshStore()
        for (i in 1..CoreMemoryStore.MAX_BLOCKS) upsertOk("b$i", "L$i", "v$i")
        val error = CoreMemoryStore.upsert("bExtra", "L", "v", false, "00:00 model")
        assertNotNull("17th block must be rejected", error)
        assertEquals(CoreMemoryStore.MAX_BLOCKS, CoreMemoryStore.blocks().size)
    }

    @Test
    fun `delete removes and unknown id errors`() {
        freshStore()
        upsertOk("b1", "A", "a")
        assertNull(CoreMemoryStore.delete("b1"))
        assertTrue(CoreMemoryStore.blocks().isEmpty())
        assertNotNull("unknown id must error", CoreMemoryStore.delete("b1"))
    }

    @Test
    fun `injectHeaderText puts pinned blocks first`() {
        freshStore()
        upsertOk("b1", "Plain", "plain value")
        upsertOk("b2", "Pinned", "pinned value", pinned = true)
        upsertOk("b3", "Plain2", "plain value 2")
        val header = CoreMemoryStore.injectHeaderText()!!
        val pinnedIdx = header.indexOf("[b2]")
        val plainIdx = header.indexOf("[b1]")
        assertTrue("pinned block must lead the header", pinnedIdx < plainIdx)
        assertTrue(header.startsWith("== CORE MEMORY =="))
    }

    @Test
    fun `injectHeaderText is null for an empty store`() {
        freshStore()
        assertNull("empty store → no header, layer skipped, zero cost", CoreMemoryStore.injectHeaderText())
    }

    @Test
    fun `injectHeaderText budget cut carries the visible marker`() {
        freshStore()
        // Two pinned blocks whose combined size exceeds the budget.
        val half = "y".repeat(CoreMemoryStore.INJECTION_BUDGET_CHARS / 2)
        upsertOk("b1", "Big1", half, pinned = true)
        upsertOk("b2", "Big2", half, pinned = true)
        val header = CoreMemoryStore.injectHeaderText()!!
        assertTrue(header.contains("budget reached"))
        assertTrue("header stays bounded", header.length <= CoreMemoryStore.INJECTION_BUDGET_CHARS + 200)
    }

    @Test
    fun `injector prepends to the first message and keeps the rest`() {
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "hi", contentParts = listOf(AgentContentPart.Text("hi"))),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "hello"),
        )
        val out = CoreMemoryInjector.inject(history, "== CORE MEMORY ==\n- [b1] X: y\n")
        assertEquals(2, out.size)
        assertEquals(LLMMessage.Role.USER, out[0].role)
        assertEquals(2, out[0].contentParts.size)
        assertTrue(out[0].contentParts[0] is AgentContentPart.Text)
        assertTrue((out[0].contentParts[0] as AgentContentPart.Text).text.startsWith("== CORE MEMORY =="))
        assertEquals("hi", (out[0].contentParts[1] as AgentContentPart.Text).text)
        // The tail is untouched (same instances).
        assertEquals(history[1], out[1])
        // The base history is not mutated (wire-only injection).
        assertEquals(1, history[0].contentParts.size)
    }

    @Test
    fun `injector on empty history is a no-op`() {
        val out = CoreMemoryInjector.inject(emptyList(), "header")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `injector on a parts-less message creates the parts list`() {
        val history = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi"))
        val out = CoreMemoryInjector.inject(history, "HEADER\n")
        assertEquals(1, out[0].contentParts.size)
        assertEquals("HEADER\nhi", out[0].content)
    }
}
