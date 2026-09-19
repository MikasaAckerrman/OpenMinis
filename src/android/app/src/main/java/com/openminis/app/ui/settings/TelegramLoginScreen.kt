package com.openminis.app.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-telegram-login] Telegram-styled login screen. Guides the user through
 * the Pyrogram auth flow: phone → code → (2FA password). Uses the same
 * session file as the telegram MCP (tg_session.session), so logging in
 * here immediately unlocks all tg_* tools.
 */
@Composable
fun TelegramLoginScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as? MinisApp

    // Telegram dark palette
    val bg = Color(0xFF17212B)
    val surface = Color(0xFF232E3C)
    val accent = Color(0xFF2AABEE)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFF708499)

    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var showCreds by remember { mutableStateOf<Boolean?>(null) } // null = not checked yet
    var needsPassword by remember { mutableStateOf(false) }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Check initial status
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder("python3", "/var/minis/mcp-servers/srv/tg-login.py", "status")
                    .redirectErrorStream(true)
                    .start()
                p.inputStream.bufferedReader().readText()
            }.getOrDefault("")
        }
        val json = runCatching { JSONObject(r.trim().lines().lastOrNull { it.startsWith("{") } ?: "{}") }.getOrDefault(JSONObject())
        loggedIn = json.optBoolean("logged_in", false)
        // env_ready = env vars set; has_saved_creds = previously entered via UI.
        // If NEITHER: show the API credentials step first (send-code would
        // fail without them — the "code never arrives" bug).
        showCreds = !(json.optBoolean("env_ready", false) || json.optBoolean("has_saved_creds", false))
    }

    fun runLogin(vararg args: String, onDone: (JSONObject) -> Unit) {
        scope.launch {
            busy = true
            error = null
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val cmd = mutableListOf("python3", "/var/minis/mcp-servers/srv/tg-login.py")
                    cmd.addAll(args)
                    // Pass API creds when the user entered them locally —
                    // the script falls back to its saved state / env vars.
                    if (apiId.isNotBlank() && apiHash.isNotBlank() && args.firstOrNull() != "status") {
                        cmd.addAll(listOf("--api-id", apiId.trim(), "--api-hash", apiHash.trim()))
                    }
                    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().readText()
                }.getOrDefault("")
            }
            busy = false
            val json = runCatching { JSONObject(out.trim().lines().lastOrNull { it.startsWith("{") } ?: "{}") }.getOrDefault(JSONObject())
            if (json.has("error")) {
                error = json.getString("error")
            } else {
                onDone(json)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, tint = textPrimary, contentDescription = "Back")
            }
            Spacer(Modifier.width(8.dp))
            Text("Telegram", color = textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(32.dp))

        // Logo circle
        Box(
            modifier = Modifier.size(80.dp).background(accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✈", color = Color.White, fontSize = 36.sp)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            if (loggedIn == true) "You're logged in" else "Sign in to Telegram",
            color = textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (loggedIn == true)
                "All Telegram MCP tools are active"
            else
                "Your phone number is required for login.\nSession is shared with the telegram MCP.",
            color = textSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(32.dp))

        if (loggedIn == true) {
            // Logged in card
            Surface(
                color = surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Check, tint = accent, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Connected", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Session file active — tg_read, tg_search, tg_list_chats available", color = textSecondary, fontSize = 13.sp)
                    }
                }
            }
        } else if (showCreds == null) {
            // Checking env vars / saved state — show a spinner-free placeholder
            // (LaunchedEffect below fills it instantly).
            TgText("Checking API credentials...", color = subtitleColor)
        } else if (showCreds == true) {
            // [T-tg-login-creds-first] API ID + Hash BEFORE phone — without
            // these, send-code fails silently (no code arrives).
            OutlinedTextField(
                value = apiId,
                onValueChange = { apiId = it },
                label = { Text("API ID", color = textSecondary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = textSecondary.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiHash,
                onValueChange = { apiHash = it },
                label = { Text("API Hash", color = textSecondary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = textSecondary.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Get these from my.telegram.org → API development tools. " +
                "Or set TG_API_ID / TG_API_HASH in Settings → Environment.",
                color = textSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone Number (+7...)", color = textSecondary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = textSecondary.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    runLogin("send-code", phone.trim()) { r ->
                        if (r.optBoolean("code_sent", false)) codeSent = true
                    }
                },
                enabled = !busy && phone.isNotBlank() && apiId.isNotBlank() && apiHash.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Send Code", color = Color.White)
                }
            }
        } else if (!codeSent) {
            // Phone input
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone Number", color = textSecondary) },
                placeholder = { Text("+7 900 123 45 67", color = textSecondary.copy(alpha = 0.5f)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = textSecondary.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { runLogin("send-code", phone.trim()) { codeSent = true } },
                enabled = !busy && phone.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                else { Text("Send Code", fontSize = 16.sp) }
            }
        } else if (!needsPassword) {
            // Code input
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Verification Code", color = textSecondary) },
                placeholder = { Text("12345", color = textSecondary.copy(alpha = 0.5f)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = textSecondary.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { runLogin("verify", phone.trim(), code.trim()) { r ->
                    if (r.optBoolean("needs_password", false)) needsPassword = true else loggedIn = true
                } },
                enabled = !busy && code.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                else { Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Verify", fontSize = 16.sp) }
            }
        } else {
            // 2FA password
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Cloud Password (2FA)", color = textSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = textSecondary.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { runLogin("password", password) { loggedIn = true } },
                enabled = !busy && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                else { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sign In", fontSize = 16.sp) }
            }
        }

        // Error banner
        error?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Surface(
                color = Color(0xFF332025),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    msg,
                    color = Color(0xFFFF6B6B),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}
