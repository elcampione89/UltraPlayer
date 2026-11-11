plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val panelUrl = (project.findProperty("PANEL_URL") as String?) ?: ""
val appVersionCode = ((project.findProperty("APP_VERSION_CODE") as String?) ?: "2000").toInt()
val appVersionName = (project.findProperty("APP_VERSION_NAME") as String?) ?: "4.0"

android {
    namespace = "tech.daemonhub.ultraplayer"
    compileSdk = 36

    // CI writes the keystore from GitHub secrets; without one, release falls back to debug signing.
    val releaseStore = rootProject.file(System.getenv("MTV_KEYSTORE_FILE") ?: "keystore.jks")
    signingConfigs {
        create("release") {
            storeFile = releaseStore
            storePassword = System.getenv("MTV_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("MTV_KEY_ALIAS") ?: "materialtv"
            keyPassword = System.getenv("MTV_KEY_PASSWORD") ?: ""
        }
    }

    defaultConfig {
        applicationId = "tech.daemonhub.ultraplayer"
        minSdk = 22
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "PANEL_URL", "\"$panelUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStore.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            (this as com.android.build.gradle.internal.api.ApkVariantOutputImpl).outputFileName =
                "UltraPlayer-${variant.versionName}.apk"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2025.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
}
