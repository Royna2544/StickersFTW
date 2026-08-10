import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/** Signing material lives outside the repo (see .gitignore). Absent on a
 * fresh clone and on CI, which is why every use of it is null-guarded rather
 * than assumed: the project has to stay buildable for someone who does not
 * have the key, they just cannot produce a signed release. */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
/** Backslashes are escape characters in a .properties file, so a pasted
 * Windows path silently arrives mangled. Normalising to forward slashes
 * accepts either spelling instead of failing with an unsigned build and no
 * explanation. */
fun keystorePath(): File? = keystoreProperties.getProperty("storeFile")
    ?.replace('\\', '/')
    ?.let { path -> File(path).takeIf { it.exists() } ?: rootProject.file(path).takeIf { it.exists() } }

val hasSigningKey = keystorePath() != null

android {
    namespace = "com.royna.stickersftw"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.royna.ftw"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // Release-ish flags even in debug: the encoder runs the whole
                // quality ladder over every frame of a pack, and an -O0 build
                // of libwebp turns a slow conversion into an unusable one.
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-O2", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // r28 and later link 16KB-aligned by default, which is the entire reason
    // the webp encoder is built from source here rather than taken from a
    // prebuilt AAR.
    ndkVersion = "30.0.14904198"

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = keystorePath()
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Falls back to unsigned rather than failing the build, so a
            // clone without the key still compiles.
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
        debug {
            // Same key for debug too: the auto-generated debug keystore is
            // per-machine and gets regenerated, and a signature change forces
            // an uninstall on the next install -- which takes the app's data
            // with it.
            signingConfig = signingConfigs.findByName("release") ?: signingConfig
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.gson)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose.android)
    implementation(libs.androidx.datastore.preferences.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.squareup.retrofit)
    implementation(libs.squareup.retrofit.converter.gson)
    implementation(libs.squareup.okhttp)
    // Not debugImplementation: RetrofitProvider references HttpLoggingInterceptor
    // unconditionally and only gates it at runtime on BuildConfig.DEBUG, so the
    // type has to resolve when compiling release too. Release builds failed
    // outright before this.
    implementation(libs.squareup.okhttp.logging.interceptor)
    implementation(libs.coil.compose)
    implementation(libs.coil.android)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.airbnb.lottie)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}