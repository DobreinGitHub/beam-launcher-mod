plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.home.tiles"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.home.tiles"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // tv: the XGIMI projector (remote, firmware integrations). tablet: touch, any orientation.
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            buildConfigField("boolean", "TV", "true")
            manifestPlaceholders["orientation"] = "landscape"
        }
        create("tablet") {
            dimension = "device"
            applicationIdSuffix = ".tablet"
            buildConfigField("boolean", "TV", "false")
            manifestPlaceholders["orientation"] = "unspecified"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded only, so the debug key is fine.
            signingConfig = signingConfigs.getByName("debug")
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
}
