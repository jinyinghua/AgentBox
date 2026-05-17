package com.shaun.agentbox.sandbox

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 负责维护应用内终端使用的持久化 SSH daemon。
 */
class TerminalSshManager(private val context: Context) {

    companion object {
        private const val TAG = "TerminalSshManager"
        private const val STARTUP_TIMEOUT_MS = 15_000L
    }

    private val linuxManager = LinuxEnvironmentManager(context)
    private val daemonPidFile = File(linuxManager.rootfsDir, "var/run/sshd.pid")
    private var daemonProcess: Process? = null
    private val starting = AtomicBoolean(false)

    suspend fun ensureServerRunning(workspaceDir: File) = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }
        linuxManager.ensureSshPrepared()

        if (isServerAlive()) return@withContext
        if (!starting.compareAndSet(false, true)) {
            waitUntilAlive()
            return@withContext
        }

        try {
            stopServerInternal()
            val processBuilder = ProcessBuilder(*linuxManager.buildSshDaemonCommand(workspaceDir))
                .directory(workspaceDir)
                .redirectErrorStream(true)

            val env = processBuilder.environment()
            env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            env["HOME"] = "/root"
            env["USER"] = "root"
            env["LOGNAME"] = "root"
            env["TERM"] = "xterm-256color"
            env["PROOT_TMP_DIR"] = linuxManager.tmpDir.absolutePath

            daemonProcess = processBuilder.start()
            waitUntilAlive()
        } finally {
            starting.set(false)
        }
    }

    suspend fun openShell(workspaceDir: File): TerminalShellSession = withContext(Dispatchers.IO) {
        ensureServerRunning(workspaceDir)

        val jsch = JSch()
        jsch.addIdentity(linuxManager.getSshPrivateKeyFile().absolutePath)
        val session = jsch.getSession(linuxManager.getSshUser(), linuxManager.getSshHost(), linuxManager.getSshPort())
        session.setConfig(Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "publickey")
        })
        session.timeout = 10_000
        session.connect(10_000)

        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color", 160, 48, 1280, 720)
        channel.setInputStream(null)
        val input = channel.inputStream
        val output = channel.outputStream
        channel.connect(10_000)

        TerminalShellSession(session, channel, input, output)
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        stopServerInternal()
    }

    private suspend fun waitUntilAlive() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STARTUP_TIMEOUT_MS) {
            if (isServerAlive()) return
            val process = daemonProcess
            if (process != null && !process.isAlive) {
                val output = runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
                throw IllegalStateException("sshd failed to start. $output")
            }
            delay(200)
        }
        throw IllegalStateException("Timed out waiting for sshd to start.")
    }

    private fun isServerAlive(): Boolean {
        return try {
            if (!daemonPidFile.exists()) return false
            val pid = daemonPidFile.readText().trim().toIntOrNull() ?: return false
            File("/proc/$pid").exists()
        } catch (e: Exception) {
            Log.w(TAG, "isServerAlive check failed", e)
            false
        }
    }

    private fun stopServerInternal() {
        runCatching {
            if (daemonPidFile.exists()) {
                val pid = daemonPidFile.readText().trim().toIntOrNull()
                if (pid != null) {
                    Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-TERM", pid.toString())).waitFor()
                }
                daemonPidFile.delete()
            }
        }
        runCatching {
            daemonProcess?.destroy()
            daemonProcess?.waitFor()
        }
        daemonProcess = null
    }
}
