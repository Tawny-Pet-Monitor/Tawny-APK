import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upload-key signing. Create android/keystore.properties (NOT committed) with:
//   storeFile=/abs/path/tawny-upload.jks
//   storePassword=...
//   keyAlias=upload
//   keyPassword=...
// Generate the key once:
//   keytool -genkeypair -v -keystore tawny-upload.jks -alias upload \
//     -keyalg RSA -keysize 2048 -validity 10000
// Then enrol the app in Play App Signing on first upload. If the file is
// absent the release build is left unsigned (fine for a CI artifact).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile") != null

// Remote-relay settings. Set these in android/local.properties (NOT committed):
//   tawny.rendezvousUrl=wss://<your-worker>.workers.dev
//   tawny.stunUrls=stun:stun.cloudflare.com:3478,stun:stun.l.google.com:19302
//   tawny.turnMode=auto            (auto = relay only on P2P failure; always = force)
// All optional. With none set the app builds LAN-only, exactly as before.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionCode = versionProps.getProperty("VERSION_CODE", "1").trim().toInt()
val appVersionName = versionProps.getProperty("VERSION_NAME", "0.1.0").trim()
fun localOrProject(key: String): String? =
    localProps.getProperty(key) ?: (project.findProperty(key) as String?)
val rendezvousUrl = localOrProject("tawny.rendezvousUrl").orEmpty()
val turnMode = localOrProject("tawny.turnMode") ?: "auto"
// The native build type as the GitHub edition's release (see `native` below).
val nativeRelease = localOrProject("tawny.nativeRelease") == "true"
// Nostr relays every build meets on (public/nostr/transport.js), Nostr first
// with the rendezvous as the backup. Checked 2026-11-13 to carry ephemeral
// gift wraps (kind 21059) round trip; damus.io, nos.lol and nostr.mom refuse
// them and offchain.pub admits only its web of trust. Users can replace the
// list on the Servers screen. Override per build:
//   tawny.nostrRelays=wss://a.example,wss://b.example   (empty = no Nostr)
//   tawny.nostrMode=prefer|only|off
val nostrRelays = (localOrProject("tawny.nostrRelays")
    ?: "wss://relay.primal.net,wss://relay.nostr.net,wss://relay.snort.social")
    .split(",").map { it.trim() }.filter { it.matches(Regex("^wss?://[^\\s\"]+$")) }
val nostrMode = localOrProject("tawny.nostrMode")?.trim()?.takeIf { it in listOf("prefer", "only", "off") } ?: "prefer"

// Tawny DevOps: the `devops` build signals over Nostr only, through the relay
// and wire tap in Tawny DevOps/nostr-signaling-demo (./run.sh --lan). The
// phone reaches it through `adb reverse tcp:7447 tcp:7447`, so 127.0.0.1 is
// right for both the phone and the desktop build. Override per machine with
//   tawny.devops.nostrRelays=ws://192.168.1.50:7447/relay   (comma list)
//   tawny.devops.nostrMirror=http://192.168.1.50:7447/mirror
val devopsNostrRelays = (localOrProject("tawny.devops.nostrRelays") ?: "ws://127.0.0.1:7447/relay")
    .split(",").map { it.trim() }.filter { it.matches(Regex("^wss?://[^\\s\"]+$")) }
val devopsNostrMirror = (localOrProject("tawny.devops.nostrMirror") ?: "http://127.0.0.1:7447/mirror")
    .trim().takeIf { it.matches(Regex("^https?://[^\\s\"]+$")) }.orEmpty()
// What the page's connect-src must add for those: scheme://host:port, deduped.
val devopsNostrHosts = (devopsNostrRelays + listOf(devopsNostrMirror))
    .filter { it.isNotEmpty() }
    .map { Regex("^(wss?|https?)://([^/]+)").find(it)!!.let { m ->
        val scheme = m.groupValues[1]
        if (scheme == "ws" || scheme == "http") "ws://${m.groupValues[2]}" else m.groupValues[2] } }
    .distinct()

val stunUrls = localOrProject("tawny.stunUrls")
    ?: if (rendezvousUrl.isNotEmpty())
        "stun:stun.cloudflare.com:3478,stun:stun.l.google.com:19302" else ""

