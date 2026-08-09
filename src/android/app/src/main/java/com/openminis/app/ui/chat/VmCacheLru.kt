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
     * [pinned] the streaming ones; both are exempt from eviction.
     */
    fun simulate(
        touches: List<String>,
        maxResident: Int,
        active: String? = null,
        pinned: Set<String> = emptySet(),
    ): State {
        val lru = mutableListOf<String>()
        val evicted = mutableListOf<String>()
        for (id in touches) {
            lru.remove(id)
            lru.add(id)
            var i = 0
            while (lru.size > maxResident && i < lru.size) {
                val candidate = lru[i]
                if (candidate == active || candidate in pinned) {
                    i++
                    continue
                }
                lru.removeAt(i)
                evicted.add(candidate)
            }
        }
        return State(resident = lru.toList(), evicted = evicted.toList())
    }
}
