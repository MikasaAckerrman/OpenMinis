package com.openminis.app.data.model

/**
 * [T-same-model-failover] Which OTHER endpoints serve the same model as the one
 * that just failed?
 *
 * ## The problem this solves
 *
 * The user's network journal (26 entries, host=gorouter.app) shows the shape:
 *
 *   FAIL   NO_RESPONSE  attempt=1/2 … no response from server (120s)
 *   RETRY  NO_RESPONSE  attempt=1   waited=0s  evicted=true
 *   FAIL   NO_RESPONSE  attempt=2/2 … no response from server (120s)
 *   GIVEUP NO_RESPONSE  attempts=2
 *
 * Two 120-second silences on the SAME host, then the turn dies. `GIVEUP` proves
 * no handover happened: [com.openminis.app.data.FallbackDecision] had already
 * returned shouldFallback=true, but the candidate list was empty, so `next` was
 * null.
 *
 * It was empty by construction. `buildFallbackProviders` starts with
 *
 *   val groupId = _selectedGroupId.value ?: return emptyList()
 *
 * and the user's sessions are bound to a MODEL, not to a group (their only two
 * groups are Voice Input / Voice Output). So group fallback — the entire
 * failover mechanism — was dead for every normal session.
 *
 * Meanwhile `chat.models.list` reports the failing model, claude-opus-5-thinking,
 * on **56** provider instances. Fifty-five live doors to the same model stood
 * next to a hung one and none was tried.
 *
 * ## Why this is the right mechanism
 *
 * Raising the watchdog again cannot help: the user is right that gorouter *can*
 * work, it just goes silent on a node. Waiting longer on a silent socket buys
 * nothing — the fix is to stop waiting and walk through a different door to the
 * SAME model. The reply the user gets is identical, because the model is
 * identical; only the transport changes.
 *
 * Kept pure (no Android, no OkHttp, no ViewModel) so the ordering rules are
 * testable, and so the "same model" notion has ONE definition instead of being
 * re-derived at each call site.
 */
object SameModelFailover {

    /**
     * One candidate endpoint for a model.
     *
     * @param entryId ModelEntry uuid — the identity used to skip the endpoint
     *   that just failed. Compared instead of the label because labels are
     *   user-editable free text and dozens of the user's instances share one.
     * @param instanceId owning provider instance.
     * @param modelId the model served here. Candidates must match the failed
     *   model exactly: a "similar" model is a different answer, and silently
     *   swapping it would be worse than the error.
     * @param host endpoint host, used to prefer a DIFFERENT host first.
     * @param isEnabled disabled instances are not candidates.
     * @param isHidden entries the user hid from the picker are still valid
     *   transports — hiding is a UI preference, not a "do not use" flag. Kept as
     *   an explicit field so that decision is visible rather than implied.
     * @param hasCredential no key → cannot be dialled.
     */
    data class Candidate(
        val entryId: String,
        val instanceId: String,
        val modelId: String,
        val host: String,
        val isEnabled: Boolean = true,
        val isHidden: Boolean = false,
        val hasCredential: Boolean = true,
    )

    /**
     * Candidates for [modelId], ordered best-first, excluding [failedEntryId].
     *
     * Ordering: a host DIFFERENT from [failedHost] comes first, then the
     * original host's other keys. That ordering is the whole point — the failure
     * being escaped is a property of the host (a silent gateway node, an
     * upstream 502), so another key on the same dead host is the least likely
     * to help. It is still kept as a later resort, because a relay can fail per
     * account/route while the host as a whole is fine.
     *
     * Within each bucket, input order is preserved (stable) so the result is
     * deterministic and the user's own arrangement decides — no hidden
     * "cleverness" the user cannot see or predict.
     *
     * @param maxCandidates cap on returned endpoints. With 56 doors to one
     *   model, trying them all would keep a doomed turn alive for minutes; a
     *   small cap fails fast and honestly instead. `<= 0` means unlimited, since
     *   "cap of zero" would silently disable failover — the failure mode this
     *   object exists to remove.
     */
    fun candidatesFor(
        modelId: String,
        failedEntryId: String?,
        failedHost: String?,
        all: List<Candidate>,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    ): List<Candidate> {
        val usable = all.filter {
            it.modelId == modelId &&
                it.entryId != failedEntryId &&
                it.isEnabled &&
                it.hasCredential
        }
        val (otherHost, sameHost) = usable.partition {
            failedHost == null || !it.host.equals(failedHost, ignoreCase = true)
        }
        val ordered = otherHost + sameHost
        return if (maxCandidates <= 0) ordered else ordered.take(maxCandidates)
    }

    /**
     * Default cap on same-model endpoints tried in one turn.
     *
     * Three, not "all": each attempt costs a real dial plus whatever the new
     * endpoint takes to first byte, and the user is watching a stalled reply. If
     * three independent endpoints for one model all fail, the problem is not the
     * endpoint — surfacing that quickly is more useful than a four-minute march
     * through 56 keys.
     */
    const val DEFAULT_MAX_CANDIDATES: Int = 3
}
