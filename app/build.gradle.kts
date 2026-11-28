import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi") version "1.76.0"
}

// ---------------------------------------------------------------------------------------------
// Release signing is opt-in. Without configuration, assembleRelease produces
// app-release-unsigned.apk; the debug key is never used for release. Provide all four values via
// environment variables, Gradle properties (-P or ~/.gradle/gradle.properties), or a gitignored
// keystore.properties in the project root (see keystore.properties.example). Partial
// configuration fails the build. See docs/dev/BUILD_NOTES.md.
// ---------------------------------------------------------------------------------------------
val keystoreFile = rootProject.file("keystore.properties")
val keystoreFileProps = Properties().apply { if (keystoreFile.isFile) keystoreFile.inputStream().use { load(it) } }
fun signingValue(env: String, prop: String, fileKey: String): String? =
    (providers.environmentVariable(env).orNull ?: providers.gradleProperty(prop).orNull ?: keystoreFileProps.getProperty(fileKey))
        ?.takeIf { it.isNotBlank() }
val releaseSigning = linkedMapOf(
    "MARQUEE_KEYSTORE_FILE / marquee.keystore.file / storeFile" to signingValue("MARQUEE_KEYSTORE_FILE", "marquee.keystore.file", "storeFile"),
    "MARQUEE_KEYSTORE_PASSWORD / marquee.keystore.password / storePassword" to signingValue("MARQUEE_KEYSTORE_PASSWORD", "marquee.keystore.password", "storePassword"),
    "MARQUEE_KEY_ALIAS / marquee.key.alias / keyAlias" to signingValue("MARQUEE_KEY_ALIAS", "marquee.key.alias", "keyAlias"),
    "MARQUEE_KEY_PASSWORD / marquee.key.password / keyPassword" to signingValue("MARQUEE_KEY_PASSWORD", "marquee.key.password", "keyPassword"),
)
val signReleases = releaseSigning.values.all { it != null }
if (!signReleases && releaseSigning.values.any { it != null }) {
    throw GradleException(
        "Release signing is partially configured. Missing: " +
            releaseSigning.filterValues { it == null }.keys.joinToString("; ") +
            ". Provide all four values, or none to build an unsigned release.",
    )
}

// Versioning: overridable for CI with -Pmarquee.versionCode=N -Pmarquee.versionName=x.y.z.
val marqueeVersionCode = providers.gradleProperty("marquee.versionCode").orNull?.toInt() ?: 2
val marqueeVersionName = providers.gradleProperty("marquee.versionName").orNull ?: "0.2.0"

android {
    namespace = "com.mav.marquee"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mav.marquee"
        minSdk = 26
        targetSdk = 36
        versionCode = marqueeVersionCode
        versionName = marqueeVersionName
        // Same-code A/B found no managed-image-cache gain; normal heap passed 4K playback without LMK.
        manifestPlaceholders["marqueeLargeHeap"] = providers.gradleProperty("marquee.largeHeap").orElse("false").get()
    }

    signingConfigs {
        if (signReleases) {
            create("release") {
                // Relative paths resolve against the project root (not app/); absolute paths are used as-is.
                // "~" is not expanded.
                storeFile = rootProject.file(releaseSigning.values.elementAt(0)!!)
                storePassword = releaseSigning.values.elementAt(1)
                keyAlias = releaseSigning.values.elementAt(2)
                keyPassword = releaseSigning.values.elementAt(3)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["playerActivityExported"] = true
            resValue("string", "app_name", "Marquee DEV")
            // Debug builds keep every ABI the media3-ffmpeg AAR ships, so x86/x86_64 TV emulators work.
        }
        release {
            isMinifyEnabled = true
            manifestPlaceholders["playerActivityExported"] = false
            isShrinkResources = true
            resValue("string", "app_name", "Marquee")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ARM TVs only: 32-bit userspace (e.g. the reference Sony BRAVIA VU1) and 64-bit.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            signingConfig = if (signReleases) signingConfigs.getByName("release") else null
        }
        create("profile") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isDebuggable = false
            // Local diagnostics only (shell-profileable, keeps logcat markers); never distributed.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Release only: strip verbose/debug/info logging (playback/startup markers). The profile
        // build keeps them because its measurements read those markers.
        getByName("release") { proguardFile("proguard-release-logs.pro") }
    }

    // Optional per-ABI APKs next to the universal one: -Pmarquee.abiSplits=true
    splits {
        abi {
            isEnable = providers.gradleProperty("marquee.abiSplits").orNull == "true"
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            it.maxParallelForks = 1
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("user.timezone", "UTC")
        }
    }

    lint {
        // New lint errors fail the build (including lintVitalRelease during assembleRelease).
        // Pre-existing findings that are deliberately not fixed live in lint-baseline.xml; scoped
        // suppressions with reasons live in lint.xml.
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = file("lint.xml")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        // Native libraries stay uncompressed and page-aligned in the APK (16 KB page-size devices).
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Opt in with -Pmarquee.composeReports=true; keep ordinary builds unchanged.
composeCompiler {
    if (providers.gradleProperty("marquee.composeReports").orNull == "true") {
        val phase = providers.gradleProperty("marquee.reportPhase").orElse("current").get()
        reportsDestination = layout.buildDirectory.dir("compose-reports/$phase")
        metricsDestination = layout.buildDirectory.dir("compose-reports/$phase")
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "768m"
    maxParallelForks = 1
    systemProperty("marquee.navCapture", providers.gradleProperty("marquee.navCapture").orElse("verify").get())
    systemProperty("marquee.projectRoot", rootProject.projectDir.absolutePath)
    inputs.property("navCapture", providers.gradleProperty("marquee.navCapture").orElse("verify"))
    systemProperty("marquee.navCaptionLayouts", providers.gradleProperty("marquee.navCaptionLayouts").orElse("true").get())
    inputs.property("navCaptionLayouts", providers.gradleProperty("marquee.navCaptionLayouts").orElse("true"))
    // Offline login renders for docs/media (see LoginRenderTest); off unless requested.
    providers.gradleProperty("marquee.loginRender").orNull?.let {
        systemProperty("marquee.loginRender", it)
        outputs.upToDateWhen { false }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.1")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    testImplementation(composeBom)
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.1")

    implementation("io.coil-kt.coil3:coil:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Diagnostic variant only. Keep native tracing binaries out of the shipped release.
    "profileImplementation"("androidx.compose.runtime:runtime-tracing:1.11.4")
    "profileImplementation"("androidx.tracing:tracing-perfetto:1.0.1")
    "profileImplementation"("androidx.tracing:tracing-perfetto-binary:1.0.1")

    // Playback (used by com.mav.marquee.player.*)
    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Last-resort software audio decoders (Jellyfin's FFmpeg build for Media3). Ships native
    // libraries for armeabi-v7a, arm64-v8a, x86 and x86_64, all 16 KB page-aligned.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
}

roborazzi {
    // Committed goldens. The tests also pass explicit paths (OfflinePaths.golden), see docs/dev/TESTING.md.
    outputDir.set(file("src/test/screenshots"))
}
