package com.openminis.app.debug

import android.content.Context
import com.openminis.app.data.MutationJournal
import com.openminis.app.data.NetworkJournal
import org.json.JSONObject
import java.io.File

/**
 * [T-mutation-journal] Read the always-on journals over RPC so "where did my
 * messages go" and "why did the session stop" can be answered from a shell
 * without pulling files off the device.
 */
internal object MutationJournalMethods {

    /** `chat.journal.read` — tail of the mutation journal. */
    fun read(context: Context, params: JSONObject): JSONObject =
        readJournal(MutationJournal.file(), params)

    /**
     * [T-network-journal] `chat.network.journal` — tail of the network journal.
     *
     * Shares [readJournal] with the mutation journal on purpose: both files have
     * the same TSV shape and the same filter needs, and two copies of the tail
     * logic would drift in exactly the small ways that make a diagnostic
     * untrustworthy (one truncating the session id differently from the other).
     */
    fun readNetwork(context: Context, params: JSONObject): JSONObject =
        readJournal(NetworkJournal.file(), params)

    private fun readJournal(f: File?, params: JSONObject): JSONObject {
        val limit = params.optInt("limit", 200).coerceIn(1, 5000)
        val sessionFilter = params.optString("sessionId", "").takeIf { it.isNotEmpty() }
        val kindFilter = params.optString("kind", "").takeIf { it.isNotEmpty() }?.uppercase()

        if (f == null || !f.exists()) {
            return JSONObject().apply {
                put("exists", false)
                put("path", f?.absolutePath ?: JSONObject.NULL)
                put("count", 0)
                put("lines", org.json.JSONArray())
            }
        }
        // Session ids are journalled truncated to 8 chars, so match on that.
        val shortSession = sessionFilter?.take(8)
        val all = f.readLines().filter { it.isNotBlank() }
        val matched = all.filter { line ->
            (shortSession == null || line.contains("sess=$shortSession")) &&
                (kindFilter == null || line.contains("\t$kindFilter\t"))
        }
        val tail = matched.takeLast(limit)
        val arr = org.json.JSONArray()
        for (line in tail) arr.put(line)
        return JSONObject().apply {
            put("exists", true)
            put("path", f.absolutePath)
            put("sizeBytes", f.length())
            put("totalLines", all.size)
            put("matchedLines", matched.size)
            put("count", arr.length())
            put("lines", arr)
        }
    }
}
