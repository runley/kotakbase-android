import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release signing key: keystore.properties (not in git) names the key file outside the repo and its password
val keystore = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }
// release builds are for phones (arm64 only, half the size); debug ones also run on the x86_64 emulator
val releasing = gradle.startParameter.taskNames.any { it.contains("Release") }

android {
    namespace = "app.kotakbase"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kotakbase"
        minSdk = 26 // libmpv's minimum
        targetSdk = 36
        versionCode = 5 // raise both with every release; the update check compares versionName
        versionName = "0.1.4"
        ndk { abiFilters += if (releasing) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }
        // API client ids from secrets.properties (scripts/sync-desktop.mjs copies the desktop's); empty: sign-in is off
        val secrets = Properties().apply { rootProject.file("secrets.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }
        buildConfigField("String", "ANILIST_CLIENT_ID", "\"${secrets.getProperty("anilistClientId", "")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            if (!keystore.isEmpty) {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystore.isEmpty) null else signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3") // also brings OkHttp, used for AniList
    implementation("androidx.window:window:1.5.1") // fold state, for the tabletop layout
    implementation("androidx.webkit:webkit:1.18.0") // serves Watch Together's page from the app (a secure origin, for WebRTC)
    implementation("io.github.dokar3:quickjs-kt-android:1.0.18") // runs extensions and the desktop's shared JS

    testImplementation("junit:junit:4.13.2")
}
