plugins {
    id("com.android.application")
}

// The remote's four shortcut keys make the XGIMI firmware launch fixed Chinese video apps by
// package/activity name. These stubs take those names and hand the key over to Beam (the launcher).
android {
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
            signingConfig = signingConfigs.getByName("debug")
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
        // In CI the whole list of problems goes to the log, not just the first one.
        textReport = project.hasProperty("lintStrict")
        textOutput = file("stdout")
    }
}
