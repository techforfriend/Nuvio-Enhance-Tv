plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import java.io.File
import java.util.Properties

fun parseBooleanProperty(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
}

fun resolveProperty(dev: Properties, local: Properties, key: String, fallback: String = ""): String {
    return dev.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: local.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun cmakePath(path: String): String {
    if (path.isBlank()) return ""
    val file = File(path)
    val resolved = if (file.isAbsolute) file else rootProject.file(path)
    return resolved.absolutePath.replace("\\", "/")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val devProperties = Properties().apply {
    val devPropertiesFile = rootProject.file("local.dev.properties")
    if (devPropertiesFile.exists()) {
        load(devPropertiesFile.inputStream())
    }
}

val enableDoviNative = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_NATIVE_ENABLED")
)
val doviExtractorHookReady = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_EXTRACTOR_HOOK_READY")
)
val doviEnableRealLink = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_ENABLE_REAL_LINK")
)
// Spec 21 N4: MKV per-sample DV/HDR10+ rewrite in native (default) or the Kotlin path (A/B build).
val dvMkvNativePipeline = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DV_MKV_NATIVE_PIPELINE", fallback = "true")
)
val doviStaticLibPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_STATIC_LIB")
val doviIncludeDirPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_INCLUDE_DIR")
val doviPrebuiltRootPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_PREBUILT_ROOT")

fun env(name: String): String? = providers.environmentVariable(name).orNull

fun truthy(value: String?): Boolean {
    return value.equals("true", ignoreCase = true) ||
        value.equals("1", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true)
}

val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val useDebugReleaseSigning = env("CI_USE_DEBUG_SIGNING").equals("true", ignoreCase = true)
val useLocalFfmpegDecoder = truthy(
    providers.gradleProperty("useLocalFfmpegDecoder").orNull
        ?: env("USE_LOCAL_FFMPEG_DECODER")
        ?: localProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
)
// Spec 49 R3: `-PnuvioPublic=true` makes the public build: no built-in TMDB key whatever
// local.properties holds (users enter their own, Spec 52), which alone turns the update check on.
val nuvioPublicBuild = truthy(providers.gradleProperty("nuvioPublic").orNull)
val releaseStoreFilePath = env("NUVIO_RELEASE_STORE_FILE")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_FILE")
val releaseKeyAliasValue = env("NUVIO_RELEASE_KEY_ALIAS")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_ALIAS", "nuviotv")
// No password defaults (Spec 49 R1): a release build without them fails at signing instead of
// signing with a guessable password.
val releaseKeyPasswordValue = env("NUVIO_RELEASE_KEY_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_PASSWORD")
val releaseStorePasswordValue = env("NUVIO_RELEASE_STORE_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_PASSWORD")

