import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One canonical HTTPS backend for the released MotionMagic app.
// Keeping it fixed prevents an empty Gradle property from disabling login.
val motionMagicApiBaseUrl = "https://motionmagicai.onrender.com"
val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties().apply {
    FileInputStream(versionPropertiesFile).use(::load)
}
val playVersionCode = versionProperties.getProperty("VERSION_CODE")?.toIntOrNull()
    ?: error("VERSION_CODE in version.properties must be a whole number")

android {
    namespace = "com.gamixtv.MotionMagicAI"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.gamixtv.MotionMagicAI"
        minSdk = 26
        targetSdk = 36
        // Read the next Play upload number from the tracked version file.
        versionCode = playVersionCode
        versionName = "0.8.7"
        buildConfigField("String", "AUTH_BASE_URL", "\"$motionMagicApiBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 removes unused code and obfuscates released bytecode for Google Play.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".build-tools/debug.keystore")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// `bundleRelease` uses the current number, then advances it only after a
// successful bundle. The next bundle is therefore always one higher.
tasks.configureEach {
    if (name != "bundleRelease") return@configureEach
    // A Play upload must be a newly built bundle; never skip the increment
    // because Gradle considers an older release bundle up to date.
    outputs.upToDateWhen { false }
    doLast {
        versionProperties.setProperty("VERSION_CODE", (playVersionCode + 1).toString())
        FileOutputStream(versionPropertiesFile).use { versionProperties.store(it, "Next Google Play version code") }
        logger.lifecycle("Next Google Play version code: ${playVersionCode + 1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Modern Google Play Billing; its manifest metadata replaces the legacy
    // AIDL billing path detected by Play Console.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

