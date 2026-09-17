plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Push notifications (Firebase Cloud Messaging) need the Firebase project's google-services.json in this module.
// The file is Git-ignored and added locally; without it the app builds and runs without push notifications.
val pushConfigured = file("google-services.json").exists()
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

android {
    namespace = "com.dynamictransport.driver"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dynamictransport.driver"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PUSH_CONFIGURED", pushConfigured.toString())
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_ENVIRONMENT", "\"development\"")
            buildConfigField("String", "API_BASE_URL", "\"$developmentApiBaseUrl\"")
            buildConfigField("boolean", "DEMO_MODE", debugDemoMode.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "API_ENVIRONMENT", "\"production\"")
            buildConfigField("String", "API_BASE_URL", "\"$productionApiBaseUrl\"")
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
}
