package com.openminis.app.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Управление доступом к Telegram-чатам для MCP-сервера telegram.
 *
 * Читает/пишет два файла (через bind-mount к filesDir/minis-global/mcp-servers/srv/):
 * - tg_whitelist.json — настройки доступа (mode + allowed/blocked chat IDs)
 * - tg_chats_cache.json — кэш чатов от агента (read-only для UI)
 *
 * Не трогает MCPRepository (servers.json) и telegram.py.
 */
class TelegramWhitelistRepository(context: Context) {

    data class WhitelistConfig(
        val mode: String = "whitelist",
        val allowed: List<Long> = emptyList(),
        val blocked: List<Long> = emptyList(),
    )

    data class ChatInfo(
        val id: Long,
        val title: String,
        val type: String,
        val username: String?,
    )

    private val srvDir: File = File(context.filesDir, "minis-global/mcp-servers/srv")
    private val whitelistFile = File(srvDir, "tg_whitelist.json")
    private val chatsCacheFile = File(srvDir, "tg_chats_cache.json")

    private val _config = MutableStateFlow(WhitelistConfig())
    val config: StateFlow<WhitelistConfig> = _config.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatInfo>>(emptyList())
    val chats: StateFlow<List<ChatInfo>> = _chats.asStateFlow()

    init {
        reload()
    }

    /** Перечитать оба файла с диска. */
    fun reload() {
        loadConfig()
        loadChats()
    }

    private fun loadConfig() {
        if (!whitelistFile.exists()) {
            _config.value = WhitelistConfig()
            return
        }
        try {
            val text = whitelistFile.readText()
            val json = JSONObject(text)
            val mode = json.optString("mode", "whitelist")
            val allowed = json.optJSONArray("allowed")?.toLongList() ?: emptyList()
            val blocked = json.optJSONArray("blocked")?.toLongList() ?: emptyList()
            _config.value = WhitelistConfig(mode, allowed, blocked)
        } catch (e: Exception) {
            _config.value = WhitelistConfig()
        }
    }

    private fun loadChats() {
        if (!chatsCacheFile.exists()) {
            _chats.value = emptyList()
            return
        }
        try {
            val text = chatsCacheFile.readText()
            val json = JSONObject(text)
            val arr = json.optJSONArray("chats") ?: JSONArray()
            val list = mutableListOf<ChatInfo>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                list.add(ChatInfo(
                    id = c.optLong("id"),
                    title = c.optString("title", "Без названия"),
                    type = c.optString("type", ""),
                    username = c.optString("username").ifBlank { null },
                ))
            }
            _chats.value = list
        } catch (e: Exception) {
            _chats.value = emptyList()
        }
    }

    /** Установить режим (whitelist | blacklist) и сохранить. */
    fun setMode(mode: String) {
        val current = _config.value
        _config.value = current.copy(mode = mode)
        saveConfig()
    }

    /**
     * Добавить/убрать chat_id из allowed (whitelist) или blocked (blacklist).
     * В whitelist режиме: enabled=true → добавить в allowed, false → убрать.
     * В blacklist режиме: enabled=true → добавить в blocked, false → убрать.
     */
    fun toggleChat(chatId: Long, enabled: Boolean) {
        val current = _config.value
        val newList = if (current.mode == "whitelist") {
            if (enabled) current.allowed + chatId else current.allowed - chatId
        } else {
            if (enabled) current.blocked + chatId else current.blocked - chatId
        }
        _config.value = if (current.mode == "whitelist") {
            current.copy(allowed = newList)
        } else {
            current.copy(blocked = newList)
        }
        saveConfig()
    }

    /** Проверить, включён ли чат в текущем режиме. */
    fun isChatEnabled(chatId: Long): Boolean {
        val c = _config.value
        return if (c.mode == "whitelist") chatId in c.allowed else chatId in c.blocked
    }

    private fun saveConfig() {
        try {
            srvDir.mkdirs()
            val json = JSONObject()
            json.put("mode", _config.value.mode)
            json.put("allowed", JSONArray(_config.value.allowed))
            json.put("blocked", JSONArray(_config.value.blocked))
            val tmp = File(srvDir, "tg_whitelist.json.tmp")
            tmp.writeText(json.toString(2))
            tmp.renameTo(whitelistFile)
        } catch (e: Exception) {
            // Молча: не крашим UI при ошибке записи
        }
    }

    private fun JSONArray.toLongList(): List<Long> {
        val out = mutableListOf<Long>()
        for (i in 0 until length()) out.add(getLong(i))
        return out
    }
}
