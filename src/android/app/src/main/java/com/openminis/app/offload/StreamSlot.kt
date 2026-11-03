package com.openminis.app.offload

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [T-stream-slot-claim] Claiming the run slot of a session.
 *
 * The slot is a single permit: one agent turn at a time. The check and the
 * claim have to be ONE atomic step.
 *
 * Why: the start paths read the flag, did their preparation, and only then
 * wrote it. The window between the read and the write was wide - 68 lines
 * in the tool-block rerun alone - and a message that arrived in that window
 * passed the same check. Two turns then ran at once and wrote into the same
 * transcript, which is what the user saw as two mixed-together works.
 *
 * [H2-ownership] (2026-11-04 review round): the claim returns an ownership
 * TOKEN and only that token can release the slot. A stale coroutine tail
 * calling releaseStreamSlot with a foreign/old token is a physical no-op —
 * it can no longer drop a NEWER turn's flag (the Stop-during-prepare bug:
 * an aborted send released the slot a concurrent new send had already
 * taken, killing the Stop button and the session-mutex exclusivity with it).
 *
 * Implementation note: the AtomicLong below IS the lock; the Boolean flow is
 * only a UI mirror. Ownership transfer happens in ONE CAS on the long, so
 * the old read-then-write window cannot reopen. The mirror write lands right
 * after the CAS — the flag may lag by nanoseconds, which is safe because
 * every entry guard decides via tryClaimStreamSlot, which cannot lie.
 */
// [N1-per-flow] (2026-11-04 review round 2) The registry is keyed by the
// flow INSTANCE, not process-global: in HEAD `_isStreaming` was a per-VM
// flag, so two chats could stream in parallel (per-session mutexes handle
// their own exclusivity). A top-level slot would have serialized unrelated
// chats and parked the loser's queue in a foreign VM. WeakHashMap: a dead
// VM's registry is collectable together with its flow.
private val registries = java.util.Collections.synchronizedMap(
    java.util.WeakHashMap<MutableStateFlow<Boolean>, AtomicLong>()
)
private val claimCounter = AtomicLong(0L)

private fun registryOf(flow: MutableStateFlow<Boolean>): AtomicLong =
    registries.computeIfAbsent(flow) { AtomicLong(0L) }

/**
 * Attempt to take the run slot. Returns the ownership token on success
 * (only this exact value may release the slot), `null` when somebody
 * else already holds it. The token doubles as the idempotency key: a
 * double release of the same token is a harmless no-op.
 */
fun MutableStateFlow<Boolean>.tryClaimStreamSlot(): Long? {
    val token = claimCounter.incrementAndGet()
    if (!registryOf(this).compareAndSet(0L, token)) return null
    value = true
    return token
}

/**
 * Give the slot back — but only if [claim] is still the current owner.
 * A path that gives up before the run owns the slot must call this with
 * its own token, otherwise the session stays busy for good. A stale tail
 * (token superseded by a takeover) must NOT free the new turn: the CAS
 * fails and this is a silent no-op by contract.
 */
fun MutableStateFlow<Boolean>.releaseStreamSlot(claim: Long?) {
    if (claim == null) return
    val registry = registryOf(this)
    if (registry.compareAndSet(claim, 0L)) {
        value = false
    } else if (registry.get() == 0L) {
        // [A1-doublecheck] (review round 2) Registry free but the mirror may
        // be stuck at true (a forceRelease raced an owner re-assert). Drop
        // the mirror unconditionally, then RE-CHECK the registry: if a fresh
        // claim slipped in between, restore its flag — the old CAS(false→true)
        // form was inverted (it could never fix a stuck-true and could blip
        // a new owner's flag down instead).
        value = false
        if (registry.get() != 0L) value = true
    }
}

/**
 * Breaker for abort/overtake paths (cancelStream, user-retry takeover,
 * stale-stream recovery): the caller deliberately declares that no one
 * owns the run anymore. Clears the ownership registry and the UI flag
 * together. Never use it as a substitute for an owner release — it
 * would strip a legitimate claim.
 */
fun MutableStateFlow<Boolean>.forceReleaseStreamSlot() {
    registryOf(this).set(0L)
    value = false
}
