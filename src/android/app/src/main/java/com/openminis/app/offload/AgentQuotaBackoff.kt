package com.openminis.app.offload

/** Pure bounded policy for quota-driven output-token retries. */
object AgentQuotaBackoff {
    const val MIN_CAP = 4_096

    /** Next lower cap, or null when the minimum has already been tried. */
    fun nextCap(currentCap: Int): Int? {
        if (currentCap <= MIN_CAP) return null
        return maxOf(MIN_CAP, currentCap / 2)
    }
}
