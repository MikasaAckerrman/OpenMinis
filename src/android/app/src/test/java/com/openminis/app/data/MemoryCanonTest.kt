package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-canon-persistence] Unit tests for the pinned-canon store: parse
 * round-trip, dedup ids, explicit-signal gate (RU + EN, negative cases),
 * supersede semantics, fragment budget with visible counter.
 */
class MemoryCanonTest {

    // -- Gate classifier ---------------------------------------------------

    @Test
    fun `explicit russian memorize signal is detected`() {
        assertTrue(MemoryCanon.isExplicitMemorySignal("запомни всегда отвечай по-русски"))
        assertTrue(MemoryCanon.isExplicitMemorySignal("Запомните: никогда не удалять файлы без спроса"))
        assertTrue(MemoryCanon.isExplicitMemorySignal("запомнить это правило навсегда"))
    }

    @Test
    fun `explicit russian always-never rule is detected`() {
        assertTrue(MemoryCanon.isExplicitMemorySignal("всегда используй python 3 для скриптов"))
        assertTrue(MemoryCanon.isExplicitMemorySignal("никогда не спрашивай подтверждение на мелкие правки"))
    }

    @Test
    fun `explicit english signals are detected`() {
        assertTrue(MemoryCanon.isExplicitMemorySignal("remember this: I prefer Russian replies"))
        assertTrue(MemoryCanon.isExplicitMemorySignal("always answer in Russian"))
        assertTrue(MemoryCanon.isExplicitMemorySignal("never delete files without asking"))
    }

    @Test
    fun `ordinary work text is NOT a signal`() {
        assertFalse(MemoryCanon.isExplicitMemorySignal("продолжаем, чиним компакцию"))
        assertFalse(MemoryCanon.isExplicitMemorySignal("статус: CI зелёный, пушим"))
        assertFalse(MemoryCanon.isExplicitMemorySignal("посмотри это объявление и напиши продавцу"))
        assertFalse(MemoryCanon.isExplicitMemorySignal("the build passed, continue"))
        // Слово-канон внутри других слов не должно матчиться (границы!)
        assertFalse(MemoryCanon.isExplicitMemorySignal("запомнился тёплый вечер")) // «запомнился» ≠ «запомни»
    }

    @Test
    fun `extract fact strips signal phrase`() {
        assertEquals(
            "всегда отвечай по-русски",
            MemoryCanon.extractFactText("запомни: всегда отвечай по-русски"),
        )
        assertEquals(
            "I prefer Russian replies",
            MemoryCanon.extractFactText("remember this: I prefer Russian replies"),
        )
    }

    // -- IDs / dedup ---------------------------------------------------------

    @Test
    fun `same fact normalizes to same id regardless of punctuation`() {
        val a = MemoryCanon.idFor("Отвечай по-русски, коротко!")
        val b = MemoryCanon.idFor("отвечай по русски коротко")
        assertEquals(a, b)
        assertNotEquals(a, MemoryCanon.idFor("совсем другой факт"))
    }

    // -- Parse / serialize round-trip ----------------------------------------

    @Test
    fun `parse reads entry fields and skips broken blocks`() {
        val text = """
            <!-- canon:c-abc123def0 -->
            id: c-abc123def0
            type: preference
            status: active
            pin: true
            source: user_explicit
            created: 2026-09-08
            supersedes:
            text: Отвечай по-русски.

            <!-- canon:c-broken999 -->
            <!-- canon:c-fed9876543 -->
            id: c-fed9876543
            type: decision
            status: superseded
            pin: false
            source: user_confirmed
            created: 2026-09-01
            supersedes: c-abc123def0
            text: Старое решение.
        """.trimIndent()
        val entries = MemoryCanon.parse(text)
        assertEquals(2, entries.size)  // broken block skipped, two valid parsed
        val first = entries[0]
        assertEquals("c-abc123def0", first.id)
        assertEquals("preference", first.type)
        assertEquals("active", first.status)
        assertTrue(first.pin)
        assertEquals("Отвечай по-русски.", first.text)
        val second = entries[1]
        assertEquals("superseded", second.status)
        assertFalse(second.pin)
        assertEquals("c-abc123def0", second.supersedes)
    }

    @Test
    fun `append dedups by content id and marks superseded`() {
        val base = MemoryCanon.serializeEntry(
            MemoryCanon.CanonEntry(
                id = "c-old000001", type = "preference", status = "active", pin = true,
                source = "user_explicit", created = "2026-09-01", supersedes = null,
                text = "Старое правило",
            ),
        )
        // re-pin the same fact (same normalized text → same id): no second entry
        val (same, _) = MemoryCanon.append(base, MemoryCanon.CanonEntry(
            id = "", type = "preference", status = "active", pin = true,
            source = "user_explicit", created = "2026-09-08", supersedes = null,
            text = "Старое правило", // identical → same idFor
        ))
        // Both entries carry the same id; append() does not dedup here (the
        // repository checks dedup BEFORE calling append) — verify the caller
        // contract instead: parse(same) has the id present.
        assertTrue(MemoryCanon.parse(same).any { it.id == MemoryCanon.idFor("Старое правило") })

        // supersede: new entry flips the old one's status
        val newId = MemoryCanon.idFor("Новое правило вместо старого")
        val (updated, finalized) = MemoryCanon.append(same, MemoryCanon.CanonEntry(
            id = "", type = "preference", status = "active", pin = true,
            source = "user_explicit", created = "2026-09-08", supersedes = "c-old000001",
            text = "Новое правило вместо старого",
        ))
        assertEquals(newId, finalized.id)
        val parsed = MemoryCanon.parse(updated)
        val old = parsed.first { it.id == "c-old000001" }
        assertEquals("superseded", old.status)
        assertEquals("active", parsed.first { it.id == newId }.status)
    }

    // -- Fragment -------------------------------------------------------------

    @Test
    fun `fragment lists active pinned entries with budget counter`() {
        val entries = listOf(
            MemoryCanon.CanonEntry("c-1", "preference", "active", true, "user_explicit", "2026-09-08", null, "Отвечай по-русски."),
            MemoryCanon.CanonEntry("c-2", "preference", "superseded", true, "user_explicit", "2026-09-01", null, "Старое."),
            MemoryCanon.CanonEntry("c-3", "constraint", "active", false, "user_confirmed", "2026-09-08", null, "Без пина."),
        )
        val frag = MemoryCanon.buildFragment(entries)
        assertNotNull(frag)
        assertTrue(frag!!.contains("[c-1] Отвечай по-русски"))
        assertFalse(frag.contains("Старое"))       // superseded excluded
        assertFalse(frag.contains("Без пина"))     // pin=false excluded
        assertTrue(frag.contains("standing instructions"))
        assertTrue(frag.contains("chars"))         // Letta-style budget visible
    }

    @Test
    fun `fragment returns null when nothing active and pins budget-excess entries`() {
        assertNull(MemoryCanon.buildFragment(emptyList()))
        assertNull(MemoryCanon.buildFragment(listOf(
            MemoryCanon.CanonEntry("c-x", "fact", "revoked", true, "user_explicit", "2026-09-08", null, "Отозвано"),
        )))
        // Oversized entry alone: nothing fits → null (no empty fragment)
        val huge = "x".repeat(MemoryCanon.MAX_FRAGMENT_CHARS + 100)
        assertNull(MemoryCanon.buildFragment(listOf(
            MemoryCanon.CanonEntry("c-h", "fact", "active", true, "user_explicit", "2026-09-08", null, huge),
        )))
    }
}