android {
    namespace = "com.tawny.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tawny.monitor"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "RENDEZVOUS_URL", "\"$rendezvousUrl\"")
        // The build's Nostr relays (comma list); the Servers screen can replace them.
        buildConfigField("String", "NOSTR_RELAYS", "\"${nostrRelays.joinToString(",")}\"")
        // Extra connect-src hosts beyond the relays; only devops has one (its mirror).
        buildConfigField("String", "NOSTR_HOSTS", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // A user-facing string added to values/ without an es/ (etc.) counterpart
        // must fail the build, not slip out half-translated.
        error += "MissingTranslation"
        warning += "ExtraTranslation"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            // The app ships no native code of its own — CameraX (the QR
            // scanner) is what pulls in a couple of prebuilt .so files, and
            // Play's "upload debug symbols" warning on every release is
            // about those. As of camerax 1.4.1 there is no
            // native-debug-symbols artifact for AGP to fetch (checked
            // against dl.google.com/android/maven2 directly — 404), so this
            // setting does not actually clear the warning today. It costs
            // nothing to leave on: it's the correct config to have the
            // moment either this app gains real native code or a future
            // CameraX release starts publishing symbols, with nothing
            // further to change here.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // The APK attached to GitHub Releases, for phones without Google Play.
        // Same code and upload key as `release`, but its own app id: Play
        // re-signs its copy with Google's app-signing key, so an APK signed
        // with ours could never update (or be updated by) the Play install.
        // As com.tawny.monitor.github it sits beside it as "Tawny (GitHub)".
        // `./gradlew :app:assembleGithub`; tools/tawny-github-apk files it.
        // Tawny DevOps: a debug build that installs beside Tawny as its own app
        // ("Tawny DevOps", com.tawny.monitor.devops) and signals over Nostr
        // only, so every handshake can be watched in the inspector. Never
        // shipped. `./gradlew :app:assembleDevops`.
        create("devops") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devops"
            versionNameSuffix = "-devops"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "NOSTR_RELAYS", "\"${devopsNostrRelays.joinToString(",")}\"")
            buildConfigField("String", "NOSTR_HOSTS", "\"${devopsNostrHosts.joinToString(",")}\"")
        }
        // The native app (Rust core through tawny-ffi, Compose screens), built
        // by Tawny DevOps/tools/native-android. Installs beside the others as
        // "Tawny Native" (com.tawny.monitor.native). Its code, the generated
        // bindings and the per-ABI libraries live in src/native/.
        //
        // With -Ptawny.nativeRelease=true it is the GitHub edition instead:
        // "Tawny (GitHub)", com.tawny.monitor.github, release-signed, so it
        // updates the WebView GitHub app in place and its pairings come
        // across (JsMigration). Not minified: UniFFI's JNA bindings and
        // libwebrtc reach their classes by name. Built unsigned on the build
        // machine and signed here (`native-android --release`).
        create("native") {
            if (nativeRelease) {
                initWith(getByName("release"))
                isMinifyEnabled = false
                isShrinkResources = false
                applicationIdSuffix = ".github"
                matchingFallbacks += listOf("release")
                resValue("string", "app_name", "Tawny (GitHub)")
            } else {
                initWith(getByName("debug"))
                applicationIdSuffix = ".native"
                versionNameSuffix = "-native"
                matchingFallbacks += listOf("debug")
                resValue("string", "app_name", "Tawny Native")
            }
        }
        create("github") {
            initWith(getByName("release"))
            applicationIdSuffix = ".github"
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The Kotlin plugin's own block: `android { kotlinOptions { … } }` is an error
// from Kotlin 2.2 on.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The native app only (build type `native`): Compose, the Rust core's
    // bindings (UniFFI's Kotlin needs JNA) and libwebrtc's Java classes.
    val nativeImplementation by configurations
    nativeImplementation(platform("androidx.compose:compose-bom:2025.06.00"))
    nativeImplementation("androidx.compose.material3:material3")
    nativeImplementation("androidx.compose.ui:ui")
    nativeImplementation("androidx.compose.ui:ui-tooling-preview")
    nativeImplementation("androidx.activity:activity-compose:1.10.1")
    nativeImplementation("androidx.lifecycle:lifecycle-service:2.9.1")
    nativeImplementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    nativeImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    nativeImplementation("net.java.dev.jna:jna:5.17.0@aar")
    nativeImplementation(files("libs/libwebrtc.jar"))
    // The Compose compiler runs on every build type (buildFeatures can't be
    // set per type) and stops when no Compose runtime is on the classpath.
    // Only `native` ships Compose; the others get the runtime to compile
    // against, never packaged.
    compileOnly(platform("androidx.compose:compose-bom:2025.06.00"))
    compileOnly("androidx.compose.runtime:runtime")
    testCompileOnly(platform("androidx.compose:compose-bom:2025.06.00"))
    testCompileOnly("androidx.compose.runtime:runtime")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // Google Play's in-app review sheet, for the rating prompt. Ships its own
    // consumer R8 rules; on a phone without Play it simply never shows.
    implementation("com.google.android.play:review:2.0.2")

    // QR: encode the pairing code on the Watcher, decode it on the Handheld.
    implementation("com.google.zxing:core:3.5.3")

    // Camera preview + frame analysis for the in-app scanner.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // The Watcher hosts the WebRTC signaling handshake itself, on the LAN.
    implementation("org.java-websocket:Java-WebSocket:1.5.3")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // PairLink.kt is deliberately free of Android types, so the rules a
    // stranger's QR code is judged by can be tested on a plain JVM — no
    // emulator, no Robolectric, and it runs in a second. `./gradlew test`.
    testImplementation("junit:junit:4.13.2")
    // TipZapTest parses relay events; android.jar's org.json is only a stub on the JVM.
    testImplementation("org.json:json:20260814")
}

