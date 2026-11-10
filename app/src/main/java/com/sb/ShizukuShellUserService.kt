package com.sb

import com.sb.shizuku.IShizukuShellService

class ShizukuShellUserService : IShizukuShellService.Stub() {
    override fun execute(command: String): String {
        require(command.isNotBlank() && command.length <= 2048) {
            "Command must be 1–2048 characters"
        }
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return buildString {
            append(output)
            append("\n[exit ").append(exitCode).append("]")
        }
    }
}
