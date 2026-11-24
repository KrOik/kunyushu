plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tv.kyshu.xposed"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
        ndk {
            abiFilters.addAll(arrayOf("arm64-v8a", "x86_64"))
        }
        configureMockDomainBuildFlags()
    }

    buildTypes {
        release {
            // Shrink once at the application boundary, where R8 sees all callers.
            // Pre-shrinking this library removes/renames APIs used by the app and JVM
            // release tests. consumer-rules.pro preserves reflective/JNI entry points.
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
        prefab = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    lint {
        // 存量 NewApi 问题固化到 baseline（自 VoxLoction 移植 verificationGate 时的既有债务），
        // baseline 之外的新的 lint 违规仍会使构建失败。
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    compileOnly(project(":system-api"))
    implementation(project(":nmea"))

    // Just Test?
    //noinspection GradleDynamicVersion
    //implementation("org.lsposed.lsplant:lsplant-standalone:+")
    //noinspection UseTomlInstead
    // Dobby: update with care — verify ABI compatibility across all targets
    implementation("io.github.vvb2060.ndk:dobby:1.2")  // Tracked via ndk/dobby; semver stable

    testImplementation(libs.junit)
    // compileOnly is correct for the APK, but JVM tests also load hook classes.
    testImplementation(libs.xposed.api)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

fun featureFlag(name: String, defaultValue: Boolean = false): Boolean {
    val value = project.findProperty(name)?.toString()
        ?: System.getenv(name.uppercase().replace('.', '_'))
        ?: return defaultValue
    return value.equals("true", ignoreCase = true) ||
        value == "1" ||
        value.equals("yes", ignoreCase = true)
}

fun com.android.build.api.dsl.LibraryDefaultConfig.configureMockDomainBuildFlags() {
    buildConfigField("boolean", "ENABLE_LOCATION_MOCK", featureFlag("enableLocationMock", true).toString())
    buildConfigField("boolean", "ENABLE_GNSS_MOCK", featureFlag("enableGnssMock", true).toString())
    buildConfigField("boolean", "ENABLE_NMEA_MOCK", featureFlag("enableNmeaMock", true).toString())
    buildConfigField("boolean", "ENABLE_SENSOR_MOCK", featureFlag("enableSensorMock", true).toString())
    buildConfigField("boolean", "ENABLE_WIFI_MOCK", featureFlag("enableWifiMock", true).toString())
    buildConfigField("boolean", "ENABLE_CELL_MOCK", featureFlag("enableCellMock", true).toString())
    buildConfigField("boolean", "ENABLE_BLE_MOCK", featureFlag("enableBleMock", true).toString())
    buildConfigField("boolean", "ENABLE_NFC_MOCK", featureFlag("enableNfcMock").toString())
}
