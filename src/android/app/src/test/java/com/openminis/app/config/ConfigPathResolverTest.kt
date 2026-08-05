package com.openminis.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the path-lookup rule that decides whether `minis-config get/set <path>`
 * can reach a registered setting at all.
 *
 * These exist because a whole collection was unreachable in a shipped build:
 * `agent.autoRoute`, `agent.autoRouteModel`, `agent.defaultGraph`,
 * `agent.defaultModelEntry` and every `agent.keys.<role>` answered
 * `unknown_path` even though `topic-help agent` listed them. The old rule
 * required a path to split into exactly three segments, which excluded both
 * two-segment children and collections whose own base path contains a dot.
 *
 * The resolver is a free function precisely so this can run as a plain JVM
 * test — no Context, no registry boot, no Robolectric.
 */
class ConfigPathResolverTest {

    private class FakeField(override val path: String) : ConfigField {
        override val displayName = path
        override val description = ""
        override val valueSchema = ConfigSchema.Bool
        override val access = ConfigAccess.READWRITE
        override val risk = ConfigRisk.NORMAL
        override val revertable = true
        override fun read(): ConfigValue = ConfigValue.Bool(false)
        override fun write(value: ConfigValue) {}
    }

    /** Emits `<basePath>.<id>` plus, optionally, `<basePath>.<id>.<leaf>` fields. */
    private class FakeCollection(
        override val basePath: String,
        private val validIds: Set<String>,
        private val leaves: List<String> = emptyList(),
    ) : ConfigCollection {
        override val displayName = basePath
        override val description = ""
        override fun childIds(): List<String> = validIds.toList()
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in validIds) return emptyList()
            if (leaves.isEmpty()) return listOf(FakeField("$basePath.$forId"))
            return leaves.map { FakeField("$basePath.$forId.$it") }
        }
        override fun add(payload: ConfigValue): String = throw ConfigError.InvalidValue("no")
        override fun remove(id: String) = throw ConfigError.InvalidValue("no")
    }

    // Shapes mirroring the real registry: `agent` (leafless children),
    // `agent.keys` (dotted base), `models` (uuid ids + dotted leaves).
    private val agent = FakeCollection(
        "agent",
        setOf("autoRoute", "defaultGraph", "autoRouteModel", "defaultModelEntry"),
    )
    private val agentKeys = FakeCollection(
        "agent.keys",
        setOf("planner", "coder", "reviewer"),
    )
    private val models = FakeCollection(
        "models",
        setOf("inst-1/gpt-4o"),
        leaves = listOf("displayName", "modality.video"),
    )
    private val collections = listOf(agent, agentKeys, models).associateBy { it.basePath }

    private fun resolve(path: String): ConfigField? =
        resolveCollectionField(path) { collections[it] }

    @Test
    fun `a leafless child resolves — the regression that hid agent autoRoute`() {
        // Two segments only: the child id IS the last segment. The previous
        // `segments.size != 3` guard rejected this outright.
        for (id in listOf("autoRoute", "defaultGraph", "autoRouteModel", "defaultModelEntry")) {
            assertEquals("agent.$id", resolve("agent.$id")?.path)
        }
    }

    @Test
    fun `a collection whose base path contains a dot resolves`() {
        // `agent.keys.planner` used to split as base=agent, id=keys, leaf=planner
        // and miss, because the collection is registered at `agent.keys`.
        assertEquals("agent.keys.planner", resolve("agent.keys.planner")?.path)
        assertEquals("agent.keys.coder", resolve("agent.keys.coder")?.path)
    }

    @Test
    fun `three-segment children still resolve`() {
        assertEquals(
            "models.inst-1/gpt-4o.displayName",
            resolve("models.inst-1/gpt-4o.displayName")?.path,
        )
    }

    @Test
    fun `a leaf may itself contain dots`() {
        assertEquals(
            "models.inst-1/gpt-4o.modality.video",
            resolve("models.inst-1/gpt-4o.modality.video")?.path,
        )
    }

    @Test
    fun `an unknown child id still fails rather than matching a sibling`() {
        // The permissive scan must not turn `unknown_path` into a wrong hit:
        // matching is on the collection's own emitted path, not on prefixes.
        assertNull(resolve("agent.nosuchsetting"))
        assertNull(resolve("agent.keys.nosuchrole"))
        assertNull(resolve("models.nosuchentry.displayName"))
    }

    @Test
    fun `unknown bases and pathless ids fail`() {
        assertNull(resolve("bogus.autoRoute"))
        assertNull(resolve("bogus.some.deep.path"))
        assertNull(resolve("agent"))
        assertNull(resolve(""))
    }

    @Test
    fun `a longer base wins over a shorter one that also matches`() {
        // Both `agent` and `agent.keys` are registered. `agent.keys.planner`
        // must land on the agent.keys field, and `agent.autoRoute` on agent's —
        // neither may shadow the other.
        assertEquals("agent.keys.planner", resolve("agent.keys.planner")?.path)
        assertEquals("agent.autoRoute", resolve("agent.autoRoute")?.path)
    }
}
