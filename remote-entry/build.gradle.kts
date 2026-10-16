import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing key: kept outside the repository (see README), the same as Beam Mod's, so that
// Beam Mod's entry for this app (a signature permission) lets it in.
val releaseKey = Properties().apply {
    val file = File(System.getenv("BEAM_RELEASE_PROPS") ?: "${System.getProperty("user.home")}/.beam/release.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// The russifier's XGIMI settings have an entry «Общие → Кнопки пульта» that opens exactly
// com.beam.remote/.RemoteActivity (tonisaf's module). This app takes that name and opens Beam Mod's
// «Кнопки пульта» page instead; nothing else.
android {
    signingConfigs {
        if (releaseKey.isNotEmpty()) {
            create("beamRelease") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    namespace = "com.beam.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.beam.remote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("beamRelease") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = project.hasProperty("lintStrict")
    }
}