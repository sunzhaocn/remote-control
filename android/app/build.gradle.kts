plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.simplerc.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.simplerc.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 41
        versionName = "0.3.38"
        buildConfigField("boolean", "FIXED_80_COMPARE", "false")
        buildConfigField("boolean", "FIXED_88_COMPARE", "false")
        buildConfigField("String", "LAN_STUN", "\"\"")
        buildConfigField("String", "LAN_TEST_STUN", "\"\"")
        buildConfigField("boolean", "LAN_TEST_SOFTWARE_DECODER", "false")
        buildConfigField("boolean", "LAN_TEST_120", "false")
        buildConfigField("boolean", "LAN_TEST_32MBPS", "false")
        buildConfigField("long", "REMOTE_TAP_DURATION_MS", "50L")
        buildConfigField("long", "REMOTE_DRAG_SEGMENT_DURATION_MS", "24L")
    }

    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("simplercShortTap16").orNull == "true") {
                buildConfigField("long", "REMOTE_TAP_DURATION_MS", "16L")
            }
            if (providers.gradleProperty("simplercShortDrag16").orNull == "true") {
                buildConfigField("long", "REMOTE_DRAG_SEGMENT_DURATION_MS", "16L")
            }
            providers.gradleProperty("simplercLanStun").orNull?.let { url ->
                require(Regex("stun:[0-9.]+:[0-9]{1,5}").matches(url))
                buildConfigField("String", "LAN_STUN", "\"$url\"")
            }
            if (providers.gradleProperty("simplercLanTestSoftwareDecoder").orNull == "true") {
                buildConfigField("boolean", "LAN_TEST_SOFTWARE_DECODER", "true")
            }
            providers.gradleProperty("simplercLanTestStun").orNull?.let { url ->
                require(Regex("stun:[0-9.]+:[0-9]{1,5}").matches(url))
                buildConfigField("String", "LAN_TEST_STUN", "\"$url\"")
                val test120 = providers.gradleProperty("simplercLanTest120").orNull == "true"
                if (test120) {
                    buildConfigField("boolean", "LAN_TEST_120", "true")
                }
                if (test120 || providers.gradleProperty("simplercLanTest32Mbps").orNull == "true") {
                    buildConfigField("boolean", "LAN_TEST_32MBPS", "true")
                }
            }
        }
        create("compare80") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".compare80"
            versionNameSuffix = "-compare80"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "FIXED_80_COMPARE", "true")
        }
        create("compare88") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".compare88"
            versionNameSuffix = "-compare88"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "FIXED_88_COMPARE", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(files("libs/stream-webrtc-android-simplerc-1.3.10.aar"))
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}

