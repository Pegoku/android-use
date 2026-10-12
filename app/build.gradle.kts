import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val signingProperties = Properties().apply {
    val config = rootProject.file("keystore.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
fun signingValue(property: String, environment: String): String? =
    signingProperties.getProperty(property) ?: providers.environmentVariable(environment).orNull
val releaseStore = signingValue("storeFile", "KEYSTORE_FILE")

android {
    namespace = "dev.pegoku.androiduse"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pegoku.androiduse"
        minSdk = 28
        targetSdk = 37
        versionCode = providers.gradleProperty("versionCode").orElse("2").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("0.2.0").get()
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = requireNotNull(signingValue("storePassword", "KEYSTORE_PASSWORD"))
                keyAlias = requireNotNull(signingValue("keyAlias", "KEY_ALIAS"))
                keyPassword = requireNotNull(signingValue("keyPassword", "KEY_PASSWORD"))
            }
        }
    }

    buildTypes {
        release {
            // The Shizuku user service is instantiated reflectively in a separate process;
            // keep everything unobfuscated rather than maintaining keep rules.
            isMinifyEnabled = false
            // Without a configured release key, fall back to the debug key so the APK still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
