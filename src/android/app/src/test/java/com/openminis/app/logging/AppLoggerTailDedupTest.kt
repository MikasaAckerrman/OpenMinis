package com.openminis.app.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-gc-churn] Guards the log-file de-duplication fix.
 *
 * AppLogger.install() replaces the JVM's stdout/stderr with a PrintWriter
 * that BOTH writes each captured line to the file (writeFileLine) AND
 * forwards the raw bytes to the original stream. Those forwarded bytes are
 * picked up by logcat under the tags `System.out` / `System.err`, came back
 * through LogcatTailer, and were written to the file a SECOND time — because
 * the tail filter only excluded `Minis.*` and `AppLogger`.
 *
 * Effect in the 19.10 incident: every println in the process cost two file
 * writes with a flush() each, plus a String allocation per line on the
 * tailer's readLine(). `System.out` alone was 14 927 log lines.
 *
 * These assertions are exact string checks — no timing, no I/O, no flakiness.
 */
class AppLoggerTailDedupTest {

    @Test
    fun skipsAppLoggerOwnTags() {
        assertTrue(AppLogger.isOwnOutputTag("Minis.Jank"))
        assertTrue(AppLogger.isOwnOutputTag("Minis.ChatViewModel"))
        assertTrue(AppLogger.isOwnOutputTag("Minis.OkHttpNetTrace"))
        assertTrue(AppLogger.isOwnOutputTag("AppLogger"))
    }

    @Test
    fun skipsStdoutAndStderrTagsAlreadyWrittenByTheInterceptor() {
        // The actual regression: these used to pass the filter and land in the
        // file twice.
        assertTrue(AppLogger.isOwnOutputTag("System.out"))
        assertTrue(AppLogger.isOwnOutputTag("System.err"))
    }

    @Test
    fun keepsFrameworkAndDiagnosticTags() {
        // Everything else must still reach the file — the tailer exists so a
        // framework or vendor warning is never lost.
        assertFalse(AppLogger.isOwnOutputTag("View"))
        assertFalse(AppLogger.isOwnOutputTag("ViewRootImpl"))
        assertFalse(AppLogger.isOwnOutputTag("BLASTBufferQueue_Java"))
        assertFalse(AppLogger.isOwnOutputTag("ActivityManager"))
        assertFalse(AppLogger.isOwnOutputTag("WindowManager"))
        assertFalse(AppLogger.isOwnOutputTag("dgc_arg"))
        assertFalse(AppLogger.isOwnOutputTag("gc merge do a ConcurrentGCTask"))
    }

    @Test
    fun doesNotOverMatchMinisPrefix() {
        // Only the real Minis tag prefix is ours; a foreign tag that merely
        // starts with the same letters must not be swallowed.
        assertFalse(AppLogger.isOwnOutputTag("Minis"))
        assertFalse(AppLogger.isOwnOutputTag("MinisApp"))
        assertFalse(AppLogger.isOwnOutputTag("MinisX.Y"))
    }
}