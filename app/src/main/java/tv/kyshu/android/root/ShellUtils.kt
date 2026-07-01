package tv.kyshu.android.root

import android.util.Log

object ShellUtils {
    private const val TAG = "ShellUtils"

    // Whitelist of allowed root command prefixes
    private val ALLOWED_ROOT_COMMANDS = setOf(
        "cp ", "mv ", "rm ", "chmod ", "mkdir ", "sha256sum ",
        "setenforce ", "head ", "cat ", "ls ", "echo ",
    )

    /**
     * Validate a command string against the whitelist.
     * Returns true if the command is allowed.
     */
    private fun isCommandAllowed(command: String): Boolean {
        // Block NUL byte injection
        if (command.contains('\u0000')) {
            Log.w(TAG, "Blocked command with NUL byte: $command")
            return false
        }
        // Block shell metacharacters that enable multi-command execution
        val blocked = setOf(';', '|', '`', '$', '(', ')', '{', '}', '<', '>', '&', '\n', '\r')
        for (c in command) {
            if (c in blocked) {
                Log.w(TAG, "Blocked command with shell metacharacter '$c': $command")
                return false
            }
        }
        // Check command prefix is whitelisted
        val prefix = ALLOWED_ROOT_COMMANDS.firstOrNull { command.startsWith(it) }
        if (prefix == null) {
            Log.w(TAG, "Blocked command not in whitelist: $command")
            return false
        }
        return true
    }

    /**
     * Quote a single argument for shell safety.
     * Wraps in single quotes and escapes any single quotes inside.
     */
    fun quoteForRootShell(arg: String): String {
        val escaped = arg.replace("'", "'\\''")
        return "'$escaped'"
    }

    private fun execAsRoot(input: String): Pair<Int, String> {
        val runtime = Runtime.getRuntime()
        val process = runtime.exec("su")
        process.outputStream.write("$input\n".toByteArray())
        process.outputStream.write("exit\n".toByteArray())
        process.outputStream.flush()
        process.waitFor()
        val output = if (process.exitValue() != 0) {
            process.errorStream.bufferedReader().readText()
        } else {
            process.inputStream.bufferedReader().readText()
        }
        return Pair(process.exitValue(), output)
    }

    fun hasRoot(): Boolean {
        return try {
            val (exitCode, _) = execAsRoot("echo root_ok")
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun setEnforceMode(enabled: Boolean) {
        val cmd = "setenforce ${if (enabled) "1" else "0"}"
        if (!isCommandAllowed(cmd)) return
        try {
            execAsRoot(cmd)
        } catch (e: Exception) {
            Log.e(TAG, "setEnforceMode failed", e)
        }
    }

    fun executeCommand(command: String): String {
        if (!isCommandAllowed(command)) return ""
        return try {
            execAsRoot(command).second
        } catch (e: Exception) {
            Log.e(TAG, "executeCommand failed", e)
            ""
        }
    }

    fun executeCommandToBytes(command: String): ByteArray {
        if (!isCommandAllowed(command)) return ByteArray(0)
        val runtime = Runtime.getRuntime()
        return try {
            val process = runtime.exec("su")
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor()
            if (process.exitValue() != 0) {
                process.errorStream.use { it.readBytes() }
            } else {
                process.inputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeCommandToBytes failed", e)
            ByteArray(0)
        }
    }
}