// The native shell serves the web app from the APK, so there is no address to
// type. Keep assets/web/ in sync with the shared public/ folder at build time.
/** The `nostr` block of config.json, or "" for none. */
fun nostrJson(relays: List<String>, mode: String, mirror: String = ""): String {
    if (relays.isEmpty() || mode == "off") return ""
    val list = relays.joinToString(",") { "\"$it\"" }
    val mirrorPart = if (mirror.isNotEmpty()) ",\"mirror\":\"$mirror\"" else ""
    return "{\"relays\":[$list],\"mode\":\"$mode\"$mirrorPart}"
}

/** config.json, as every build writes it. */
fun webConfigJson(nostr: String): String {
    val stunJson = stunUrls.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",") { "\"$it\"" }
    val tail = if (nostr.isNotEmpty()) ",\"nostr\":$nostr" else ""
    return "{\"stun\":[$stunJson],\"rendezvous\":\"$rendezvousUrl\",\"turnMode\":\"$turnMode\",\"authRequired\":false$tail}"
}

val syncWebAssets by tasks.registering(Copy::class) {
    // README.md: public/sounds/ documents which chime clips are still
    // placeholders, which is for whoever replaces them, not for the APK.
    // sounds/_src/: the original recordings the chime clips are derived from,
    // kept for re-editing (see tools/gen-chimes.sh). Not for the APK.
    from(rootProject.file("../public")) {
        exclude("**/.DS_Store", "**/README.md", "**/sounds/_src/**")
    }
    into(layout.projectDirectory.dir("src/main/assets/web"))
    doLast {
        layout.projectDirectory.file("src/main/assets/web/config.json").asFile.writeText(
            webConfigJson(nostrJson(nostrRelays, nostrMode))
        )
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }
    .configureEach { dependsOn(syncWebAssets) }
tasks.named("preBuild") { dependsOn(syncWebAssets) }

// The devops build's config.json: the main one plus the Nostr block. Its own
// asset folder outranks main's for the same path, so only devops sees it.
val devopsAssetsDir = layout.buildDirectory.dir("generated/devopsAssets")
val devopsWebConfig by tasks.registering {
    dependsOn(syncWebAssets)
    val out = devopsAssetsDir.map { it.file("web/config.json") }
    outputs.file(out)
    inputs.property("relays", devopsNostrRelays)
    inputs.property("mirror", devopsNostrMirror)
    inputs.property("rendezvous", rendezvousUrl)
    inputs.property("stun", stunUrls)
    doLast {
        out.get().asFile.apply {
            parentFile.mkdirs()
            writeText(webConfigJson(nostrJson(devopsNostrRelays, "only", devopsNostrMirror)))
        }
    }
}
android.sourceSets.getByName("devops").assets.srcDir(file("build/generated/devopsAssets"))   // a plain path: AGP refuses a Provider here
tasks.matching { it.name.startsWith("merge") && it.name.contains("Devops") && it.name.contains("Assets") }
    .configureEach { dependsOn(devopsWebConfig) }
