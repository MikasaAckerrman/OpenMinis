package com.openminis.app.data

/**
 * [T-compact-external] The user-initiated "external compaction" round trip:
 * the session is exported as a text file (prompt header + previous summary +
 * transcript), the user has ANY external model compress it, and the result is
 * applied as the compaction summary — same commit path, same invariants as
 * the internal compactor (marker v2, MutationJournal, disk-cache hygiene).
 *
 * Why ids: export and import are two separate compactAll invocations. The
 * range is recomputed from live history on import, and the user may have sent
 * messages in between — a summary of range A folded onto range B would either
 * lose messages or keep them duplicated. The first/last dbMessageId of the
 * exported range are recorded at export time and re-verified at apply time;
 * any drift is a hard refusal with a "re-export" hint, never a silent misfold.
 */
sealed class ExternalCompactionPlan {
    /** Export-only pass: write the .md file, share it, do NOT touch history. */
    data object Export : ExternalCompactionPlan()

    /** Apply an externally produced summary to the previously exported range. */
    data class Import(
        val summary: String,
        val firstDbId: String?,
        val lastDbId: String?,
    ) : ExternalCompactionPlan()
}
