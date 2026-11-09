package com.openminis.app.ui.settings

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import java.io.File
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.openminis.app.MinisApp
import com.openminis.app.sandbox.TelegramAccountStore
import com.openminis.app.sandbox.TgAccount
import com.openminis.app.sandbox.TelegramLoginRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-tg-login-host] The driver emits exactly one `RESULT {json}` line on
 * stdout (library chatter — the TgCrypto speed hint, usage text — shares the
 * merged stream). Parsing must anchor on the RESULT prefix: a plain '{' scan
 * can pick up unrelated lines, and the old `{`-last-line parse NEVER matched
 * the driver's real output at all.
 */
private fun driverJson(output: String): JSONObject = runCatching {
    val line = output.trim().lines().lastOrNull { it.contains("RESULT ") } ?: ""
    JSONObject(line.substringAfter("RESULT ", line))
}.getOrDefault(JSONObject())

/**
 * [B17-C] Mask a phone number for display: keep the country prefix and the
 * last four digits, mask the rest — the same discipline the account card
 * applies when several accounts are on screen. Degenerate inputs degrade
 * to a full mask.
 */
internal fun maskTgPhone(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    // Below 7 digits a partial mask would reveal most of the number —
    // degrade to a full mask instead.
    if (digits.length <= 6) return "***"
    val prefix = phone.trim().take(2)
    return "$prefix *** ***-${digits.takeLast(4)}"
}

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

    // Telegram dark palette — matte Grok/TG style (ROADMAP_MEMORY §И-style)
    val bg = Color(0xFF0D0F12)
    val surface = Color(0xFF16191E)
    val border = Color(0xFF262A33)
    val accent = Color(0xFF2AABEE)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFF8B95A5)

    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    // [T-telegram-login-state] The sign-in state is kept on disk, so a
    // rebuilt screen or a killed process does not throw away the phone
    // number, the API credentials and - the part that expires in minutes -
    // the verification code.
    val loginStore = remember(context) {
        TelegramLoginStateStore(
            // [tg-login-vault] The sign-in state lives in the same isolated
            // directory as the Telegram session: filesDir/tg-vault is not
            // bind-mounted into any agent sandbox, so no agent process can
            // read the phone number, the code or the api credentials.
            // [B4] The state is ENCRYPTED at rest now (AES-GCM-256,
            // AndroidKeyStore): the plaintext login-state.json is migrated
            // into login-state.enc and deleted on the first open.
            File(File(context.filesDir, "tg-vault"), "login-state.enc"),
        ).also { store ->
            store.migrateLegacyIfNeeded(File(File(context.filesDir, "tg-vault"), "login-state.json"))
        }
    }
    val savedState = remember(loginStore) { loginStore.load() }
    var phone by remember { mutableStateOf(savedState.phone) }
    var code by remember { mutableStateOf(savedState.code) }
    // The cloud password is the account's second factor and is typed once;
    // it is deliberately not kept on disk.
    var password by remember { mutableStateOf("") }
    var apiId by remember { mutableStateOf(savedState.apiId) }
    var apiHash by remember { mutableStateOf(savedState.apiHash) }
    var showCreds by remember { mutableStateOf<Boolean?>(null) } // null = not checked yet
    var needsPassword by remember { mutableStateOf(savedState.needsPassword) }
    var codeSent by remember { mutableStateOf(savedState.codeSent) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // [T-tg-accounts] The account cards (B17 stage C UI). Declared BEFORE
    // the effects: the loggedIn effect refreshes the cards after sign-in.
    val vaultDir = remember { File(context.filesDir, "tg-vault") }
    val accountStore = remember { TelegramAccountStore(vaultDir) }
    var accounts by remember { mutableStateOf<List<TgAccount>>(emptyList()) }
    var addingAccount by remember { mutableStateOf(false) }
    // [B17-C] Gateway status pill + the user-only unlink flow (agents have
    // NO tool that removes accounts — this dialog is the only door).
    var gatewayAlive by remember { mutableStateOf<Boolean?>(null) }
    var unlinkTarget by remember { mutableStateOf<TgAccount?>(null) }
    fun reloadAccounts() { accounts = accountStore.load() }
    LaunchedEffect(Unit) {
        reloadAccounts()
        val alive = withContext(Dispatchers.IO) {
            com.openminis.app.sandbox.TelegramVaultManager.isAlive(context)
        }
        gatewayAlive = alive
        // [B42-task4] The migrated "pending" account renders as "Account / ?"
        // until SOMETHING fills the registry: the vault's tg_whoami writes
        // the DAEMON's accounts.json, not this app's registry — the bridge
        // is here. One call per screen open, best-effort, then re-read.
        if (alive) {
            withContext(Dispatchers.IO) {
                com.openminis.app.sandbox.TelegramProfileSync.syncActiveProfile(context)
            }
            reloadAccounts()
        }
    }

    // [T-telegram-login-state] Keep the flow on disk as it advances: the
    // verification code is only valid for a few minutes, so losing it to a
    // rotation or a process kill means starting the login over.
    // One key holding the whole set: any change of any field re-runs it.
    LaunchedEffect(listOf(phone, code, apiId, apiHash, codeSent, needsPassword, loggedIn)) {
        // A finished sign-in leaves nothing behind, and the clearing effect
        // below runs on its own key. Without this guard a later change of a
        // field would write the phone and the code back onto the disk.
        if (loggedIn == true) return@LaunchedEffect
        loginStore.save(
            TelegramLoginState(
                phone = phone,
                code = code,
                apiId = apiId,
                apiHash = apiHash,
                codeSent = codeSent,
                needsPassword = needsPassword,
            ),
        )
    }

    // [T-telegram-login-state] A finished sign-in leaves nothing behind.
    LaunchedEffect(loggedIn) {
        if (loggedIn == true) {
            loginStore.clear()
            // [M-R3.2] The vault server must see the credential it never
            // had at boot: the Rust daemon fixes its session state at start
            // and restarts here (the python path is unchanged — its file
            // was swept right after login).
            withContext(Dispatchers.IO) {
                com.openminis.app.sandbox.TelegramVaultManager.onLoginCompleted(context)
            }
            // [T-tg-accounts] A new account just signed in: refresh the cards
            // (the profile fills via tg_whoami on the server side).
            addingAccount = false
            reloadAccounts()
        }
    }

    // Check initial status
    LaunchedEffect(Unit) {
        // [T-tg-login-host] The driver runs in a host-owned PRoot instance
        // (TelegramLoginRunner) — the app process itself has no python3 and
        // no /var/minis, so a bare ProcessBuilder here could never work.
        // [M-R3.2] Under the Rust flag the status is composed Kotlin-side
        // (MinisdLoginRunner): the python driver would report the pyrogram
        // session, which the grammers daemon cannot read.
        val isRust = com.openminis.app.sandbox.TelegramVaultManager
            .activeVaultImpl(vaultDir) == com.openminis.app.sandbox.TelegramVaultManager.IMPL_RUST
        val r = withContext(Dispatchers.IO) {
            if (isRust) {
                com.openminis.app.sandbox.MinisdLoginRunner.run(context, listOf("status"), timeoutSec = 15).output
            } else {
                TelegramLoginRunner.run(context, listOf("status"), timeoutSec = 30).output
            }
        }
        val json = driverJson(r)
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
            val argList = mutableListOf<String>()
            // [T-tg-login-host] --api-id/--api-hash are TOP-LEVEL argparse
            // options: they must precede the subcommand or argparse rejects
            // the whole line (exit 2). The driver falls back to its env /
            // saved state when the flags are absent.
            if (apiId.isNotBlank() && apiHash.isNotBlank() && args.firstOrNull() != "status") {
                argList.addAll(listOf("--api-id", apiId.trim(), "--api-hash", apiHash.trim()))
            }
            argList.addAll(args)
            val result = withContext(Dispatchers.IO) {
                // [M-R3.2] The vault_impl flag picks the login transport:
                // rust drives the interactive `minisd login` session
                // (grammers), python keeps the pyrogram driver. Both speak
                // the same RESULT {json} step contract.
                if (com.openminis.app.sandbox.TelegramVaultManager
                        .activeVaultImpl(vaultDir) == com.openminis.app.sandbox.TelegramVaultManager.IMPL_RUST
                ) {
                    com.openminis.app.sandbox.MinisdLoginRunner.run(
                        context,
                        argList,
                        apiId = apiId.trim().takeIf { apiId.isNotBlank() },
                        apiHash = apiHash.trim().takeIf { apiHash.isNotBlank() },
                    )
                } else {
                    val r = TelegramLoginRunner.run(
                        context,
                        argList,
                        apiId = apiId.trim().takeIf { apiId.isNotBlank() },
                        apiHash = apiHash.trim().takeIf { apiHash.isNotBlank() },
                    )
                    // The session Pyrogram may have written agent-side is
                    // moved into the vault right away; a no-op when nothing
                    // appeared.
                    TelegramLoginRunner.sweepSessionIntoVault(context)
                    r
                }
            }
            busy = false
            val json = driverJson(result.output)
            when {
                json.has("error") -> error = json.getString("error")
                // [T-tg-login-host] No JSON verdict at all means the driver
                // never answered (crash, missing rootfs). Surface it — the
                // old silent swallow here was the dead-button bug.
                json.length() == 0 && result.exitCode != 0 ->
                    error = "Driver failed (exit ${result.exitCode}): " +
                        result.output.trim().take(200).ifBlank { "no output" }
                else -> onDone(json)
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

        // [B17-C] Gateway status pill: the MCP tools ride the local vault
        // server on 127.0.0.1:7824 — the user should see whether it is up.
        gatewayAlive?.let { alive ->
            Surface(
                color = surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (alive) accent else textSecondary, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Local gateway 127.0.0.1:7824 — " + if (alive) "active" else "not running",
                        color = if (alive) textPrimary else textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // [T-tg-accounts] Account cards (B17 stage C): every registered
        // account with an avatar (photo or initials), the name bold, the
        // @username and the MASKED phone secondary, the exclusive active
        // radio and the user-only unlink button. Deleting an account is
        // deliberately NOT offered to agents — only the user decides that
        // here, behind a confirm dialog.
        //
        // [B44-multiaccount] The list is DRAG-REORDERABLE (long-press and
        // pull — the top position = search priority 1, persisted as
        // explicit priorities on drop via applyOrder) and every account
        // carries the participation SWITCH (off = skipped by the search
        // routing, not unlinked). Disabled cards dim; each enabled card
        // shows its 1-based search position.
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Долгий тап + тянуть — порядок задаёт приоритет поиска. Тумблер выключает аккаунт из поиска.",
                color = textSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            var visualList by remember(accounts) { mutableStateOf(accounts) }
            var dragDirName by remember { mutableStateOf<String?>(null) }
            var dragOffsetY by remember { mutableStateOf(0f) }
            val itemHeights = remember { mutableStateMapOf<String, Int>() }

            fun endDragAndPersist() {
                if (dragDirName != null) {
                    accountStore.applyOrder(visualList.map { it.dirName })
                    accounts = accountStore.load()
                    visualList = accounts
                }
                dragDirName = null
                dragOffsetY = 0f
            }

            visualList.forEach { acc ->
                val isDragging = dragDirName == acc.dirName
                val disabled = !acc.isEnabled
                Surface(
                    color = surface,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                        .alpha(if (disabled) 0.45f else 1f)
                        .onSizeChanged { itemHeights[acc.dirName] = it.height }
                        .pointerInput(acc.dirName) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragDirName = acc.dirName
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (dragDirName == acc.dirName) {
                                        dragOffsetY += amount.y
                                        // Threshold-swap: crossing half the
                                        // neighbour's height swaps the dragged
                                        // account one position and re-bases the
                                        // offset, so the pull continues smoothly
                                        // past any number of neighbours.
                                        val list = visualList.toMutableList()
                                        var cur = list.indexOfFirst { it.dirName == acc.dirName }
                                        while (true) {
                                            if (cur < list.lastIndex &&
                                                dragOffsetY > (itemHeights[list[cur + 1].dirName] ?: 200) / 2f
                                            ) {
                                                val above = list[cur]
                                                list[cur] = list[cur + 1]
                                                list[cur + 1] = above
                                                dragOffsetY -= (itemHeights[list[cur].dirName] ?: 200)
                                                cur++
                                            } else if (cur > 0 &&
                                                dragOffsetY < -(itemHeights[list[cur - 1].dirName] ?: 200) / 2f
                                            ) {
                                                val below = list[cur]
                                                list[cur] = list[cur - 1]
                                                list[cur - 1] = below
                                                dragOffsetY += (itemHeights[list[cur].dirName] ?: 200)
                                                cur--
                                            } else break
                                        }
                                        visualList = list
                                    }
                                },
                                onDragEnd = { endDragAndPersist() },
                                onDragCancel = { endDragAndPersist() },
                            )
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val avatarBitmap = remember(acc.avatarPath) {
                            acc.avatarPath?.takeIf { it.isNotBlank() }
                                ?.let { path ->
                                    runCatching {
                                        android.graphics.BitmapFactory.decodeFile(path)
                                    }.getOrNull()
                                }
                        }
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(if (acc.isActive) accent else border, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    (acc.firstName?.take(1) ?: acc.username?.take(1) ?: "?").uppercase(),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                acc.firstName ?: acc.username?.let { "@$it" } ?: "Account",
                                color = textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (acc.firstName != null && !acc.username.isNullOrBlank()) {
                                Text("@${acc.username}", color = textSecondary, fontSize = 12.sp)
                            }
                            if (!acc.phone.isNullOrBlank()) {
                                Text(maskTgPhone(acc.phone), color = textSecondary, fontSize = 12.sp)
                            }
                            // [B44-multiaccount] The 1-based position among
                            // ENABLED accounts — what the search routing
                            // actually walks.
                            if (acc.isEnabled) {
                                val searchPos = visualList.filter { it.isEnabled }.indexOf(acc) + 1
                                if (searchPos > 0) {
                                    Text(
                                        "поиск: №$searchPos",
                                        color = accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            } else {
                                Text("выключен из поиска", color = textSecondary, fontSize = 10.sp)
                            }
                        }
                        // [B44-multiaccount] Participation toggle.
                        Switch(
                            checked = acc.isEnabled,
                            onCheckedChange = { on ->
                                accountStore.setEnabled(acc.dirName, on)
                                accounts = accountStore.load()
                                visualList = accounts
                            },
                        )
                        IconButton(onClick = { unlinkTarget = acc }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Unlink account",
                                tint = textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        RadioButton(
                            selected = acc.isActive,
                            onClick = {
                                accountStore.setActive(acc.dirName)
                                accounts = accountStore.load()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

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
        } else if (!addingAccount && accounts.isNotEmpty()) {
            // [T-tg-accounts] Accounts are managed above (cards + radio):
            // offer adding another one without repeating the login flow.
            Button(
                onClick = { addingAccount = true; loggedIn = null },
                colors = ButtonDefaults.buttonColors(containerColor = surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, border),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("+ Add account", color = textPrimary) }
        } else if (showCreds == null) {
            // Checking env vars / saved state — show a spinner-free placeholder
            // (LaunchedEffect below fills it instantly).
            Text("Checking API credentials...", color = textSecondary.copy(alpha = 0.6f), fontSize = 14.sp)
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
                    unfocusedBorderColor = border,
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
                    unfocusedBorderColor = border,
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
                    unfocusedBorderColor = border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    runLogin("send-code", "--phone", phone.trim()) { r ->
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
                    unfocusedBorderColor = border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { runLogin("send-code", "--phone", phone.trim()) { codeSent = true } },
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
                    unfocusedBorderColor = border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { runLogin("verify", "--code", code.trim()) { r ->
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
                    unfocusedBorderColor = border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                // [T-tg-login-host] The driver has NO 'password' subcommand:
                // 2FA completes through verify --code --password (the phone
                // code hash stays pending in the driver state until then).
                onClick = { runLogin("verify", "--code", code.trim(), "--password", password) { loggedIn = true } },
                enabled = !busy && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                else { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sign In", fontSize = 16.sp) }
            }
        }

        // [B17-C] Unlink confirmation — the ONLY account-removal door, and
        // it belongs to the user alone (no agent tool reaches this).
        unlinkTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { unlinkTarget = null },
                title = {
                    Text("Unlink account?", color = textPrimary, fontWeight = FontWeight.SemiBold)
                },
                text = {
                    Text(
                        "The session files for " +
                            (target.firstName ?: target.username?.let { "@$it" } ?: target.dirName) +
                            " will be removed from this device. The Telegram account itself " +
                            "is not deleted — you can sign in again any time.",
                        color = textSecondary,
                        fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        accountStore.remove(target.dirName)
                        unlinkTarget = null
                        reloadAccounts()
                    }) {
                        Text("Unlink", color = Color(0xFFFF6B6B))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { unlinkTarget = null }) {
                        Text("Cancel", color = textPrimary)
                    }
                },
                containerColor = surface,
            )
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
