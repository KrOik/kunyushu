// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * 验证 libportal.so 完整性哈希与 PortalIdentity 匹配。
 * 当版本号变化时，需运行 scripts/compute_integrity_hash.py --auto --update 更新 kExpectedHash。
 *
 * Usage: ./gradlew checkIntegrityHash
 */
tasks.register("checkIntegrityHash") {
    group = "verification"
    description = "Verify libportal.so integrity hash matches PortalIdentity"
    doLast {
        val script = rootProject.file("scripts/compute_integrity_hash.py")
        if (!script.exists()) {
            throw GradleException("Hash verification script not found: ${script.absolutePath}")
        }
        val pythonCmd = if (org.gradle.internal.os.OperatingSystem.current().isWindows()) "python" else "python3"
        val result = exec {
            commandLine(pythonCmd, script.absolutePath, "--auto")
        }
        if (result.exitValue and 0xFF != 0) {
            throw GradleException("Integrity hash verification FAILED")
        }
        logger.lifecycle("Integrity hash verification PASSED")
    }
}