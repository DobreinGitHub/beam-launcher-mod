import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key: kept outside the repository (see README). Without it - CI, other people's
// builds - the release build is signed with the debug key and installs fine, but can't update a
// Beam that was signed with the real one.
val releaseKey = Properties().apply {
    val file = File(System.getenv("BEAM_RELEASE_PROPS") ?: "${System.getProperty("user.home")}/.beam/release.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

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

    namespace = "com.home.tiles"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.home.tiles"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.3-ru2"
        // The projector is 32-bit ARM; keeps Vosk/JNA native libraries to the one ABI.
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("beamRelease") ?: signingConfigs.getByName("debug")
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
    }
    lint {
        checkReleaseBuilds = false
        // CI passes -PlintStrict: lint errors fail the build there; locally they only land in the report.
        abortOnError = project.hasProperty("lintStrict")
    }
    testOptions {
        // Android's own classes (Log, Handler, ...) are stubs on the JVM: let them return defaults.
        unitTests.isReturnDefaultValues = true
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
    // Offline speech recognition for the remote's voice key.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
}
