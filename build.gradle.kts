// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ==========================================================================
// libportal.so 完整性哈希自动管理
// 在每次构建前检查 PortalIdentity.kt 中的版本信息，
// 自动计算 kExpectedHash 并更新 integrity.cpp。
// ==========================================================================

val MASK64 = java.math.BigInteger("FFFFFFFFFFFFFFFF", 16)
val FNV_PRIME = java.math.BigInteger("100000001B3", 16)
val FNV_SEED = java.math.BigInteger("811C9DC5", 16)

fun fnv1a(str: String, seed: java.math.BigInteger): java.math.BigInteger {
    var h = seed
    for (c in str.encodeToByteArray()) {
        h = h.xor(java.math.BigInteger.valueOf(c.toLong()))
        h = h.multiply(FNV_PRIME).and(MASK64)
    }
    return h
}

fun hashMix(val_: java.math.BigInteger, salt: java.math.BigInteger): java.math.BigInteger {
    var x = val_.multiply(salt).and(MASK64)
    x = x.xor(x.shiftRight(33))
    x = x.multiply(java.math.BigInteger("FF51AFD7ED558CCD", 16)).and(MASK64)
    x = x.xor(x.shiftRight(33))

    x = x.shiftLeft(17).or(x.shiftRight(47)).and(MASK64)
    x = x.multiply(java.math.BigInteger("C4CEB9FE1A85EC53", 16)).and(MASK64)
    x = x.xor(x.shiftRight(33))

    x = x.add(salt).and(MASK64)
    x = x.multiply(java.math.BigInteger("94D049BB133111EB", 16)).and(MASK64)
    x = x.xor(x.shiftRight(31))

    x = x.multiply(java.math.BigInteger("BF58476D1CE4E5B9", 16)).and(MASK64)
    x = x.xor(x.shiftRight(31))

    x = x.multiply(java.math.BigInteger("6D4A8F2E1B9C3D5E", 16)).and(MASK64)
    return x
}

fun computePortalHash(pkg: String, pkg2: String, version: String, versionCode: Long): String {
    val h1 = fnv1a(pkg, FNV_SEED)
    val h2 = fnv1a(pkg2, h1)
    val h3 = fnv1a(version, h2)
    val h4 = hashMix(h3, java.math.BigInteger.valueOf(versionCode))
    return h4.toString(16).padStart(16, '0').uppercase()
}

data class PortalIdentity(
    val pkg: String,
    val pkg2: String,
    val version: String,
    val versionCode: Long
)

fun readPortalIdentity(): PortalIdentity {
    val file = rootProject.file("xposed/src/main/java/tv/kyshu/xposed/utils/PortalIdentity.kt")
    if (!file.exists()) error("PortalIdentity.kt not found: ${file.absolutePath}")
    val text = file.readText()
    fun extract(pattern: Regex): String {
        return pattern.find(text)?.groupValues?.get(1)
            ?: error("Cannot match $pattern in PortalIdentity.kt")
    }
    val pkg = extract(Regex("""const val APPLICATION_ID\s*=\s*"([^"]+)""""))
    val pkg2 = extract(Regex("""const val LEGACY_PACKAGE_ID\s*=\s*"([^"]+)""""))
    val version = extract(Regex("""const val VERSION_NAME\s*=\s*"([^"]+)""""))
    val vcStr = extract(Regex("""const val VERSION_CODE\s*=\s*(\d+)"""))
    return PortalIdentity(pkg, pkg2, version, vcStr.toLong())
}

fun syncIntegrityHash(hashHex: String): Boolean {
    val cppFile = rootProject.file("xposed/src/main/cpp/integrity.cpp")
    if (!cppFile.exists()) {
        logger.warn("integrity.cpp not found, skipping hash update")
        return false
    }
    val text = cppFile.readText()
    val expectedLine = "static const uint64_t kExpectedHash = 0x${hashHex}ULL;"
    val currentMatch = Regex("""static const uint64_t kExpectedHash = 0x([0-9A-Fa-f]+)ULL;""").find(text)
    val currentHex = currentMatch?.groupValues?.get(1)?.uppercase()
    if (currentHex == hashHex) return false

    val updated = text.replace(
        Regex("""static const uint64_t kExpectedHash = 0x[0-9A-Fa-f]+ULL;"""),
        expectedLine
    )
    cppFile.writeText(updated)
    logger.lifecycle("Updated integrity.cpp: kExpectedHash = 0x${hashHex}")
    return true
}

val checkAndUpdateHash by tasks.registering {
    group = "build"
    description = "Auto-compute and sync libportal.so integrity hash with PortalIdentity"
    doLast {
        val identity = readPortalIdentity()
        logger.lifecycle("Portal identity: pkg=${identity.pkg}, pkg2=${identity.pkg2}, " +
            "version=${identity.version}, code=${identity.versionCode}")
        val hash = computePortalHash(identity.pkg, identity.pkg2, identity.version, identity.versionCode)
        logger.lifecycle("Computed hash: 0x${hash}")
        if (syncIntegrityHash(hash)) {
            logger.warn("integrity.cpp was out of sync and has been updated. Rebuild to pick up changes.")
        } else {
            logger.lifecycle("integrity.cpp hash is up to date.")
        }
    }
}

// Wire into build: run before every subproject's preBuild
project.afterEvaluate {
    rootProject.subprojects.forEach { sub ->
        sub.tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(checkAndUpdateHash)
        }
    }
}

tasks.register("checkIntegrityHash") {
    group = "verification"
    description = "Verify libportal.so integrity hash matches PortalIdentity"
    dependsOn(checkAndUpdateHash)
    doLast {
        val identity = readPortalIdentity()
        val hash = computePortalHash(identity.pkg, identity.pkg2, identity.version, identity.versionCode)
        val cppFile = rootProject.file("xposed/src/main/cpp/integrity.cpp")
        val text = cppFile.readText()
        val currentMatch = Regex("""static const uint64_t kExpectedHash = 0x([0-9A-Fa-f]+)ULL;""").find(text)
        val currentHex = currentMatch?.groupValues?.get(1)?.uppercase()
        if (currentHex == hash) {
            logger.lifecycle("PASSED: integrity.cpp hash matches PortalIdentity")
        } else {
            throw GradleException("FAILED: integrity.cpp hash 0x${currentHex} != computed 0x${hash}")
        }
    }
}
