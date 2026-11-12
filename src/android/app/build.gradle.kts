import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Build-time customization values that must NOT ship in the public
// open-source mirror. `provider-customization.properties` is tracked in the PRIVATE
// MinisApp repo (with real values) and listed under `private:` in
// PUBLISH_MANIFEST.yml so it is never synced; the public repo ships only
// `provider-customization.properties.example` (empty values). A build without a
// configured value compiles fine but fails at runtime the first time the
// value is required (see ClaudeOAuthManager). See docs/PUBLISH_POLICY.md.
val appCustomization = Properties().apply {
    val f = rootProject.file("app/provider-customization.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun customizationValue(key: String): String =
    (appCustomization.getProperty(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "com.openminis.app"
    // [T-android-dynamic-island] Bumped 35→36 so the Android 16 (Baklava)
    // Live Updates APIs — Notification.ProgressStyle, FLAG_PROMOTED_ONGOING,
    // NotificationManager.canPostPromotedNotifications(), setShortCriticalText —
    // are available to compile against. targetSdk stays 35 to avoid pulling in
    // Android 16 behavior changes; the Live Updates path is runtime-gated on
    // Build.VERSION.SDK_INT >= 36 (see DynamicIslandSupport / AgentForegroundService).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openminis.app"
        minSdk = 26
        targetSdk = 35
        // [T-vc56] Jump (not increment) over the installed base: the user's
        // device runs the vc55 clone APK (built before f71cac0 reverted the
        // bump), and Android refuses to install vc54 over vc55. 56 clears it.
        // vc149 STABLE: stage-complete release build (B15 + B12 + И3 + TaskDedup +
        // B17-A/B/C + B20 DeviceGuard/DynamicGate + B21 + B22 + B23 KaTeX + B18 SessionMutex).
        // vc152: B34/B35/B36 subagent overhaul (ZCode-style cards, early results,
        // watchdog 90s/stall 30s) — release build.
        // vc153: B40 no-legacy-degrade/no-auto-provider-switch + B34-UI
        // SubagentSessionView + reviewer verdict contract (2c511e13 + 32cc7f71).
        // vc154: Run Inspector polish (e5da0865) + five thinking tiers +
        // native minisd grep/glob + PIP subagent capsule (8ca2685f..e9c13805).
        // vc156: SearchTools path resolver fix (rg exit 2 resolved,
        // 6a10047e..4b56eec2, Unit Tests 42509488780 green).
        // vc157: B43 wave (per-tool streaming + background rescue + surgery
        // mapping) + B44 multi-account UI + M-R3.1 daemon wiring (vault_impl
        // flag) + M-R3.2 grammers login bridge + B43-race-fix
        // (joinSettleWatchers). 8052193e..b91b28b5, Unit Tests 42668539637.
        // vc158: B45 wave — rg stderr deadlock, honest spawn_many parallelism
        // (dedup off siblings + bare files), shared parent workspace, force
        // retry (15s takeover), rust status heuristic. b91b28b5..c69a45eb,
        // Unit Tests 42683913044.
        // vc159: B45-stall-pulse (touch wired to the engine collect + tool
        // heartbeat, TTFT rule, 60s threshold) + flake killed by
        // construction (settle from the async body; FULL test log format).
        // c69a45eb..7ae25e92, Unit Tests 42697522885.
        // vc160: B46-plan-sheet ships in the APK — top-bar plan chip
        // [n/total] + PlanSheet bottom sheet, reactive via
        // TodoStore.observe. 7ae25e92..7ec22019, Unit Tests 42704091253.
        // vc161: M-R3.3 — grep/glob route to the minisd daemon over
        // loopback RPC (no rg fork), --workspace + lazy restart on session
        // switch, agent shell cwd = /var/minis/workspace.
        // 7ec22019..50c4f5dd, Unit Tests 42864018744.
        // vc162: B47 — shell_execute declares its file surface (the false
        // global barrier is gone; independent commands parallel under the
        // CPU gate), split-leader cards never vanish on mid-run inject.
        // 50c4f5dd..db1d6fa9, Unit Tests 42885836735.
        // vc163: B48 — worker teardown (proot+VM cache+tree delete), session-scoped
        // shell DAG keys, thermal concurrency clamps, daily DB VACUUM.
        // 2df8754a..2b43812f, Unit Tests 42914733806.
        // vc164: B49 — worker parallel discipline (the spawn_many ping-pong:
        // standalone workers never saw PARALLEL_TOOL_DISCIPLINE), spawn_many
        // timeout argument (clamp 30-180s, default 90 intact), daily orphan
        // sweep for the pre-B48 minis-sessions leftovers (1550 on vc163,
        // creation-window-guarded, SafeDir-listed).
        // fa556510..27adbaba, Unit Tests 43073335984.
        // vc165: B50 — SpawnGate device-truth lines: one admission-chain line
        // per spawn_many batch (cpuCeiling, llmStreams, pacing) + one
        // slot-acquire stamp per child, so slot serialization is provable
        // on-device (app concurrent vs relay sequential).
        // 27adbaba..8f07b060, Unit Tests 43092106195.
        // vc166: B50 — the SpawnGate batch line carries the raw thermal
        // status next to the ceiling (vc165 device truth: ceiling 2 came
        // from thermal MODERATE; the line now explains its own why).
        // 857a49f0..6d86dcaf, Unit Tests 43102406691.
        // vc167: B51 — the freeze class closed: D1 (timeout releases the
        // ephemeral worker sessions under NonCancellable — no more zombie
        // proot pairs) + PRootGate (worker shells capped at 2, SEVERE → 1,
        // main/vault bypass). Reviewed line-by-line in the worktree, PASS.
        // b974a142..f3138495, Unit Tests 43263144138.
        versionCode = 167
        // vc69: 97 commits — kotlincheck infra + strict preflight (types/enums,
        // parallel-batch gate) + atomic tool budget + budget footer + symmetric
        // supermemory lifecycle (FPS guard) + thermal telemetry + review fixes
        // (graph_id enum regression, B1 completion-stop, parallel holes).
        versionName = "1.23-preview"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // [T-build-tracking] Build identity for DebugRPCHandler / about screen,
        // so the agent knows which tree/commit/branch it runs on without
        // manual investigation.
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "local"}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${System.getenv("GITHUB_REF_NAME") ?: "local"}\"")
        buildConfigField("String", "CI_RUN_ID", "\"${System.getenv("GITHUB_RUN_ID") ?: "local"}\"")
        buildConfigField("String", "BUILD_DATE", "\"${System.getenv("BUILD_DATE") ?: "local"}\"")

        // System prompt prefix required by Anthropic for Claude Code OAuth
        // credentials. Empty in the public mirror (see provider-customization.properties).
        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            "\"${customizationValue("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")}\""
        )

        ndk {
            // [И8] Universal ABI: arm64 for the phone, x86_64 for the
            // Waydroid PC stand. librg.so stays arm64-only (prebuilt) —
            // native_search degrades on the PC stand, documented in И8.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // [T-stable-debug-signing] Pin the debug signing key.
    //
    // AGP otherwise auto-generates a debug keystore under ~/.android, which is
    // a FRESH key on every CI runner. Two consecutive CI builds are then signed
    // by different keys, and Android refuses to install one over the other
    // ("App not installed") — the only way through is uninstalling first, which
    // wipes every chat, provider and setting.
    //
    // A committed debug key makes CI builds upgrade in place, and lets you roll
    // BACK to an older build too (same key, so `adb install -r` is accepted).
    // It signs debug builds only and confers no privilege: the password is the
    // well-known Android debug default and the certificate is self-signed.
    signingConfigs {
        getByName("debug") {
            // rootProject is src/android, so the app module is one level down.
            val shared = rootProject.file("app/keystore/minis-debug.keystore")
            if (shared.exists()) {
                storeFile = shared
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                logger.lifecycle("[signing] debug key: ${shared.absolutePath}")
            } else {
                // Fail loudly rather than silently falling back to a per-machine
                // key: a silent fallback is exactly the bug this block fixes, and
                // it only shows up later as "App not installed" plus data loss.
                logger.error(
                    "[signing] MISSING ${shared.absolutePath} — this build will use " +
                        "AGP's per-machine debug key and will NOT install over a CI build."
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }

        // [T-clone-variant] A side-by-side install for testing.
        //
        // Different applicationId means Android treats it as a separate app:
        // own data directory, own icon, installable ALONGSIDE the primary one.
        // That is what lets a new build be exercised without touching the
        // install that holds real chats — the alternative was uninstalling,
        // which is how data got lost in the first place.
        //
        // initWith(debug) inherits debuggable, so the RPC server and `run-as`
        // work here too, and the shared signing key applies.
        create("clone") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".clone"
            versionNameSuffix = "-clone"
            // BuildConfig.DEBUG is false for any buildType other than `debug`,
            // even one that inherits from it — AGP derives it from the type
            // NAME. The RPC server (127.0.0.1:5321) and the minis-debug offload
            // handler are gated on BuildConfig.DEBUG, so without this the clone
            // would install fine and then be untestable: no agent.graph.* calls.
            // isDebuggable stays true via initWith, so `run-as` works either way.
            isDebuggable = true
            buildConfigField("boolean", "DEBUG", "true")
            // The launcher label comes from src/clone/res/values/strings.xml,
            // NOT resValue: app_name already exists in src/main/res, and
            // resValue would collide with it ("duplicate resource").
            // matchingFallbacks: this buildType is not declared by any library
            // dependency, so consumers must fall back to debug.
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tar.gz", "proot-aarch64")
    }

    // [T-clone-variant] The clone shares debug's generated assets.
    //
    // gen_debug_skill_android.sh writes into src/debug/assets/debug-skill, and a
    // buildType source set is named after the type — so `clone` would not see it
    // and GET /skill would 404 despite the server running. Pointing clone at the
    // same directory keeps ONE generated copy instead of duplicating the script's
    // output per variant.
    sourceSets {
        getByName("clone") {
            assets.srcDirs("src/debug/assets")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // [test-hardening] Robolectric needs the merged resources/assets to
        // resolve Compose contexts on the JVM; heap headroom for the
        // concurrency stress tests (bounded LiveTurnBuffer storm etc.).
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }

    lint {
        // AGP 8.x ships a NonNullableMutableLiveData detector that throws
        // IncompatibleClassChangeError on Kotlin source during
        // lintVitalAnalyzeRelease, regardless of whether the project uses
        // LiveData (this project does not). The crash happens in the
        // detector's dispatch phase — before the configured `disable` list
        // is consulted — so disabling the rule alone is not enough.
        // checkReleaseBuilds=false skips lintVitalAnalyzeRelease entirely;
        // debug-build lint coverage is unaffected. Re-enable once AGP ships
        // a fixed detector (tracked at issuetracker.google.com/388538014).
        checkReleaseBuilds = false
        disable += "NonNullableMutableLiveData"
    }
}

// [T-bash-on-demand] Keep the shared bashism rule table / test vectors as a
// SINGLE source of truth (src/shared/bashism) — copy into assets at build
// time instead of committing duplicate JSON. iOS references the same files as
// bundle resources. Runs before every asset merge so debug/release stay fresh.
val copyBashismRules by tasks.registering(Copy::class) {
    from(rootProject.file("../shared/bashism")) {
        include("bashism_rules.json", "bashism_test_vectors.json")
    }
    into(layout.projectDirectory.dir("src/main/assets/bashism"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyBashismRules) }
tasks.named("preBuild") { dependsOn(copyBashismRules) }

// [T-android-debugserver-skill] Stage the debug-server skill + an Android
// reference client into the DEBUG-ONLY asset source set, so the debug server
// can serve them over GET /skill (mirrors the iOS "Generate Debug Skill" build
// phase). Single source of truth stays .claude/skills/debug-server/.
//
// Wired to DEBUG asset merges only: src/debug/assets never reaches a release
// APK, so the tooling docs can't ship to users. `assets` is also declared as an
// output so Gradle re-runs this when the skill changes but skips it otherwise.
val stageDebugSkillAssets by tasks.registering(Exec::class) {
    val script = rootProject.file("../../scripts/gen_debug_skill_android.sh")
    onlyIf { script.exists() }
    inputs.file(script).optional()
    outputs.dir(layout.projectDirectory.dir("src/debug/assets/debug-skill"))
    commandLine("bash", script.absolutePath)
}
// [T-clone-variant] Also wire the `clone` variant: it carries the debug server
// (BuildConfig.DEBUG forced true), so GET /skill must have something to serve.
// Release variants stay excluded — that is the point of the src/debug source set.
tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("Assets") &&
        (it.name.contains("Debug") || it.name.contains("Clone"))
}.configureEach { dependsOn(stageDebugSkillAssets) }

// [B45-flake-hunt] The CI console uses gradle's SHORT test-logging format,
// which drops assertion MESSAGES — a red run shows only
// "java.lang.AssertionError at File.kt:123" and the diagnosis needs a
// full local rebuild we forbid. FULL keeps the message (and the events
// dump the self-diagnosing tests embed) in the console where the CI
// round actually reads it.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // [T-m13-fts-bundle] Bundled SQLite WITH FTS5 (Apache-2.0, requery; probe: bindEngine reads through the virtual table):
    // the vivo SM8650 platform SQLite is compiled WITHOUT the fts5 module
    // (vc86 crash-loop) — this lib provides the same engine family
    // in-process; arm64-only via existing abiFilters, ~1.8MB.
    implementation("com.github.requery:sqlite-android:3.49.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // [T-post-update-jank] Baseline-profile installer: compiles the
    // baseline-prof.txt classes AOT on first boot after an update instead
    // of leaving the whole app in the interpret-only window.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner — used by XAIOAuthManager to detect Custom
    // Tab dismissal (T-xai-oauth-stop-resume port iOS d1dbdd5d).
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Markdown rendering (mikepenz multiplatform-markdown-renderer)
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    // Chrome Custom Tabs (in-app browser for OAuth)
    implementation("androidx.browser:browser:1.8.0")

    // T-pwa-1: WebViewAssetLoader serves pinned PWA HTML under
    // https://appassets.androidplatform.net/ inside PwaActivity, so
    // sibling CSS/JS resolve against the file's parent dir without
    // granting WebView raw file:// access.
    implementation("androidx.webkit:webkit:1.12.1")

    // Drag-to-reorder for LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // T283: ACRA — local crash report capture. acra-core only (no http
    // sender, no network permission). CrashFileSender writes reports to
    // filesDir/logs/ where LogManagementScreen surfaces them.
    implementation("ch.acra:acra-core:5.12.0")

    // T322: Shizuku SDK — offloads privileged Android system APIs (PackageManager,
    // PermissionManager, ActivityManager, AppOps, IInputManager, …) through a
    // user-installed Shizuku app running as adb shell (uid=2000) or root. The CLI
    // surface `android-shizuku-cli` is a NativeOffloadHandler that forwards argv into
    // these hidden APIs via Shizuku's binder. `api` provides the manager binder
    // proxy + permission flow, `provider` registers the in-process content
    // provider that hosts the user-app side of the binder.
    //
    // [T-android-privileged-backend] AXManager (Axeron) needs NO extra
    // dependency: its server is a drop-in Shizuku-protocol implementation that
    // `sendBinder`s into the standard `<applicationId>.shizuku` ShizukuProvider
    // (verified against the installed APK). AxeronBackend therefore rides the
    // same `rikka.shizuku.Shizuku` client + provider declared above. The
    // earlier Axeron-API SDK route was dropped — it duplicated the
    // `moe.shizuku.*` classes (AGP checkDuplicateClasses failure) and pulled an
    // incompatible androidx.core / minSdk for zero added capability.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Testing — JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
    // [test-hardening] Robolectric: Compose/UI-adjacent ViewModel logic on the
    // JVM (no device, no APK build). Pinned to a 4.14.x line that supports
    // compileSdk 36 resource merging.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // [T-m13-fts-memory] JVM suite runs the REAL FTS5 SQL (the identical
    // statements the Android SQLite executes) against sqlite-jdbc.
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")


    // Testing — Instrumented (on-device) tests
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}
apply(from = "testlogging.gradle.kts")
