package com.openminis.app.data

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-level-5] Deserialization safety for the five-tier
 * ThinkingLevel. The 7-level scale (XHIGH/ULTRA) is gone — no mainstream
 * backend accepts those wire values and the old XHIGH default ceiling for
 * unknown models leaked "xhigh" onto every OpenAI-compatible provider
 * (HTTP 400 before the first token). Guards the three failure modes:
 *  - a legacy persisted name ("XHIGH"/"ULTRA") must decode onto its honest
 *    tier instead of throwing and wiping the config blob;
 *  - a completely unknown name must clamp to HIGH, never throw;
 *  - encoding writes only the clean five names.
 */
class ThinkingLevelTest {

    @Test
    fun decoded_returnsKnownLevels() {
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.decoded("OFF"))
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.decoded("LOW"))
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.decoded("MEDIUM"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("HIGH"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.decoded("MAX"))
    }

    @Test
    fun decoded_removedScaleNames_aliasOntoTheirHonestTiers() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("XHIGH"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.decoded("ULTRA"))
    }

    @Test
    fun decoded_unknownValueClampsToHigh_neverThrows() {
        // A completely unrecognized future value must clamp to the default
        // every OpenAI-compatible backend accepts, not throw.
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("SUPREME"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded(""))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("garbage-token"))
    }

    @Test
    fun rank_followsDeclarationOrder() {
        assertEquals(0, ThinkingLevel.OFF.rank)
        assertEquals(1, ThinkingLevel.LOW.rank)
        assertEquals(2, ThinkingLevel.MEDIUM.rank)
        assertEquals(3, ThinkingLevel.HIGH.rank)
        assertEquals(4, ThinkingLevel.MAX.rank)
        // Sanity: intersection/clamp via rank works as expected.
        assertEquals(
            ThinkingLevel.HIGH,
            minOf(ThinkingLevel.MAX, ThinkingLevel.HIGH, compareBy { it.rank }),
        )
    }

    /**
     * The lenient serializer is the config-load guard: a blob a previous
     * (7-level) build persisted must decode WITHOUT losing the rest of the
     * overrides — display name, context window, modalities ride along.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun modelOverrides_legacyXHighName_decodesLeniently_notWipe() {
        val wire = """{"maxThinkingLevel":"XHIGH","displayName":"X"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.HIGH, ov.maxThinkingLevel)
        assertEquals("X", ov.displayName) // the blob survived — nothing wiped
    }

    @Test
    fun modelOverrides_legacyUltraName_decodesToMax() {
        val wire = """{"maxThinkingLevel":"ULTRA"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.MAX, ov.maxThinkingLevel)
    }

    @Test
    fun modelOverrides_unknownName_clampsToHigh_notNull() {
        // The lenient serializer PRESERVES the user's "some thinking" intent:
        // an unknown name clamps to HIGH instead of coercing to null (which
        // would silently drop the override).
        val wire = """{"maxThinkingLevel":"SUPREME","displayName":"X"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.HIGH, ov.maxThinkingLevel)
        assertEquals("X", ov.displayName)
    }

    @Test
    fun modelOverrides_knownMaxThinkingLevel_roundTrips() {
        val wire = """{"maxThinkingLevel":"MAX"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.MAX, ov.maxThinkingLevel)
    }

    @Test
    fun modelOverrides_encodeWritesOnlyCleanFiveNames() {
        val encoded = json.encodeToString(
            ModelOverrides.serializer(),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH),
        )
        assertTrue(encoded.contains("\"maxThinkingLevel\":\"HIGH\""))
        assertTrue(!encoded.contains("XHIGH"))
        assertTrue(!encoded.contains("ULTRA"))
    }

    @Test
    fun modelGroup_legacyThinkingLevel_decodesLeniently() {
        val wire = """{"id":"g1","name":"G","memberEntryIds":[],"defaultThinkingLevel":"XHIGH"}"""
        val group = json.decodeFromString(ModelGroup.serializer(), wire)
        assertEquals(ThinkingLevel.HIGH, group.defaultThinkingLevel)
        assertEquals("G", group.name) // other fields intact — config NOT wiped
    }
}
