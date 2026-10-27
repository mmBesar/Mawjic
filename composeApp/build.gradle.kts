import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

val versionNameValue = providers.gradleProperty("versionName").orElse("0.1.0").get()
val versionCodeValue = providers.gradleProperty("versionCode").orElse(providers.environmentVariable("VERSION_CODE")).orElse("1").get().toInt()
val rustRoot = rootProject.layout.projectDirectory.dir("rust-core")
val rustTargetDir = layout.buildDirectory.dir("rust-target")
val generatedDesktopResources = layout.buildDirectory.dir("generated/nativeResources")
val generatedJniLibs = layout.buildDirectory.dir("generated/jniLibs")
val hostArch = System.getProperty("os.arch").lowercase(Locale.ROOT)
val rustDesktopTarget = when (hostArch) {
    "aarch64", "arm64" -> "aarch64-unknown-linux-gnu"
    "x86_64", "amd64" -> "x86_64-unknown-linux-gnu"
    else -> error("Unsupported Linux desktop architecture: $hostArch")
}
val desktopResourceArch = if (rustDesktopTarget.startsWith("aarch64")) "linux-arm64" else "linux-x64"

val buildRustDesktopCore = tasks.register<Exec>("buildRustDesktopCore") {
    group = "build"
    description = "Build the dependency-free Rust parser for the current Linux desktop architecture."
    inputs.dir(rustRoot)
    outputs.file(generatedDesktopResources.map { it.file("native/$desktopResourceArch/libmawjic_core.so") })
    environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
    commandLine("cargo", "build", "--locked", "--release", "--manifest-path", rustRoot.file("Cargo.toml").asFile.absolutePath, "--target", rustDesktopTarget)
    doLast {
        val source = rustTargetDir.get().file("$rustDesktopTarget/release/libmawjic_core.so").asFile
        check(source.isFile) { "Cargo did not create ${source.absolutePath}" }
        val destination = generatedDesktopResources.get().dir("native/$desktopResourceArch").asFile
        destination.mkdirs()
        source.copyTo(destination.resolve("libmawjic_core.so"), overwrite = true)
    }
}

val buildRustAndroidCore = tasks.register<Exec>("buildRustAndroidCore") {
    group = "build"
    description = "Cross-compile the Rust parser for Android arm64-v8a using the installed Android NDK."
    inputs.dir(rustRoot)
    outputs.file(generatedJniLibs.map { it.file("arm64-v8a/libmawjic_core.so") })
    doFirst {
        val ndk = sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
            .mapNotNull { System.getenv(it) }.firstOrNull()
            ?: error("Android NDK not found. Install the NDK and set ANDROID_NDK_HOME.")
        val hostTag = if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64" else "linux-x86_64"
        val toolchain = file("$ndk/toolchains/llvm/prebuilt/$hostTag/bin")
        val linker = toolchain.resolve("aarch64-linux-android26-clang")
        check(linker.canExecute()) { "Android NDK linker not executable: ${linker.absolutePath}" }
        environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
        commandLine("cargo", "build", "--locked", "--release", "--manifest-path", rustRoot.file("Cargo.toml").asFile.absolutePath, "--target", "aarch64-linux-android")
    }
    doLast {
        val source = rustTargetDir.get().file("aarch64-linux-android/release/libmawjic_core.so").asFile
        check(source.isFile) { "Cargo did not create ${source.absolutePath}" }
        val destination = generatedJniLibs.get().dir("arm64-v8a").asFile
        destination.mkdirs()
        source.copyTo(destination.resolve("libmawjic_core.so"), overwrite = true)
    }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            // Use explicit versioned coordinates. CMP's old `compose.*` aliases
            // are deprecated and can silently move independently across releases.
            implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
            implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.ui:ui:1.10.3")
            implementation("org.jetbrains.compose.components:components-resources:1.10.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("io.coil-kt.coil3:coil-compose:3.5.0")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.5.0")
            implementation("io.coil-kt.coil3:coil-svg:3.5.0")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.12.4")
            implementation("androidx.media3:media3-exoplayer:1.11.1")
            implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
            implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
            implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
            implementation("androidx.media3:media3-ui:1.11.1")
            implementation("io.ktor:ktor-client-android:3.6.0")
            implementation("net.java.dev.jna:jna:5.19.1@aar")
        }
        val desktopMain by getting {
            resources.srcDir(generatedDesktopResources)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-java:3.6.0")
                implementation("net.java.dev.jna:jna:5.19.1")
            }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "app.mawjic.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.mawjic.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    sourceSets.getByName("main").manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets.getByName("main").jniLibs.srcDir(generatedJniLibs)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
}

val signingPath = System.getenv("ANDROID_KEYSTORE_PATH")
val signingStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val signingKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = !signingPath.isNullOrBlank() && !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank() && file(signingPath).isFile
if (releaseSigningConfigured) {
    android.signingConfigs.create("release") {
        storeFile = file(signingPath!!)
        storePassword = signingStorePassword
        keyAlias = signingKeyAlias
        keyPassword = signingKeyPassword
    }
    android.buildTypes.getByName("release").signingConfig = android.signingConfigs.getByName("release")
}
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    group = "verification"
    doLast {
        check(releaseSigningConfigured) {
            "Refusing to build a release APK without release signing. Set ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD. Debug signing is never substituted."
        }
    }
}
tasks.configureEach {
    if (name in setOf("assembleRelease", "packageRelease", "bundleRelease")) dependsOn(requireReleaseSigning)
}
// Do not make desktop-only builds require an Android SDK or NDK.
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRustAndroidCore) }
tasks.matching { it.name == "desktopProcessResources" }.configureEach { dependsOn(buildRustDesktopCore) }

compose.desktop {
    application {
        mainClass = "app.mawjic.tv.desktop.MainKt"
        nativeDistributions {
            packageName = "mawjic"
            packageVersion = versionNameValue
            description = "A cross-platform IPTV client with EPG and Catppuccin themes"
            vendor = "Mawjic contributors"
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm)
            linux {
                appCategory = "AudioVideo"
                debMaintainer = "Mawjic contributors"
                iconFile.set(rootProject.file("assets/mawjic.png"))
            }
        }
    }
}

tasks.matching { it.name == "desktopTest" }.configureEach { dependsOn(buildRustDesktopCore) }
