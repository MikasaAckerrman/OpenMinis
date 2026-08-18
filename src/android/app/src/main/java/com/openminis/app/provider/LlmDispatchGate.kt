package com.openminis.app.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide throttle that sits in front of every outbound LLM stream so that
 * many concurrent chat sessions don't (a) burst past a provider's RPM window
 * and trip HTTP 429, or (b) open so many simultaneous SSE connections that the
 * device UI starves and lags.
 *
 * ## Two independent controls
 *
 * 1. **Per-key token bucket** — paces the *rate* of new requests. Keyed by the
 *    request host (the thing a provider's RPM limit is actually attached to),
 *    so sessions on different providers don't throttle each other. This is the
 *    root-cause fix for "Rate limited — please try again later" when several
 *    sessions fire at once: instead of N requests landing in the same 100ms,
 *    they self-pace at the refill rate. See [TokenBucket] for the arithmetic.
 *
 * 2. **Global stream semaphore** — caps the *number* of simultaneous streams
 *    across the whole app. An SSE stream holds a socket + a reader coroutine +
 *    UI recomposition for its entire lifetime; 12 of them at once is what makes
 *    a mid-range device stutter. Holding a permit for the stream's lifetime
 *    (acquired before dispatch, released on completion/cancellation) bounds the
 *    concurrent load regardless of how many chats the user has open or how many
 *    scheduled tasks fire together.
 *
 * Both are intentionally generous by default — this is a *safety valve* against
 * pathological bursts, not a hard quota. A single active session behaves
 * exactly as before (bucket starts full, permit always available).
 *
 * ## Why an object (process-global) and not DI-scoped
 *
 * The whole point is coordination ACROSS sessions/ViewModels, so the state must
 * outlive any one of them. The providers already share one process-global
 * [com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool] for the
 * same reason; this is the request-admission counterpart to that pool.
 */
object LlmDispatchGate {

    /**
     * Max simultaneous LLM streams app-wide. LLM streaming is network-I/O-bound,
     * not CPU-bound: a waiting stream costs a socket + a lightweight reader
     * coroutine, so running many at once barely touches CPU. The real cost of
     * "many at once" is UI recomposition — but only the ONE foreground chat
     * recomposes; background sessions stream through the headless path with no
     * Compose work. So the cap is deliberately high: it exists ONLY to stop a
     * pathological burst (e.g. a storm of scheduled tasks opening dozens of
     * streams at once), NOT to serialise the user's real parallel work. The
     * product requirement is "at least 10 concurrent sessions run unthrottled",
     * so the valve sits well above that with headroom to spare.
     */
    @Volatile
    var maxConcurrentStreams: Int = 32

    /**
     * Sustained requests-per-minute budget per host. **Zero disables local
     * rate pacing entirely (the shipped default).**
     *
     * Rationale: pre-emptive local pacing throttled the *common* case (many
     * sessions each taking a turn) to defend against the *rare* case (a
     * provider actually returning HTTP 429) that is already handled reactively
     * — [ChatViewModel] honours `Retry-After` and backs off per-provider when a
     * 429 really arrives. Pacing keyed by host also meant every session on the
     * same provider shared one bucket, so a burst of 8 then 2 req/s was split
     * across all of them and re-spent on every agent-loop step — exactly the
     * "everything is divided across all my sessions" stall. Let the provider
     * declare its own limit via [enablePacing]; until then, do not pace.
     */
    @Volatile
    var defaultRpm: Double = 0.0

    /** Burst allowance = how many requests may fire back-to-back before pacing. */
    @Volatile
    var burstCapacity: Double = 8.0

    /** True when a positive per-host rate budget is configured. */
    val pacingEnabled: Boolean get() = defaultRpm > 0.0

    /**
     * Opt in to per-host rate pacing with a declared limit (e.g. from a
     * provider's published RPM). Clears existing buckets so the new rate takes
     * effect immediately. Pass rpm <= 0 to disable pacing again.
     */
    fun enablePacing(rpm: Double, burst: Double = burstCapacity) {
        defaultRpm = rpm
        burstCapacity = burst
        buckets.clear()
    }

    private val semaphoreLock = Any()
    @Volatile
    private var semaphoreCache: Pair<Int, Semaphore>? = null

    /**
     * Semaphore sized to the CURRENT [maxConcurrentStreams]. Rebuilt when the
     * setting changes (kotlinx `Semaphore` permit count is fixed at
     * construction, so a runtime change — from settings or a test — needs a
     * fresh instance). In-flight permits on an old instance simply drain
     * against it; the swap only affects permits acquired after the change,
     * which is the correct semantics for a soft concurrency cap.
     */
    private fun semaphore(): Semaphore {
        val want = maxConcurrentStreams.coerceAtLeast(1)
        semaphoreCache?.let { if (it.first == want) return it.second }
        return synchronized(semaphoreLock) {
            val cached = semaphoreCache
            if (cached != null && cached.first == want) cached.second
            else Semaphore(want).also { semaphoreCache = want to it }
        }
    }

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    /** Injectable clock so tests don't sleep. */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    private fun bucketFor(key: String): TokenBucket =
        buckets.getOrPut(key) {
            TokenBucket(
                capacity = burstCapacity,
                refillPerSec = defaultRpm / 60.0,
                initialTokens = burstCapacity,
                initialMs = clock(),
            )
        }

    /**
     * Suspend until the per-[key] rate budget admits one request. Cooperative:
     * uses [delay] so it never blocks a thread and is cancellable with the
     * calling coroutine (a cancelled turn stops waiting immediately).
     *
     * Bounded retry loop rather than a single computed sleep because other
     * coroutines may drain the bucket during our wait; we recompute after each
     * nap. The per-nap ceiling keeps us responsive to cancellation.
     */
    suspend fun awaitRateSlot(key: String) {
        // Pacing disabled (shipped default): admit immediately. The provider's
        // own 429/Retry-After is the rate authority; we don't pre-emptively
        // throttle the user's concurrent sessions. Guard here — NOT via a zero
        // refill rate — because a zero-rate bucket would report "never refills"
        // and block forever after the initial burst.
        if (!pacingEnabled) return
        val bucket = bucketFor(key)
        while (true) {
            val wait = synchronized(bucket) { bucket.tryAcquire(clock()) }
            if (wait <= 0L) return
            // Jitter de-synchronizes waiters. Without it, N coroutines that hit
            // an empty bucket at the same instant compute the SAME wait, sleep
            // the same duration, and wake together — re-forming the exact burst
            // the bucket exists to break (a thundering herd inside the gate).
            // ±20% spread staggers their wake-ups so tokens are consumed one at
            // a time. Capped per-nap so cancellation stays responsive.
            val capped = wait.coerceAtMost(1_000L)
            val jitter = (capped * 0.2 * jitterFraction()).toLong()
            delay((capped + jitter).coerceAtLeast(1L))
        }
    }

    /** Random in [-1.0, 1.0). Extracted so tests can pin it deterministically. */
    @Volatile
    var jitterFraction: () -> Double = { java.util.concurrent.ThreadLocalRandom.current().nextDouble(-1.0, 1.0) }

    /**
     * Acquire a stream permit, run [block] while holding it, release on any
     * exit (normal, throw, or cancellation). Combine with [awaitRateSlot] at
     * the call site: rate-pace first, THEN take a concurrency permit, so a
     * request waiting on the bucket isn't also occupying one of the scarce
     * stream slots.
     */
    suspend fun <T> withStreamPermit(block: suspend () -> T): T =
        semaphore().withPermit { block() }

    /** Test/diagnostic hook — drop all per-host buckets and the semaphore. */
    /** Test/diagnostic hook — drop all buckets, the semaphore, AND restore the
     *  shipped defaults so one test's pacing config never leaks into the next. */
    fun resetForTest() {
        buckets.clear()
        synchronized(semaphoreLock) { semaphoreCache = null }
        maxConcurrentStreams = 32
        defaultRpm = 0.0
        burstCapacity = 8.0
    }

    /**
     * Stable throttle key for a request URL: the host. Falls back to the whole
     * string when it isn't a parseable URL so a weird base never collapses
     * every provider onto one shared bucket.
     */
    fun keyForUrl(url: String): String =
        url.toHttpUrlOrNull()?.host ?: url

    // NOTE: OkHttp 4.x exposes URL parsing via the Kotlin `String
    // .toHttpUrlOrNull()` extension (the Java-style static `HttpUrl.parse` is
    // deprecated-to-error). Null-safe: a non-URL base falls back to the raw
    // string so a weird endpoint never collapses every provider onto one
    // shared bucket.
}
