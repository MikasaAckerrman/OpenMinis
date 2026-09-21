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
 *  - attribute the APK being installed: parse the archive path out of the
 *    command and read its packageName via PackageManager. If the archive
 *    says it is us → schedule a relaunch alarm. If attribution FAILS
 *    (path unreadable, no path in the command, multi-step install-write
 *    session) we ALSO schedule — a false alarm costs one harmless app
 *    bring-to-front, a missed self-install strands the user on the
 *    launcher mid-session (the exact observed failure). Only a POSITIVE
 *    identification of some OTHER package skips the alarm.
 *
 * Command analysis is pure string logic and lives in
 * [SelfUpdateCommandAnalysis] (its own file, no Android imports) so it is
 * unit-testable without the Android SDK (see SelfUpdateGuardTest, run
 * locally with kotlinc — the project's "prove locally, build only
 * confirms" rule).
 */
object SelfUpdateGuard {

    private const val TAG = "Minis.SelfUpdate"
    private const val FILE_TAG = "SelfUpdateGuard"

    /** How long to give PackageManager to finish the swap before relaunch. */
    private const val RELAUNCH_DELAY_MS = 12_000L

    private const val ALARM_REQUEST_CODE = 0x51F1

    /** True when the command installs (or stages) ANY package. */
    fun isPackageInstall(command: String): Boolean =
        SelfUpdateCommandAnalysis.isPackageInstall(command)

    /**
     * Must run BEFORE the shell executes the command. Returns true when the
     * install is (or may be) a self-update and a relaunch was scheduled.
     */
    fun beforePackageInstall(context: Context, command: String): Boolean {
        val self = isSelfUpdate(context, command)
        Log.w(
            TAG,
            "package-install detected (self=$self): ${command.take(160).replace('\n', ' ')}",
        )
        AppLogger.warning(
            FILE_TAG,
            "package-install detected (self=$self): ${command.take(160).replace('\n', ' ')}",
        )
        JankMonitor.mark("self-update install self=$self")
        if (self) scheduleRelaunch(context)
        return self
    }

    /**
     * An install is a SELF-update when:
     *  1. the command text names our package, OR
     *  2. the APK archive it references parses to our package, OR
     *  3. we cannot attribute it at all (unknown → assume self: the cost of
     *     a false alarm is one bring-to-front; the cost of a miss is the
     *     user stranded on the launcher — asymmetric by design).
     * Only a positively identified OTHER package returns false.
     */
    private fun isSelfUpdate(context: Context, command: String): Boolean {
        if (command.contains(context.packageName)) return true
        val paths = SelfUpdateCommandAnalysis.extractApkPaths(command)
        if (paths.isEmpty()) return true // multi-step install-create session: unattributable
        val pm = context.packageManager
        var sawForeign = false
        var sawUnreadable = false
        for (path in paths) {
            val info = try {
                // Parses only the manifest section; a one-off ~ms cost on
                // the rare install command, not a hot path.
                pm.getPackageArchiveInfo(path, 0)
            } catch (_: Exception) {
                null
            }
            when {
                info == null || info.packageName.isNullOrBlank() ->
                    // Unreadable (mode 600, foreign storage, already-moved
                    // path, or a path that only exists in the PRoot
                    // namespace) — we do NOT know what this is: assume self.
                    sawUnreadable = true
                info.packageName == context.packageName -> return true
                else -> sawForeign = true
            }
        }
        // At least one archive was foreign and none was provably ours AND
        // none was unreadable → safe to skip the alarm.
        return !(sawForeign && !sawUnreadable)
    }

    private fun scheduleRelaunch(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Generic launcher intent: resolves the enabled launcher
            // activity (or activity-alias) of OUR package without
            // hardcoding a class name — the applicationId (…app.clone) and
            // the Kotlin class package (com.openminis.app) differ, so a
            // setClassName(context.packageName, …) would resolve to a class
            // that does not exist and the fallback would silently no-op.
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
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
            // BAL (background-activity-launch) note: starting an activity
            // from the background is normally blocked on Android 10+, but
            // the app holds SYSTEM_ALERT_WINDOW (it is the overlay
            // permission — required for the session capsule and granted in
            // normal use), which is a documented BAL exemption. If the user
            // ever revokes SAW, the alarm fires but the launch is blocked;
            // sessions persist per-turn, so a manual reopen loses nothing.
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

