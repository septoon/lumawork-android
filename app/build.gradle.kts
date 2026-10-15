import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val signingProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun xcconfigValue(key: String): String? = rootProject.file("../LumaWork/Secrets.xcconfig")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.substringBefore('=').trim() == key }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf(String::isNotEmpty)
fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
val wikiApiToken = providers.gradleProperty("WIKI_API_TOKEN").orNull
    ?: localProperties.getProperty("WIKI_API_TOKEN")
    ?: xcconfigValue("WIKI_API_TOKEN")
    ?: ""
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull() ?: 1
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull ?: "0.1.0"
val releaseStoreFile = signingProperties.getProperty("storeFile")?.takeIf(String::isNotBlank)
val releaseKeyAlias = signingProperties.getProperty("keyAlias")?.takeIf(String::isNotBlank)
val releaseStorePassword = providers.environmentVariable("LUMAWORK_ANDROID_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("LUMAWORK_ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "ru.lumastack.lumawork"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.lumastack.lumawork"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField("String", "API_ORIGIN", "\"https://api.work.lumastack.ru\"")
        buildConfigField("String", "SIMPLEONE_API_ORIGIN", "\"https://sd.servionica.ru/v1\"")
        buildConfigField("String", "WIKI_API_ORIGIN", "\"https://wiki.lumastack.ru\"")
        buildConfigField("String", "WIKI_API_TOKEN", buildConfigString(wikiApiToken))
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
