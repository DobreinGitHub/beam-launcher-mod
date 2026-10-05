import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing key: kept outside the repository (see README). Without it - CI, other people's
// builds - the release build is signed with the debug key and installs fine, but can't update a
// Beam that was signed with the real one.
val releaseKey = Properties().apply {
    val file = File(System.getenv("BEAM_RELEASE_PROPS") ?: "${System.getProperty("user.home")}/.beam/release.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// The remote's four shortcut keys make the XGIMI firmware launch fixed Chinese video apps by
// package/activity name. These stubs take those names and hand the key over to Beam (the launcher).
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

    namespace = "com.home.tiles.stub"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }

    flavorDimensions += "button"
    productFlavors {
        fun button(name: String, index: Int, pkg: String, activity: String) = create(name) {
            dimension = "button"
            applicationId = pkg
            buildConfigField("int", "BUTTON", index.toString())
            manifestPlaceholders["firmwareActivity"] = activity
            manifestPlaceholders["label"] = "Beam: кнопка ${index + 1}"
        }
        button("button1", 0, "com.cibn.tv", "com.youku.tv.home.activity.HomeActivity")
        button("button2", 1, "com.ktcp.tvvideo", "com.ktcp.video.activity.HomeActivity")
        button("button3", 2, "com.gitvjimi.video", "com.gala.video.app.epg.HomeActivity")
        button("button4", 3, "com.hunantv.license", "com.mgtv.tv.launcher.ChannelHomeActivity")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("beamRelease") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
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
