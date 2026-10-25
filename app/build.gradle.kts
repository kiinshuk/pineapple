import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.torrenttunes.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.torrenttunes.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        // CI must not depend on where the runner keeps Android's default key.
        // Without this variable, local debug signing remains unchanged.
        providers.environmentVariable("CI_DEBUG_KEYSTORE").orNull?.let { keystorePath ->
            getByName("debug") {
                storeFile = rootProject.file(keystorePath).also {
                    check(it.isFile) { "CI debug keystore not found: $it" }
                }
                storeType = "JKS"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        // Signed with a real keystore when `keystore.properties` is present,
        // and with the debug key when it is not.
        //
        // The fallback matters: without it, `assembleRelease` simply fails on
        // a fresh clone, which means nobody ever tests the minified build —
        // and an untested minified build is where R8 surprises live. With it,
        // you get an installable release APK immediately, and the keystore
        // only becomes necessary once an update has to land on top of an
        // existing install rather than be refused as a different signer.
        //
        // The keystore itself is never committed.
        create("release") {
            val props = rootProject.file("keystore.properties")
            if (props.exists()) {
                // Imported rather than `java.util.Properties`: in the Gradle
                // Kotlin DSL, `java` resolves to the Java plugin extension.
                val loaded = Properties().apply {
                    props.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(loaded.getProperty("storeFile", ""))
                storePassword = loaded.getProperty("storePassword")
                keyAlias = loaded.getProperty("keyAlias")
                keyPassword = loaded.getProperty("keyPassword")
            } else {
                val debug = signingConfigs.getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Deliberately unminified: the point of a debug build is a stack
            // trace you can actually read when something goes wrong.
            isMinifyEnabled = false
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
        // Lint crashes on this AGP version while analysing Hilt's generated
        // code — `NonNullableMutableLiveDataDetector` throws
        // IncompatibleClassChangeError, and the message says outright that it
        // is a bug in lint. That is a tooling failure, not a finding, so it
        // should not be able to block a release build.
        //
        // Lint itself is not switched off: `gradlew lint` still produces the
        // report, it just no longer runs as a gate on `assembleRelease`.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Playback — ExoPlayer reads the resolved stream URLs directly, so no
    // local server and no extra datasource adapter are involved.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Asynchronous artwork loading with built-in memory + disk caching.
    // Used as the single image path in PineappleUi (replaces the hand-rolled
    // downloadBitmap pump so tiles paint immediately and never flash blank).
    implementation(libs.coil.compose)

    // InnerTube parsing is pure Kotlin and is exactly the logic worth pinning
    // without a phone: it is the part that breaks when YouTube changes schema.
    //
    // The real org.json is required — Android's copy is a stubbed jar in unit
    // tests and throws "not mocked" on every call.
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
