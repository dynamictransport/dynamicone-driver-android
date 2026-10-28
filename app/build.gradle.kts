import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

val dynamicOneApplicationId = "com.dynamictransport.dynamicone"

// Push notifications (Firebase Cloud Messaging) need the Firebase project's google-services.json in this module.
// The file is Git-ignored and added locally; without it, or while it has no Android app registered for this
// application id (e.g. a file downloaded for the former driver app id), the app builds and runs without push.
val googleServicesFile = file("google-services.json")
val pushConfigured = googleServicesFile.exists() &&
    Regex("\"package_name\"\\s*:\\s*\"${Regex.escape(dynamicOneApplicationId)}\"").containsMatchIn(googleServicesFile.readText())
if (pushConfigured) {
    apply(plugin = "com.google.gms.google-services")
}

// Central API environment configuration. Screens never contain URLs.
// Debug  -> development DynamicONE backend (default: https://localhost:7167 reached through `adb reverse`).
// Release -> production DynamicONE backend (overridable until the new Driver API is confirmed live there).
// Demo data can never be enabled in a release build.
val developmentApiBaseUrl = providers.gradleProperty("driver.devApiBaseUrl")
    .getOrElse("https://localhost:7167/api/driver/v1/")
val productionApiBaseUrl = providers.gradleProperty("driver.prodApiBaseUrl")
    .getOrElse("https://dynamicone.ai/api/driver/v1/")
val debugDemoMode = providers.gradleProperty("driver.demoMode").getOrElse("false").toBoolean()

listOf(developmentApiBaseUrl, productionApiBaseUrl).forEach { url ->
    require(url.startsWith("https://") && url.endsWith("/api/driver/v1/")) {
        "Driver API base URLs must use HTTPS and end with /api/driver/v1/: $url"
    }
}

// DynamicONE Mobile API (docs/15_MOBILE_APP_API.md): sign-in, modules and office attendance on the same host.
// Default since 30.09.2026; `app.mobileApi=false` falls back to signing drivers in through the Driver API.
fun mobileApiBaseUrl(driverApiBaseUrl: String) = driverApiBaseUrl.removeSuffix("/api/driver/v1/") + "/api/mobile/v1/"
val mobileApiEnabled = providers.gradleProperty("app.mobileApi").getOrElse("true").toBoolean()

// Driver daily shift (docs/16_DRIVER_SHIFT_REST_AND_VEHICLE_CHECK.md): tachograph rest, wake-up alarm, vehicle tag and
// daily check. Until the backend serves the endpoints, `app.driverShift=false` keeps the shift "not connected".
val driverShiftEnabled = providers.gradleProperty("app.driverShift").getOrElse("false").toBoolean()

// Release signing (Intune line-of-business APK for the managed vehicle phones). The keystore and its passwords stay
// outside Git: `keystore.properties` in the project root (Git-ignored) holds storeFile, storePassword, keyAlias and
// keyPassword. Without it, release builds stay unsigned and cannot be installed.
val releaseKeystoreFile = rootProject.file("keystore.properties")
val releaseKeystore = Properties().apply {
    if (releaseKeystoreFile.exists()) releaseKeystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.dynamictransport.dynamicone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = dynamicOneApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PUSH_CONFIGURED", pushConfigured.toString())
        buildConfigField("boolean", "MOBILE_API_ENABLED", mobileApiEnabled.toString())
        buildConfigField("boolean", "DRIVER_SHIFT_ENABLED", driverShiftEnabled.toString())
    }

    signingConfigs {
        if (releaseKeystoreFile.exists()) {
            create("release") {
                storeFile = file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_ENVIRONMENT", "\"development\"")
            buildConfigField("String", "API_BASE_URL", "\"$developmentApiBaseUrl\"")
            buildConfigField("String", "MOBILE_API_BASE_URL", "\"${mobileApiBaseUrl(developmentApiBaseUrl)}\"")
            buildConfigField("boolean", "DEMO_MODE", debugDemoMode.toString())
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "API_ENVIRONMENT", "\"production\"")
            buildConfigField("String", "API_BASE_URL", "\"$productionApiBaseUrl\"")
            buildConfigField("String", "MOBILE_API_BASE_URL", "\"${mobileApiBaseUrl(productionApiBaseUrl)}\"")
            buildConfigField("boolean", "DEMO_MODE", "false")
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

    // Debug builds can trust this PC's ASP.NET Core development certificate on physical devices
    // (tools/Export-DevHttpsCertificate.ps1). The exported public certificate is Git-ignored; without it
    // the debug build keeps the main network security config. Release builds never include it.
    if (file("dev-https/res/raw/dev_https_certificate.cer").exists()) {
        sourceSets.getByName("debug").res.directories.add("dev-https/res")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric (SDK 36+) on JDK 17+ needs access to JDK internals.
                test.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                // Robolectric's native runtime loader cannot handle spaces in the user home path
                // (e.g. "C:\Users\First Last"); keep its android-all jars in the project's .gradle dir.
                test.systemProperty("maven.repo.local", rootProject.file(".gradle/robolectric-m2").absolutePath)
                // App-level Robolectric tests run the in-app demo backend; honoured only by debug builds.
                test.systemProperty("driver.backendMode", "demo")
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Dependency upgrades are a deliberate decision (stable-only policy), not a lint failure.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Same pin as the unit tests: the Espresso 3.5.0 of Compose ui-test cannot inject input on SDK 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
