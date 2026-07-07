import com.android.build.api.dsl.ApplicationExtension
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}


loadDotEnv(project)
configureAppSigningConfigsForRelease(project)
android {
    namespace = "tv.kyshu"
    compileSdk = 35

    defaultConfig {
        applicationId = "tv.kyshu"
        minSdk = 26
        targetSdk = 35
        versionCode = getVersionCode()
        versionName = getVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        manifestPlaceholders["BAIDU_MAP_KEY"] = getBaiduMapKey()

//        val googleServicesFile = project.file("google-services.json")
//        if (!googleServicesFile.exists()) {
//            throw GradleException("在 CI 环境中必须提供 google-services.json 文件!")
//        }

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
        }
    }

    android.applicationVariants.all {
        val abiName = when {
            name.contains("App", ignoreCase = true) -> "all"
            name.contains("Arm64", ignoreCase = true) -> "arm64"
            name.contains("X64", ignoreCase = true) || name.contains("X86", ignoreCase = true) -> "x86_64"
            else -> name.lowercase()
        }
        outputs.forEach { output ->
            (output as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "Portal-${versionName}-${abiName}.apk"
        }
    }

    flavorDimensions.add("mode")
    productFlavors {
        create("app") {
            dimension = "mode"
            ndk {
                println("Full architecture and full compilation.")
                abiFilters.add("arm64-v8a")
                abiFilters.add("x86_64")
            }
        }
        create("arm64") {
            dimension = "mode"
            ndk {
                println("Full compilation of arm64 architecture")
                abiFilters.add("arm64-v8a")
            }
        }
        create("x64") {
            dimension = "mode"
            ndk {
                println("Full compilation of x64 architecture")
                abiFilters.add("x86_64")
            }
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
        viewBinding = true
    }
    packaging {
        jniLibs {
            // LSPosed loads module JNI libs from base.apk!/lib/<abi>.
            // Keep them uncompressed so System.loadLibrary can dlopen libportal.so.
            useLegacyPackaging = false
            excludes += "lib/armeabi/**"
            excludes += "lib/x86/**"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/DEPENDENCIES.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/dependencies.txt"
            excludes += "/META-INF/LGPL2.1"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/services/reactor.blockhound.integration.BlockHoundIntegration"
            excludes += "lib/armeabi/**"
            excludes += "lib/x86/**"
        }
    }
    sourceSets {
        getByName("main").jniLibs.srcDirs("libs")
    }

}

fun loadDotEnv(project: Project) {
    val envFile = project.rootProject.file(".env")
    if (!envFile.isFile) return
    val props = Properties()
    try {
        FileInputStream(envFile).use { stream -> props.load(stream) }
    } catch (e: Exception) {
        println("Warning: Failed to load .env: ${e.message}")
        return
    }
    for (key in props.stringPropertyNames()) {
        val value = props.getProperty(key)
        if (System.getenv(key).isNullOrBlank() && !value.isNullOrBlank()) {
            System.setProperty("dotenv.$key", value)
        }
    }
}

fun dotEnvVar(key: String): String? {
    val injected = System.getProperty("dotenv.$key")
    if (!injected.isNullOrBlank()) return injected
    return null
}

fun configureAppSigningConfigsForRelease(project: Project) {
    val keystorePath = System.getenv("KEYSTORE_PATH").takeIf { !it.isNullOrBlank() }
        ?: dotEnvVar("KEYSTORE_PATH")
    if (keystorePath.isNullOrBlank()) return
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD").takeIf { !it.isNullOrBlank() }
        ?: dotEnvVar("KEYSTORE_PASSWORD") ?: return
    val keyAliasName = System.getenv("KEY_ALIAS").takeIf { !it.isNullOrBlank() }
        ?: dotEnvVar("KEY_ALIAS") ?: return
    val keyPass = System.getenv("KEY_PASSWORD").takeIf { !it.isNullOrBlank() }
        ?: dotEnvVar("KEY_PASSWORD") ?: return
    project.configure<ApplicationExtension> {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPass
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.findByName("release")
            }
            debug {
                signingConfig = signingConfigs.findByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.okhttp)

    implementation(libs.fastjson)
    implementation(libs.kotlinx.serialization)
    implementation(libs.reflect)

    implementation(libs.geotools)
    implementation(fileTree(mapOf(
        "dir" to "libs",
        "include" to listOf("*.jar")
    )))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

fun getVersionCode(): Int {
    // Git commit count as unique versionCode (单调递增)
    val out = ByteArrayOutputStream()
    try {
        exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        val count = out.toString().trim().toIntOrNull()
        if (count != null && count > 0) {
            return count.coerceAtMost(Integer.MAX_VALUE)
        }
    } catch (_: Exception) { }
    // fallback: timestamp-based to ensure uniqueness
    return (System.currentTimeMillis() / 1000L % Integer.MAX_VALUE).toInt()
}

fun getGitCommitHash(): String {
    val out = ByteArrayOutputStream()
    try {
        exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        val hash = out.toString().trim()
        if (hash.isNotBlank()) return hash
    } catch (_: Exception) { }
    return "unknown"
}

fun getVersionName(): String {
    val hash = getGitCommitHash()
    val dateOut = ByteArrayOutputStream()
    try {
        exec {
            commandLine("git", "log", "-1", "--format=%cd", "--date=format:%Y%m%d-%H%M")
            standardOutput = dateOut
            isIgnoreExitValue = true
        }
        val date = dateOut.toString().trim()
        if (date.isNotBlank()) return "1.0.8-$hash-$date"
    } catch (_: Exception) { }
    return "1.0.8-$hash"
}

fun getBaiduMapKey(): String {
    // Priority: env BAIDU_MAP_KEY > .env > gradle.properties > fail
    val envKey = System.getenv("BAIDU_MAP_KEY")
    if (!envKey.isNullOrBlank()) return envKey
    val dotenvKey = dotEnvVar("BAIDU_MAP_KEY")
    if (!dotenvKey.isNullOrBlank()) return dotenvKey
    val propKey = project.findProperty("baiduMapKey") as? String
    if (!propKey.isNullOrBlank()) return propKey
    throw GradleException(
        "Baidu Map API key not configured. " +
        "Set BAIDU_MAP_KEY environment variable or baiduMapKey in gradle.properties."
    )
}

