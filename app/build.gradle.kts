import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// CI passes its run number so every published APK has a higher versionCode than the last.
val buildNumber = providers.environmentVariable("BUILD_NUMBER").orElse("1").get().toInt()

// Optional private release key (see README). Without it, release builds use the repo's shared key.
val releaseKeystore = providers.environmentVariable("SIGNING_KEYSTORE").orNull

android {
    namespace = "io.github.ramziag.weather"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ramziag.weather"
        minSdk = 30
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Committed on purpose so that local and CI builds can update each other in place.
        getByName("debug") {
            storeFile = rootProject.file("keystore/shared.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        // Skip Google's encrypted dependency blob; nothing reads it for a sideloaded app.
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/*.version", "/META-INF/**/*.kotlin_module", "/kotlin/**", "/DebugProbesKt.bin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is stubbed out in local unit tests; use the real implementation there.
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<Test>().configureEach {
    // LIVE_API=1 also runs the tests that call the real Open-Meteo API (CI does this).
    environment("LIVE_API", providers.environmentVariable("LIVE_API").orElse("").get())
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    // Robolectric touches JDK internals (file descriptors) that Java 17+ hides by default.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
