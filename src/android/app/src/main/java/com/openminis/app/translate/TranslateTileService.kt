package com.openminis.app.translate

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.openminis.app.logging.AppLogger

/**
 * Quick-settings tile: pull down the shade, tap, the clipboard is translated.
 *
 * WHY A TILE AT ALL. The user asked for the translator to sit in the floating
 * "quick tools" panel of their vivo launcher. That list is a FuntouchOS
 * feature with no public API — its contents cannot be changed programmatically.
 * The shade tile is the closest thing Android actually offers: two gestures
 * from anywhere in the system, works over any app.
 *
 * WHY startActivityAndCollapse AND NOT WORK IN THE SERVICE. Since Android 10
 * only the foreground app may read the primary clip, and a TileService is not
 * foreground. The tile therefore launches [TranslateClipboardActivity], which
 * comes to the front, reads the clip and finishes.
 *
 * API 34 CHANGED THE CONTRACT: startActivityAndCollapse(Intent) throws
 * UnsupportedOperationException and a PendingIntent must be passed instead.
 * Both paths are kept — the app supports older versions too.
 */
@RequiresApi(Build.VERSION_CODES.N)
class TranslateTileService : TileService() {

    companion object {
        private const val TAG = "TranslateTile"
        private const val REQUEST_CODE = 0x7A11
    }

    override fun onStartListening() {
        super.onStartListening()
        // The tile is only useful when the shared toolbox is present; reflect
        // that instead of failing after the tap.
        qsTile?.apply {
            state = if (TranslateBridge.isAvailable(applicationContext)) {
                Tile.STATE_INACTIVE
            } else {
                Tile.STATE_UNAVAILABLE
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, TranslateClipboardActivity::class.java).apply {
            action = TranslateClipboardActivity.ACTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
                val pi = android.app.PendingIntent.getActivity(
                    this, REQUEST_CODE, intent, flags)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Throwable) {
            // Some OEM shades reject the collapse call; the activity itself is
            // still worth starting, otherwise the tap does nothing at all.
            AppLogger.warning(TAG, "collapse failed (${e.message}), starting directly")
            runCatching { startActivity(intent) }
        }
    }
}
