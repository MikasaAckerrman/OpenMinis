package com.openminis.app.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [M-R3.1] The launch contract of the wired daemon: the command shape
 * (binary, mode, port, and the `--storage` the vault wiring passes) and
 * the stdin secret hand-off ("no secret, no service" — the supervisor
 * writes the ONE line and closes the pipe). The binary is a placeholder
 * file (isAvailable must pass) and the process is a stub: the real
 * artifact is aarch64, which JVM tests cannot exec.
 */
class DaemonSupervisorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Minimal process stub: captures the stdin bytes, pretends to run. */
    private class FakeProcess : Process() {
        val stdin = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = stdin.write(b)
            override fun write(b: ByteArray) = stdin.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stdin.write(b, off, len)
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun isAlive() = true
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit?): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
    }

    private var lastProc: FakeProcess? = null
    private var lastCommand: List<String> = emptyList()

    /** A real directory holding the placeholder binary — isAvailable()
     *  checks the FILESYSTEM, so the dir must exist. */
    private fun nativeLibDir(): File =
        tmp.newFolder("native-lib").also { dir ->
            File(dir, DaemonSupervisor.BINARY_NAME).writeText("ELF-PLACEHOLDER")
        }

    private fun supervisor(libDir: File, storage: File?, secret: String?): DaemonSupervisor =
        DaemonSupervisor(
            nativeLibraryDir = libDir.absolutePath,
            storage = storage,
            secretProvider = { secret },
            starter = { command ->
                lastCommand = command
                FakeProcess().also { lastProc = it }
            },
        )

    @Test
    fun `vault wiring command carries the storage directory`() {
        val libDir = nativeLibDir()
        val storage = tmp.newFolder("tg-vault")
        assertTrue(supervisor(libDir, storage, "secret-hex").start())

        assertEquals(
            listOf(
                File(libDir, DaemonSupervisor.BINARY_NAME).absolutePath,
                "vault",
                "--port", "7824",
                "--storage", storage.absolutePath,
            ),
            lastCommand,
        )
        // The secret lands on the child's stdin as ONE line — the daemon
        // refuses to serve without it.
        assertEquals("secret-hex\n", lastProc!!.stdin.toString("UTF-8"))
    }

    @Test
    fun `default command keeps the M-R1 shape without storage`() {
        val libDir = nativeLibDir()
        assertTrue(supervisor(libDir, null, null).start())

        assertEquals(
            listOf(File(libDir, DaemonSupervisor.BINARY_NAME).absolutePath, "vault", "--port", "7824"),
            lastCommand,
        )
    }

    @Test
    fun `a missing secret writes nothing and still returns a started process`() {
        assertTrue(supervisor(nativeLibDir(), tmp.newFolder("tg-vault"), null).start())

        assertEquals(0, lastProc!!.stdin.size())
        // The supervisor closed the pipe (use{}), so the daemon reads EOF
        // and exits instead of hanging — its side of the contract.
    }

    @Test
    fun `absent binary refuses to start instead of throwing`() {
        // nativeLibraryDir without the binary: the honest availability
        // check (an x86_64 Waydroid stand, a broken install) reads false
        // and the starter is never reached.
        val sup = DaemonSupervisor(
            nativeLibraryDir = tmp.newFolder("empty-lib").absolutePath,
            starter = { throw IllegalStateException("starter must never be reached") },
        )
        assertFalse(sup.start())
    }
}
