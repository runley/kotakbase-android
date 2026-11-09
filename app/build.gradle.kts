plugins {
    id("com.android.application")
}

android {
    namespace = "app.kotakbase"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kotakbase"
        minSdk = 26 // libmpv's minimum
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // the Fold, and the emulator
    }
}

dependencies {
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
}
