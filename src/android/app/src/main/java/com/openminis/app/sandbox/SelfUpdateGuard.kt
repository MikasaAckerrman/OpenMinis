package com.openminis.app.sandbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.openminis.app.diagnostics.JankMonitor
import com.openminis.app.logging.AppLogger

/**
 * Keeps `pm install` of our own package from looking like a crash.
 *
 * WHAT HAPPENED (2026-09-21 13:12, logcat-proven): an agent session ran
 * `pm install -r -t /data/local/tmp/minis-test.apk` to install the freshly
 * built APK. Android kills the running process the moment PackageManager
 * swaps the package — ActivityTaskManager logged "app died, no saved state"
 * ONE SECOND after the install command, the in-flight turn vanished, and
 * the user reported it as "при запросе вылетает". Nothing in the app
 * crashed: the process was murdered by its own update.
 *
 * WHAT WE DO NOW. ExecutionCoordinator calls [beforePackageInstall] BEFORE
 * handing a package-install command to the shell:
 *  - log the exact reason (logcat + file) so the "crash" is explainable;
 *  - when the command names OUR package (or is any install-create/commit
 *    session, which we cannot attribute), schedule a relaunch alarm
 *    [RELAUNCH_DELAY_MS] out: if the process survives (the APK was another
 *    app's), the alarm harmlessly re-opens the app; if the process died,
 *    the user is back in the app instead of staring at the launcher.
 *
 * This is deliberately syntactic, like DestructiveCommandPolicy: it reads
 * the command line, it does not promise to catch every path an APK could
 * take onto the device (adb, shizuku direct, PackageInstaller UI).
 */
object SelfUpdateGuard {

    private const val TAG = "Minis.SelfUpdate"
    private const val FILE_TAG = "SelfUpdateGuard"

    /** How long to give PackageManager to finish the swap before relaunch. */
    private const val RELAUNCH_DELAY_MS = 12_000L

    private const val ALARM_REQUEST_CODE = 0x51F1

    private val INSTALL_HINTS = listOf(
        Regex("""\bpm\s+install\b"""),
        Regex("""\bcmd\s+package\s+install\b"""),
        // Multi-step PackageInstaller shell sessions
        Regex("""\binstall-create\b"""),
        Regex("""\binstall-commit\b"""),
        // Bundtool / fastboot-style flash of an APK package
        Regex("""\bpm\s+install-write\b"""),
    )

    /** True when the command installs (or stages) ANY package. */
    fun isPackageInstall(command: String): Boolean =
        INSTALL_HINTS.any { it.containsMatchIn(command) }

    /**
     * Must run BEFORE the shell executes the command. Returns true when the
     * command explicitly references our own package (full guard).
     */
    fun beforePackageInstall(context: Context, command: String): Boolean {
        val self = command.contains(context.packageName)
        Log.w(TAG, "package-install command detected (self=$self): ${command.take(160).replace('\n', ' ')}")
        AppLogger.warning(
            FILE_TAG,
            "package-install command detected (self=$self): ${command.take(160).replace('\n', ' ')}",
        )
        JankMonitor.mark("self-update install self=$self")
        if (self) scheduleRelaunch(context)
        return self
    }

    private fun scheduleRelaunch(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent().setClassName(context, context.packageName + ".MainActivity")
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            val pi = PendingIntent.getActivity(
                context,
                ALARM_REQUEST_CODE,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // setAndAllowWhileIdle: no SCHEDULE_EXACT_ALARM permission needed,
            // fires even in Doze. A 12s alarm is inexact-safe.
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RELAUNCH_DELAY_MS,
                pi,
            )
            val line = "self-update: process will be KILLED by PackageManager during install — " +
                "relaunch alarm scheduled in ${RELAUNCH_DELAY_MS / 1000}s"
            Log.w(TAG, line)
            AppLogger.warning(FILE_TAG, line)
        } catch (e: Exception) {
            Log.e(TAG, "failed to schedule relaunch alarm: ${e.message}")
            AppLogger.error(FILE_TAG, "failed to schedule relaunch alarm: ${e.message}")
        }
    }
}
