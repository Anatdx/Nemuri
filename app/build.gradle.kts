plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun readSecret(vararg names: String): String? {
    fun String.cleaned() = trim().takeIf { it.isNotEmpty() }

    fun readFishVariable(name: String): String? {
        return providers.exec {
            commandLine("fish", "-lc", "if set -q $name; printf %s \\$$name; end")
        }.standardOutput.asText.get().cleaned()
    }

    return names.firstNotNullOfOrNull { name ->
        providers.gradleProperty(name).orNull?.cleaned()
            ?: providers.environmentVariable(name).orNull?.cleaned()
            ?: readFishVariable(name)
    }
}

val releaseStoreFile = readSecret(
    "NEMURI_KEYSTORE",
    "NEMURI_KEYSTORE_FILE",
    "NEMURI_STORE_FILE",
    "ANDROID_KEYSTORE",
    "ANDROID_STORE_FILE",
    "STORE_FILE"
)
val releaseStorePassword = readSecret(
    "NEMURI_KEYSTORE_PASSWORD",
    "NEMURI_STORE_PASSWORD",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_STORE_PASSWORD",
    "STORE_PASSWORD"
)
val releaseKeyAlias = readSecret(
    "NEMURI_KEY_ALIAS",
    "NEMURI_ALIAS",
    "ANDROID_KEY_ALIAS",
    "KEY_ALIAS"
)
val releaseKeyPassword = readSecret(
    "NEMURI_KEY_PASSWORD",
    "ANDROID_KEY_PASSWORD",
    "KEY_PASSWORD"
) ?: releaseStorePassword
val nemuriBaseVersion = "0.1.0"
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 0 }

android {
    namespace = "com.anatdx.nemuri"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.anatdx.nemuri"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount.get()
        versionName = "$nemuriBaseVersion-${gitCommitCount.get().toString().padStart(4, '0')}"
    }

    if (
        releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
    ) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)
    implementation(libs.libxposed.service)
    compileOnly(libs.libxposed.api)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