android {
    namespace = "com.nuvio.tv"
    // Spec 32 P3: compileSdk 37 (Compose 1.12 minCompileSdk); targetSdk stays 36, the Shield is API 30.
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // AK id: this fork installs as com.nuvio.tv.ak, side-by-side with the
        // legacy com.nuvio.tv.test copy and the Play Store app.
        applicationId = "com.nuvio.tv.ak"
        minSdk = 24
        targetSdk = 36
        versionCode = 1393
        versionName = "0.9.0-ak52"
        resValue("string", "app_name", "Nuvio Enhanced")

        buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
        buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
        buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
        val builtInTmdbKey = if (nuvioPublicBuild) "" else localProperties.getProperty("TMDB_API_KEY", "")
        buildConfigField("String", "TMDB_API_KEY", "\"$builtInTmdbKey\"")
        // Update check feed (Spec 52 P6): the public repo's GitHub Releases; checked only by a build
        // without a TMDB key (core/update/UpdateRelease.kt updateCheckSupported).
        buildConfigField("String", "UPDATE_OWNER", buildConfigString(localProperties.getProperty("UPDATE_OWNER").takeUnless { it.isNullOrBlank() } ?: "Arshad-Kamal"))
        buildConfigField("String", "UPDATE_REPO", buildConfigString(localProperties.getProperty("UPDATE_REPO").takeUnless { it.isNullOrBlank() } ?: "Nuvio-AK"))
        buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", enableDoviNative.toString())
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", doviExtractorHookReady.toString())
        buildConfigField("boolean", "DV_MKV_NATIVE_PIPELINE", dvMkvNativePipeline.toString())
        if (enableDoviNative) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DDOVI_ENABLE_LIBDOVI=${if (doviEnableRealLink) "ON" else "OFF"}",
                        "-DDOVI_LIBDOVI_STATIC_LIB=${cmakePath(doviStaticLibPath)}",
                        "-DDOVI_LIBDOVI_INCLUDE_DIR=${cmakePath(doviIncludeDirPath)}",
                        "-DDOVI_LIBDOVI_PREBUILT_ROOT=${cmakePath(doviPrebuiltRootPath)}"
                    )
                }
            }
        }
        buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }

    }

    if (enableDoviNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = releaseKeyAliasValue
            keyPassword = releaseKeyPasswordValue
            storeFile = releaseStoreFilePath?.let(::file) ?: file("../nuviotv.jks")
            storePassword = releaseStorePasswordValue
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false

            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")

            // Dev environment (from local.dev.properties)
            buildConfigField("String", "SUPABASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_URL")))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_ANON_KEY")))
            buildConfigField("String", "SUPABASE_FALLBACK_URL", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_FALLBACK_URL")))
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${devProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${devProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${devProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${devProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${devProperties.getProperty("AVATAR_PUBLIC_BASE_URL", localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", ""))}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useDebugReleaseSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }

            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")

            // Production environment (from local.properties)
            buildConfigField("String", "SUPABASE_URL", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_URL", "")))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_ANON_KEY", "")))
            buildConfigField("String", "SUPABASE_FALLBACK_URL", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_FALLBACK_URL", "")))
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            applicationIdSuffix = ".debug"
            // Wave U Shield windows install this next to the family's "Nuvio AK": distinct label.
            resValue("string", "app_name", "Nuvio AK Test")
            matchingFallbacks += "release"
        }
    }

    splits {
        abi {
            isEnable = !buildingAppBundle
            reset()
            // W6.3: -Pnuvio.genAbi=<abi> narrows the split for baseline-profile
            // generation on the x86_64 emulator (default behavior unchanged).
            if (project.hasProperty("nuvio.genAbi")) {
                include(project.property("nuvio.genAbi") as String)
            } else {
                include("armeabi-v7a", "arm64-v8a")
            }
            isUniversalApk = false
        }
    }

    bundle {
        language {
            // Keep all string resources in the
            // base install so Play Store installs can switch languages at runtime.
            // https://developer.android.com/guide/app-bundle/configure-base
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            // why: flatMapLatest and the coroutines-test dispatchers are stable in practice;
            // one module opt-in (main + unit tests) instead of annotating each use site. Older
            // per-site @OptIn(ExperimentalCoroutinesApi::class) annotations are now redundant.
            optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true // AGP 9 default off; app_name comes from resValue (defaultConfig + benchmark)
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Keep one consistent native set across dependencies.
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so",
                "lib/*/libtorrserver.so"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}


composeCompiler {
    // Enable Compose compiler metrics for performance analysis
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
}

// Globally exclude stock media3 modules — replaced by the Nuvio Engine local AARs in app/libs
// (lib-common/-datasource/-datasource-okhttp/-exoplayer/-exoplayer-hls/-extractor; see dependencies)
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-common")
    exclude(group = "androidx.media3", module = "media3-datasource")
    exclude(group = "androidx.media3", module = "media3-datasource-okhttp")
    exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
    exclude(group = "androidx.media3", module = "media3-extractor")
}

baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
    saveInSrc = true
    mergeIntoMain = true
    baselineProfileOutputDir = "generated/baselineProfiles"
    filter {
        include("com.nuvio.tv.**")
    }
}


dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    val composeBom = platform(libs.androidx.compose.bom)

    // Source-retention nullness annotations (MonotonicNonNull / RequiresNonNull /
    // EnsuresNonNull) used by the vendored Matroska extractor in
    // com.nuvio.tv.core.player.dvmkv. Media3 keeps these compileOnly in its own
    // build, so they aren't on our classpath via the prebuilt AARs.
    compileOnly("org.checkerframework:checker-qual:3.43.0")

    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.appcompat)
    // Marquee ambient accent (ui/theme/AmbientAccent.kt); see docs/stack-notes.md androidx.palette.
    implementation(libs.androidx.palette)
    implementation(libs.androidx.profileinstaller)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.activity:activity-compose:1.11.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // Pin the RESOLVED OkHttp runtime to the 5.3.2 that ke5–ke21 shipped and validated
    // (docs/stack-notes.md: "pin strictly, never drift"). The removed CloudStream library
    // used to raise it transitively; without this the runtime silently fell back to 4.12.0
    // (DRDYK A2.1 review). The constraint is on `implementation`, so okhttp core resolves 5.3.2
    // on BOTH compile and runtime classpaths; only logging-interceptor stays the 4.12.0 artifact
    // (the catalog `okhttp` version now governs logging-interceptor alone).
    constraints {
        implementation("com.squareup.okhttp3:okhttp") { version { strictly("5.3.2") } }
    }

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)

    // Navigation
    implementation(libs.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)

    // Media3 — remaining stock modules from Maven (not forked)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.decoder)
    implementation(libs.media3.session)
    implementation(libs.media3.container)

    // Transitive dependencies required by forked local AARs (not bundled in AARs):
    // - Guava: needed by lib-common (ImmutableList/ImmutableSet in Tracks, Player API)
    // - media3-database: needed by lib-datasource (cache/storage layer)
    // - annotation-experimental: needed by lib-common (OptIn annotations)
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.media3:media3-database:1.8.0")
    implementation("androidx.annotation:annotation-experimental:1.3.1")

    // Nuvio Engine local AARs (replaces lib-exoplayer, lib-common, lib-datasource, lib-datasource-okhttp, lib-exoplayer-hls, lib-extractor)
    implementation(files(
        "libs/lib-common-release.aar",
        "libs/lib-datasource-release.aar",
        "libs/lib-datasource-okhttp-release.aar",
        "libs/lib-exoplayer-release.aar",
        "libs/lib-exoplayer-hls-release.aar",
        "libs/lib-extractor-release.aar"
    ))
    implementation(libs.media3.ui)

    // Local decoder AAR (AV1)
    implementation(files(
        "libs/lib-decoder-av1-release.aar"
    ))
    if (useLocalFfmpegDecoder) {
        implementation(project(":ffmpeg-decoder-downmix"))
    } else {
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    }

    // libass-android for ASS/SSA subtitle support (from Maven Central)
    implementation("io.github.peerless2012:ass-media:0.4.0")
    // Local nextlib-mediainfo fork (static FFmpeg; no libav*.so in final AAR)
    implementation(files("libs/nextlib-mediainfo-local.aar"))
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")

    // Markdown rendering
    implementation(libs.markdown.renderer.m3)

    // QR code + local server for addon management
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)


    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // CloudStream (CS3) runtime — mirrors Nuvio Enhanced-2's full flavour.
    // .cs3 plugins load through the embedded runtime AAR; the rest are the
    // libraries that runtime and standard providers link against.
    implementation(files("libs/cloudstream-runtime-api-4.8.0-3496e5f.aar"))
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.datetime)
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    implementation(libs.conscrypt.android)
    implementation(libs.fuzzywuzzy)
    implementation(libs.rhino)
    implementation(libs.tmdb.java)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3") {
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation(kotlin("reflect"))
    implementation("androidx.annotation:annotation:1.10.0")

    // Performance profiling
    implementation("androidx.metrics:metrics-performance:1.0.0-rc01")  // JankStats
    debugImplementation("androidx.compose.runtime:runtime-tracing")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation(libs.mockwebserver)
    // Real org.json for JVM unit tests: android.jar's org.json is a stub that returns
    // null under isReturnDefaultValues (BingeGroupCache/StreamLinkCache/DeviceAssessmentApplier
    // serialize with JSONObject). It used to arrive transitively via the removed CloudStream
    // library (DRDYK A2 plugins cut); same version, now explicit. Test classpath only.
    testImplementation("org.json:json:20250517")
    // XmlPullParser implementation for JVM tests (android.jar's is a stub). Upstream kxml2, not
    // the modified libcore KXmlParser Android's Xml.newPullParser returns; the XMLTV parser's
    // entity guard sits in its own input stream so it holds for both. Spec 34. Test classpath only.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
