package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolTaxonomyTest {

    @Test
    fun `plan_submit is META - the plan-mode exit must run in every mode`() {
        assertEquals(MutationKind.META, ToolTaxonomy.kindOf("plan_submit"))
    }

    @Test
    fun `new tool surface names classify`() {
        assertEquals(MutationKind.READ, ToolTaxonomy.kindOf("grep"))
        assertEquals(MutationKind.READ, ToolTaxonomy.kindOf("glob"))
        assertEquals(MutationKind.READ, ToolTaxonomy.kindOf("memory_blocks_view"))
        assertEquals(MutationKind.READ, ToolTaxonomy.kindOf("list_agents"))
        assertEquals(MutationKind.WRITE, ToolTaxonomy.kindOf("memory_blocks_edit"))
    }

    @Test
    fun `delegation classifies conservatively as execute`() {
        // Children of a PLAN session must not be spawned to write around
        // the mode — spawn/graph tools pay the per-call gate.
        assertEquals(MutationKind.EXECUTE, ToolTaxonomy.kindOf("spawn_subagent"))
        assertEquals(MutationKind.EXECUTE, ToolTaxonomy.kindOf("run_graph"))
        assertEquals(MutationKind.EXECUTE, ToolTaxonomy.kindOf("root_shell"))
        assertEquals(MutationKind.EXECUTE, ToolTaxonomy.kindOf("session_gc"))
    }
}
