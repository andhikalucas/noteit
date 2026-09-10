import java.util.Properties
import java.util.Base64
import java.security.MessageDigest

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val personalSecrets = Properties().apply {
    val secretsFile = rootProject.file("secrets.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use { load(it) }
}
val geminiApiKey = personalSecrets.getProperty("GEMINI_API_KEY", "").trim()
val quotedGeminiApiKey = "\"" + geminiApiKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Flutter passes each Dart definition as a base64 item. Keep benchmarks in a
// different Android sandbox: test-tool install/uninstall behavior must never
// target the owner's notes, recordings, settings or model files.
val flutterTarget = providers.gradleProperty("target").orNull.orEmpty().replace('\\', '/')
// Integration tests actually build a generated temporary listener.dart, not
// the original integration_test path. Only the standard app entry point may
// use the personal package; every alternate/test entry point is isolated.
val personalEntryPoint = flutterTarget.isEmpty() || flutterTarget == "lib/main.dart" || flutterTarget.endsWith("/lib/main.dart")
val benchmarkBuild = !personalEntryPoint || providers.gradleProperty("dart-defines").orNull.orEmpty()
    .split(',').filter { it.isNotBlank() }.any { encoded ->
        runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }
            .getOrNull() == "NOTEIT_BENCHMARK=true"
    }
val qwenBenchmark = benchmarkBuild && providers.gradleProperty("dart-defines").orNull.orEmpty()
    .split(',').filter { it.isNotBlank() }.any { encoded ->
        runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }
            .getOrNull() == "NOTEIT_QWEN_BENCHMARK=true"
    }
val qwenAar = rootProject.file("../build/benchmarks/qwen-android/sherpa-onnx-1.13.7.aar")
if (qwenBenchmark) {
    check(qwenAar.isFile && qwenAar.length() == 49113869L) { "Prepare the pinned Qwen benchmark AAR first." }
    val digest = MessageDigest.getInstance("SHA-256")
    qwenAar.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } ==
        "c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8") { "Qwen benchmark AAR checksum mismatch." }
}

android {
    namespace = "ai.voiceflow.voiceflow_mobile"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    buildFeatures { buildConfig = true }
    if (qwenBenchmark) sourceSets.getByName("main").java.srcDir("src/qwenBenchmark/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Native whisper.cpp engine (see src/main/cpp/CMakeLists.txt).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = if (benchmarkBuild) "ai.voiceflow.voiceflow_mobile.benchmark" else "ai.voiceflow.voiceflow_mobile"
        manifestPlaceholders["appLabel"] = if (benchmarkBuild) "noteit! benchmark" else "noteit!"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24  // whisper.cpp / ggml need modern libc; also gates our FGS-microphone use
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "GEMINI_API_KEY", if (benchmarkBuild) "\"\"" else quotedGeminiApiKey)
        buildConfigField("boolean", "BENCHMARK_BUILD", benchmarkBuild.toString())
        buildConfigField("boolean", "QWEN_BENCHMARK", qwenBenchmark.toString())

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        // arm64 for real devices (Whisper is validated there); x86_64 for the emulator.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Whisper in a Debug-mode native build is 5-10x slower — always
            // compile the native engine optimized, even for debug APKs.
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    if (qwenBenchmark) implementation(files(qwenAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
