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
        versionCode = 20
        versionName = "0.20-preview"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // System prompt prefix required by Anthropic for Claude Code OAuth
        // credentials. Empty in the public mirror (see provider-customization.properties).
        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            "\"${customizationValue("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")}\""
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
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

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
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

    // Testing — Instrumented (on-device) tests
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}
