package com.openminis.app.sandbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M-R3.2] The grammers login bridge: the pure step translation (daemon
 * markers → the screen's driver-compatible JSON), the argv parsing, and
 * the real-process await mechanics of the interactive `minisd login`
 * session (reviewer verdict: variant (a) — the process stays alive
 * between the screen's steps).
 */
class MinisdLoginRunnerTest {

    private fun verdict(
        step: String,
        phaseBefore: String,
        await: MinisdLoginRunner.Await,
        tail: String = "",
    ): MinisdLoginRunner.StepOutcome =
        MinisdLoginRunner.verdictFor(step, phaseBefore, await, tail)

    // ── step translation ────────────────────────────────────────────────

    @Test
    fun `send-code with the code prompt is code_sent and keeps the session`() {
        val o = verdict("send-code", MinisdLoginRunner.PH_DONE, MinisdLoginRunner.Await.Marker(MinisdLoginRunner.M_CODE))
        assertTrue(o.json.optBoolean("code_sent"))
        assertEquals(MinisdLoginRunner.PH_AWAIT_CODE, o.phase)
    }

    @Test
    fun `send-code into an already-authorized session is an honest dead end`() {
        val o = verdict("send-code", MinisdLoginRunner.PH_DONE, MinisdLoginRunner.Await.Marker(MinisdLoginRunner.M_ALREADY))
        assertTrue(o.json.getString("error").contains("already authorized"))
    }

    @Test
    fun `first verify sees the 2FA requirement and parks the session at the password prompt`() {
        val o = verdict("verify", MinisdLoginRunner.PH_AWAIT_CODE, MinisdLoginRunner.Await.Marker(MinisdLoginRunner.M_PASSWORD))
        assertTrue(o.json.optBoolean("needs_password"))
        assertEquals(MinisdLoginRunner.PH_AWAIT_PASSWORD, o.phase)
    }

    @Test
    fun `first verify without 2FA lands logged_in`() {
        val o = verdict("verify", MinisdLoginRunner.PH_AWAIT_CODE, MinisdLoginRunner.Await.Marker(MinisdLoginRunner.M_SAVED))
        assertTrue(o.json.optBoolean("logged_in"))
        assertEquals(MinisdLoginRunner.PH_DONE, o.phase)
    }

    @Test
    fun `verify exit zero with already-authorized reads as logged_in`() {
        val o = verdict(
            "verify", MinisdLoginRunner.PH_AWAIT_CODE, MinisdLoginRunner.Await.Exit(0),
            "minisd: session is already authorized, nothing to do",
        )
        assertTrue(o.json.optBoolean("logged_in"))
    }

    @Test
    fun `verify failure surfaces the daemon tail`() {
        val o = verdict(
            "verify", MinisdLoginRunner.PH_AWAIT_CODE, MinisdLoginRunner.Await.Exit(1),
            "minisd: sign-in failed: PHONE_CODE_INVALID",
        )
        assertTrue(o.json.getString("error").contains("PHONE_CODE_INVALID"))
    }

    @Test
    fun `password step success is logged_in`() {
        val o = verdict("verify-pw", MinisdLoginRunner.PH_AWAIT_PASSWORD, MinisdLoginRunner.Await.Marker(MinisdLoginRunner.M_SAVED))
        assertTrue(o.json.optBoolean("logged_in"))
        assertEquals(MinisdLoginRunner.PH_DONE, o.phase)
    }

    @Test
    fun `every step surfaces a timeout as an error`() {
        assertTrue(verdict("send-code", MinisdLoginRunner.PH_DONE, MinisdLoginRunner.Await.Timeout).json.has("error"))
        assertTrue(verdict("verify", MinisdLoginRunner.PH_AWAIT_CODE, MinisdLoginRunner.Await.Timeout).json.has("error"))
        assertTrue(verdict("verify-pw", MinisdLoginRunner.PH_AWAIT_PASSWORD, MinisdLoginRunner.Await.Timeout).json.has("error"))
    }

    // ── error tail ──────────────────────────────────────────────────────

    @Test
    fun `error tail keeps the last meaningful lines only`() {
        // The window is the last 3 lines: with 5 lines the first two must
        // fall out, the error line must stay.
        val t = MinisdLoginRunner.errorTail(
            "line-AAA\nline-BBB\nline-CCC\n" +
                "minisd: sign-in failed: FLOOD_WAIT_3\nminisd: connect failed: boom\n",
        )
        assertTrue(t.contains("FLOOD_WAIT_3"))
        assertFalse(t.contains("AAA"))
        assertFalse(t.contains("BBB"))
        assertEquals("login failed without output", MinisdLoginRunner.errorTail("   \n\n"))
    }

    // ── argv parsing ────────────────────────────────────────────────────

    @Test
    fun `python argparse flags are stripped, step args kept`() {
        val a = MinisdLoginRunner.parseArgs(
            listOf("--api-id", "123", "--api-hash", "abc", "verify", "--code", "555", "--password", "pw"),
        )
        assertEquals("verify", a.step)
        assertEquals("555", a.code)
        assertEquals("pw", a.password)
        val b = MinisdLoginRunner.parseArgs(listOf("send-code", "--phone", "+79962227579"))
        assertEquals("send-code", b.step)
        assertEquals("+79962227579", b.phone)
    }

    // ── status contract ─────────────────────────────────────────────────

    @Test
    fun `status json mirrors the screen contract`() {
        val json = JSONObject(
            MinisdLoginRunner.statusJson(hasGrammers = true, envReady = false)
                .removePrefix("RESULT "),
        )
        assertTrue(json.optBoolean("logged_in"))
        assertFalse(json.optBoolean("env_ready"))
        assertFalse(json.optBoolean("has_saved_creds"))
    }

    @Test
    fun `legacy registry rows do not count as logged in on the grammers daemon`() {
        // [device-round correction] The pyrogram-era accounts.json proved
        // nothing about the rust daemon: saying logged-in off it hid the
        // login form and blocked the whole bridge (authorized=false on the
        // live device). Only the grammers session file counts.
        val json = JSONObject(
            MinisdLoginRunner.statusJson(hasGrammers = false, envReady = true)
                .removePrefix("RESULT "),
        )
        assertFalse(json.optBoolean("logged_in"))
        assertTrue(json.optBoolean("env_ready"))
    }

    // ── await mechanics on REAL processes ───────────────────────────────

    @Test
    fun `await sees the marker a real process emits`() {
        val p = ProcessBuilder(
            "/bin/sh", "-c",
            "sleep 0.2; echo 'minisd login: enter login code:'; read x; echo got-it",
        ).start()
        val s = MinisdLoginRunner.LoginSession(p)
        val a = s.await(listOf(MinisdLoginRunner.M_CODE), 5)
        assertTrue("the code prompt must surface as a marker", a is MinisdLoginRunner.Await.Marker)
        assertTrue(s.writeLine("12345"))
        s.kill()
        s.joinReader(2000)
        assertTrue("a killed session must read dead", !s.alive)
    }

    @Test
    fun `await times out against a silent process`() {
        val p = ProcessBuilder("/bin/sh", "-c", "sleep 3").start()
        val s = MinisdLoginRunner.LoginSession(p)
        val a = s.await(listOf(MinisdLoginRunner.M_CODE), 1)
        assertTrue(a is MinisdLoginRunner.Await.Timeout)
        s.kill()
    }

    @Test
    fun `a dying process is reported as an exit with its code`() {
        // The missing-credentials refusal of main.rs: eprintln + exit 2.
        // redirectErrorStream mirrors LoginSession's own wiring — the
        // daemon's stderr is the tail the verdicts read.
        val p = ProcessBuilder(
            "/bin/sh", "-c",
            "echo 'minisd: login requires TG_API_ID and TG_API_HASH in the environment' >&2; exit 2",
        ).apply { redirectErrorStream(true) }.start()
        val s = MinisdLoginRunner.LoginSession(p)
        val a = s.await(listOf(MinisdLoginRunner.M_CODE), 5)
        assertEquals(2, (a as MinisdLoginRunner.Await.Exit).code)
        assertTrue(s.tail().contains("TG_API_ID"))
    }
}
