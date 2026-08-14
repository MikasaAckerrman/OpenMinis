package com.openminis.app.translate

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch

/**
 * Translate whatever is in the clipboard — the target of the quick-settings
 * tile and of the home-screen shortcut.
 *
 * WHY THE CLIPBOARD AND NOT THE SCREEN. Reading the screen needs the
 * accessibility service, which returns a flat list of every label on it
 * (headers, timestamps, fragments) with no structure; the translation is only
 * as good as that dump. The clipboard is what the user chose deliberately, so
 * one tap gives a predictable result. Screen translation stays available
 * through the CLI, where the caller can filter the dump first.
 *
 * CLIPBOARD ACCESS ON ANDROID 10+. Only the foreground app may read the
 * primary clip. That is exactly why this is an Activity and not a Service:
 * a tile handler alone cannot read it, so the tile starts this activity, the
 * activity comes to the foreground, reads the clip, and finishes.
 */
class TranslateClipboardActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TranslateClip"
        private const val MAX_CHARS = 20_000
        const val ACTION = "com.openminis.app.action.TRANSLATE_CLIPBOARD"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { TranslateBridge.prewarm(applicationContext) }

        if (!TranslateBridge.isAvailable(applicationContext)) {
            toast(getString(R.string.translate_engine_missing))
            finish()
            return
        }

        val clip = readClipboard()
        if (clip.isNullOrBlank()) {
            toast(getString(R.string.translate_clipboard_empty))
            finish()
            return
        }

        val text = if (clip.length > MAX_CHARS) clip.take(MAX_CHARS) else clip

        lifecycleScope.launch {
            val progress = android.app.AlertDialog.Builder(this@TranslateClipboardActivity)
                .setTitle(getString(R.string.translate_working))
                .setView(android.widget.ProgressBar(this@TranslateClipboardActivity).apply {
                    isIndeterminate = true
                    setPadding(48, 48, 48, 48)
                })
                .setCancelable(true)
                .setOnCancelListener { finish() }
                .create()
                .also { it.show() }

            val result = TranslateBridge.translate(applicationContext, text)
            progress.dismiss()

            if (!result.ok) {
                AppLogger.warning(TAG, "translate failed: ${result.error}")
                toast(getString(R.string.translate_failed))
                finish()
                return@launch
            }

            // Write the translation back to the clipboard before showing it:
            // the user's next action is almost always a paste.
            writeClipboard(result.text)
            AppLogger.info(TAG, "clipboard ${text.length} chars " +
                "${result.from}->${result.to} in ${result.ms}ms")

            android.app.AlertDialog.Builder(this@TranslateClipboardActivity)
                .setTitle(getString(R.string.translate_result_title,
                    result.from.uppercase(), result.to.uppercase()))
                .setMessage(result.text)
                .setPositiveButton(getString(R.string.translate_copied)) { _, _ -> finish() }
                .setNeutralButton(getString(R.string.translate_open_in_app)) { _, _ ->
                    startActivity(Intent(this@TranslateClipboardActivity,
                        com.openminis.app.MainActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, result.text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    finish()
                }
                .setOnDismissListener { finish() }
                .show()
        }
    }

    private fun readClipboard(): String? = try {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) null
        else clip.getItemAt(0).coerceToText(this)?.toString()
    } catch (e: Throwable) {
        AppLogger.warning(TAG, "clipboard read failed: ${e.message}")
        null
    }

    private fun writeClipboard(text: String) {
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("translation", text))
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "clipboard write failed: ${e.message}")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
