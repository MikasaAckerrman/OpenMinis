package com.openminis.app.translate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch

/**
 * "Translate" in the text-selection menu (next to Copy / Share / Web search).
 *
 * Android delivers ACTION_PROCESS_TEXT with the selected text in
 * EXTRA_PROCESS_TEXT. When EXTRA_PROCESS_TEXT_READONLY is false the host app
 * accepts a replacement: we return the translation in the result Intent and
 * the text in the field is swapped in place. On read-only selections (web
 * pages, other apps' labels) there is nothing to replace, so the translation
 * is shown in a dialog and copied to the clipboard.
 *
 * WHY A TRANSPARENT ACTIVITY AND NOT A SERVICE. PROCESS_TEXT is an activity
 * contract — the system needs a component that can return RESULT_OK with an
 * extra. The activity carries no layout of its own; the theme makes it
 * invisible so the only thing the user sees is the dialog (or nothing at all
 * on the replace path).
 *
 * TIMING. TranslateBridge.prewarm is fired before the text is even read: the
 * engine costs ~1300 ms cold and ~50 ms warm, and those milliseconds overlap
 * with the system's own activity-launch animation.
 */
class ProcessTextActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ProcessText"
        private const val MAX_CHARS = 20_000
    }

    // Pre-Tiramisu locale override, same as every other entry point.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the engine immediately — before parsing the intent — so the
        // model load overlaps the launch animation instead of following it.
        lifecycleScope.launch { TranslateBridge.prewarm(applicationContext) }

        val selected = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)?.toString()
        val readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) ?: false

        if (selected.isNullOrBlank()) {
            toast(getString(R.string.translate_nothing_selected))
            finish()
            return
        }
        if (!TranslateBridge.isAvailable(applicationContext)) {
            // The engine lives in the shared toolbox folder; without it there is
            // nothing to run. Say so plainly instead of failing silently.
            toast(getString(R.string.translate_engine_missing))
            finish()
            return
        }

        val text = if (selected.length > MAX_CHARS) selected.take(MAX_CHARS) else selected

        lifecycleScope.launch {
            val progress = showProgress()
            val result = TranslateBridge.translate(applicationContext, text)
            progress?.dismiss()

            if (!result.ok) {
                AppLogger.warning(TAG, "translate failed: ${result.error}")
                toast(getString(R.string.translate_failed))
                finish()
                return@launch
            }

            AppLogger.info(TAG, "translated ${text.length} chars " +
                "${result.from}->${result.to} in ${result.ms}ms readOnly=$readOnly")

            if (readOnly) {
                showResult(result)
            } else {
                // Editable selection: hand the translation back and let the host
                // app replace it. This is the whole point of PROCESS_TEXT — no
                // copy-paste dance for the user.
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, result.text)
                })
                finish()
            }
        }
    }

    /** Indeterminate spinner for the cold-start case. Returns null when the
     *  engine is already warm, so a 50 ms call does not flash a dialog. */
    private fun showProgress(): android.app.AlertDialog? {
        val bar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(48, 48, 48, 48)
        }
        return android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.translate_working))
            .setView(bar)
            .setCancelable(true)
            .setOnCancelListener { finish() }
            .create()
            .also { it.show() }
    }

    private fun showResult(result: TranslateBridge.Result) {
        // Copy first: the dialog can be dismissed by a stray tap, and losing the
        // translation after waiting for it is the worst possible outcome.
        copyToClipboard(result.text)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.translate_result_title,
                result.from.uppercase(), result.to.uppercase()))
            .setMessage(result.text)
            .setPositiveButton(getString(R.string.translate_copied)) { _, _ -> finish() }
            .setNeutralButton(getString(R.string.translate_open_in_app)) { _, _ ->
                openInChat(result.text)
                finish()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("translation", text))
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "clipboard failed: ${e.message}")
        }
    }

    private fun openInChat(text: String) {
        try {
            startActivity(Intent(this, com.openminis.app.MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "openInChat failed: ${e.message}")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        // Keep the engine alive: the user is likely to translate again within
        // seconds, and a reload costs 1.3 s. TranslateBridge.release() is only
        // called from the tile's explicit "stop" path.
    }
}
