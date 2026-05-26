import java.util.Properties

plugins {
    // AGP 9 built-in Kotlin: no separate org.jetbrains.kotlin.android plugin.
    id("com.android.application")
}

// Release signing secrets, resolved from a gitignored launcher/keystore.properties
// first, then environment variables (CI-friendly). If none are supplied the release
// signingConfig is left unset and `assembleRelease` produces an unsigned APK — so the
// project still builds for anyone without the key. See keystore.properties.template.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSecret(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val ksStoreFile = signingSecret("storeFile", "SK_KEYSTORE_FILE")
val ksStorePass = signingSecret("storePassword", "SK_KEYSTORE_PASSWORD")
val ksKeyAlias  = signingSecret("keyAlias", "SK_KEY_ALIAS")
val ksKeyPass   = signingSecret("keyPassword", "SK_KEY_PASSWORD")
val hasReleaseSigning = ksStoreFile != null && ksStorePass != null &&
    ksKeyAlias != null && ksKeyPass != null

// versionCode must monotonically increase for over-the-top updates. Overridable for
// CI/publish via the SK_VERSION_CODE env var (CI passes github.run_number); local dev
// builds default to 1.
val skVersionCode = System.getenv("SK_VERSION_CODE")?.toIntOrNull() ?: 1

// versionName derives from the latest reachable git tag (v1.4.0 -> "1.4.0") so it can't
// drift from releases the way the old hard-coded string did. Override with SK_VERSION_NAME
// for CI / shallow clones with no tags; falls back to a literal if git or tags are absent.
// NOTE: reads the latest tag, so tag BEFORE building or the APK reports the prior version.
val skVersionName: String = System.getenv("SK_VERSION_NAME")
    ?: runCatching {
        val exec = providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0")
            isIgnoreExitValue = true
        }
        if (exec.result.get().exitValue == 0)
            exec.standardOutput.asText.get().trim().removePrefix("v")
        else null
    }.getOrNull()?.takeIf { it.isNotEmpty() }
    ?: "1.0.0"

android {
    namespace = "com.skarm.launcher"
    compileSdk = 35
    // Unified toolchain for app cmake + the submodule build scripts (gl4es/openal/
    // lwjgl). openal-soft's C++20 ranges need Clang 19+; NDK 27's Clang 18 miscompiles
    // them. Also gives 16 KB-aligned .so by default (forward-compat with 16 KB-page
    // Android 15+ devices). Keep in sync with NDK_VERSION in .github/workflows/build-apk.yml.
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.skarm.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = skVersionCode
        versionName = skVersionName

        ndk {
            // who even uses 32-bit
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Relative paths resolve against launcher/ (rootProject); absolute paths pass through.
                storeFile = rootProject.file(ksStoreFile!!)
                storePassword = ksStorePass
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Signed when a keystore is configured; otherwise emits app-release-unsigned.apk.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin jvmTarget defaults to compileOptions.targetCompatibility (17) under
    // AGP built-in Kotlin, so no explicit kotlinOptions block is needed.

    buildFeatures {
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // libgl4es.so is built via pre-requisite script
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Don't double compress
    androidResources {
        noCompress += listOf("xz", "tar")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")
}

val stageBootstrapJar by tasks.registering(Copy::class) {
    from(project(":bootstrap").tasks.named("jar"))
    into(layout.projectDirectory.dir("src/main/assets/sk"))
    rename(".*", "sk-bootstrap.jar")
}
tasks.named("preBuild") { dependsOn(stageBootstrapJar) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}
