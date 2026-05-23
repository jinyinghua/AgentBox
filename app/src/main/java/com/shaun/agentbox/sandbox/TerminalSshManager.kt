package com.shaun.agentbox.sandbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Starts the app terminal as a direct proot-backed interactive shell.
 *
 * This class keeps the old name for compatibility with the UI wiring, but it no longer
 * starts an OpenSSH daemon. OpenSSH's sshd is not reliable inside Android/proot because
 * Android's seccomp policy can kill it with SIGSYS ("Bad system call").
 */
class TerminalSshManager(private val context: Context) {

    companion object {
        private const val LINUX_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }

    private val linuxManager = LinuxEnvironmentManager(context)
    private var shellProcess: Process? = null

    suspend fun ensureServerRunning(workspaceDir: File) = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }
    }

    suspend fun openShell(workspaceDir: File): TerminalShellSession = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }
        stopServerInternal()

        val command = """
            export HOME=/root USER=root LOGNAME=root TERM=xterm-256color PATH=$LINUX_PATH
            cd /workspace
            clear 2>/dev/null || true
            exec /bin/sh -i
        """.trimIndent()

        val processBuilder = ProcessBuilder(*linuxManager.buildProotCommand(workspaceDir, command))
            .directory(workspaceDir)
            .redirectErrorStream(true)

        val env = processBuilder.environment()
        env["PATH"] = LINUX_PATH
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["TERM"] = "xterm-256color"
        env["PROOT_TMP_DIR"] = linuxManager.tmpDir.absolutePath

        val process = processBuilder.start()
        shellProcess = process

        delay(200)
        if (!process.isAlive) {
            val output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            throw IllegalStateException("Shell failed to start." + if (output.isNotBlank()) "\n$output" else "")
        }

        TerminalShellSession(process, process.inputStream, process.outputStream)
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        stopServerInternal()
    }

    private fun stopServerInternal() {
        val process = shellProcess
        shellProcess = null
        if (process != null) {
            runCatching { process.outputStream.write("exit\n".toByteArray()) }
            runCatching { process.outputStream.flush() }
            runCatching { process.outputStream.close() }
            runCatching { process.destroy() }
            runCatching { process.waitFor() }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
            }
        }
    }
}
