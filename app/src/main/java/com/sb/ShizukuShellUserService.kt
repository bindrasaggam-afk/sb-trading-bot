package com.sb

import com.sb.shizuku.IShizukuShellService

class ShizukuShellUserService : IShizukuShellService.Stub() {
    override fun execute(command: String): String {
        require(command.isNotBlank() && command.length <= 2048) {
            "Command must be 1–2048 characters"
        }
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return buildString {
            append(output)
            if (error.isNotBlank()) append("\n").append(error)
            append("\n[exit ").append(exitCode).append("]")
        }
    }
}
