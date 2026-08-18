package com.openminis.app.ui.chat

/**
 * [T-android-vm-cache-unbounded] Pure model of ChatViewModelStore's LRU
 * eviction, extracted so the policy can be proven without Android's
 * ViewModelStore / a running app.
 *
 * The bug it guards: the real cache only dropped a session's ViewModel when the
 * session was DELETED, so every chat the user opened stayed resident with its
 * fully-parsed message history. Two invariants matter and neither is obvious
 * from reading the imperative code — the active chat and a streaming chat must
 * survive eviction, because clearing their store cancels `viewModelScope` and
 * kills an in-flight reply.
 */
object VmCacheLru {
    data class State(
        val resident: List<String> = emptyList(),
        val evicted: List<String> = emptyList(),
    )

    /**
     * Replay [touches] through the policy. [active] is the foregrounded chat,
     * [pinned] the ones with live work; both are exempt from eviction and —
     * under [ResidentEvictionPolicy] — do NOT count against [maxResident],
     * which now bounds only the IDLE resident set. Delegates to the single
     * source of truth so this simulation can never drift from production.
     */
    fun simulate(
        touches: List<String>,
        maxResident: Int,
        active: String? = null,
        pinned: Set<String> = emptySet(),
    ): State {
        val lru = mutableListOf<String>()
        val evicted = mutableListOf<String>()
        val protectedKeys = buildSet {
            addAll(pinned)
            if (active != null) add(active)
        }
        for (id in touches) {
            lru.remove(id)
            lru.add(id)
            val victims = ResidentEvictionPolicy.keysToEvict(
                lruOrder = lru,
                protectedKeys = protectedKeys,
                maxResidentIdle = maxResident,
            )
            for (v in victims) {
                lru.remove(v)
                evicted.add(v)
            }
        }
        return State(resident = lru.toList(), evicted = evicted.toList())
    }
}
