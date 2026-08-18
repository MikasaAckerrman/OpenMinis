package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionLaunchPolicyTest {

    @Test
    fun `only an explicit user request may rewrite compacted context`() {
        assertTrue(CompactionLaunchPolicy.mayRewrite(CompactionLaunchPolicy.Origin.EXPLICIT_USER))
        assertFalse(CompactionLaunchPolicy.mayRewrite(CompactionLaunchPolicy.Origin.PRESSURE_MAINTENANCE))
        assertFalse(CompactionLaunchPolicy.mayRewrite(CompactionLaunchPolicy.Origin.OVERSIZE_RECOVERY))
        assertFalse(CompactionLaunchPolicy.mayRewrite(CompactionLaunchPolicy.Origin.MODEL_SWITCH))
        assertFalse(CompactionLaunchPolicy.mayRewrite(CompactionLaunchPolicy.Origin.AUTOMATIC_OFFLOAD))
    }
}
