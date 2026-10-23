package com.openminis.app.ui.chat

/**
 * A text buffer that one thread appends to while another thread may copy it.
 *
 * The streaming loop appends chunks here while it runs. A terminator on
 * another thread - the user's Stop, or a transport error that arrives while
 * the stream is still going - copies it to commit whatever survived.
 *
 * [T-live-turn-buffer] StringBuilder does not support that. `toString()` on a
 * builder another thread is appending to can return a torn string, and can
 * throw StringIndexOutOfBoundsException while doing it. A torn string that
 * does NOT throw is the worse case: it is silently persisted as the user's
 * answer, with characters dropped or repeated in the middle.
 *
 * Every access takes the lock, so a copy is always a whole number of chunks.
 * The lock is only ever held for the append itself or for the copy - both
 * microseconds - so the streaming loop does not feel it, and it is never held
 * across a suspension or any I/O.
 *
 * The length and the slice are taken as two separate operations on purpose:
 * they are not one atomic unit, and the caller reads them the same way it
 * read the raw builder before. What the lock adds is that the builder can
 * no longer be mutated *during* either operation.
 */
class LiveTurnBuffer {
    private val lock = Any()
    private val sb = StringBuilder()

    /** Appends one chunk. */
    fun append(text: String) {
        synchronized(lock) { sb.append(text) }
    }

    /** Drops everything appended so far. */
    fun clear() {
        synchronized(lock) { sb.setLength(0) }
    }

    /** A consistent copy of everything appended so far. */
    fun snapshot(): String = synchronized(lock) { sb.toString() }

    /** The number of characters appended so far. */
    val length: Int get() = synchronized(lock) { sb.length }

    /** Whether anything has been appended. */
    fun isNotEmpty(): Boolean = synchronized(lock) { sb.isNotEmpty() }

    /**
     * A consistent slice of what has been appended.
     *
     * Taken under the lock so the slice cannot straddle an append. The
     * bounds are the caller's, exactly as they were when it called
     * `StringBuilder.substring` directly.
     */
    fun substring(from: Int, to: Int): String = synchronized(lock) { sb.substring(from, to) }
}
