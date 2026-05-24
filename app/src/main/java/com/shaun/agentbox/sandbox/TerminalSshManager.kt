package com.shaun.agentbox.sandbox

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Starts the app terminal as a real PTY-backed proot shell.
 *
 * The class keeps the old name for compatibility with the UI wiring, but it no longer
 * starts OpenSSH. A PTY is required for prompt, echo, line editing, and TUI programs.
 */
class TerminalSshManager(private val context: Context) {

    companion object {
        private const val LINUX_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }

    private val linuxManager = LinuxEnvironmentManager(context)
    private var shellSession: TerminalShellSession? = null

    suspend fun ensureServerRunning(workspaceDir: File) = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }
    }

    suspend fun openShell(workspaceDir: File): TerminalShellSession = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }
        stopServerInternal()

        val command = """
            export HOME=/root USER=root LOGNAME=root TERM=xterm-256color PATH=$LINUX_PATH
            cd /workspace
            exec /bin/sh -i
        """.trimIndent()

        val args = linuxManager.buildProotCommand(workspaceDir, command)
        val env = arrayOf(
            "PATH=$LINUX_PATH",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "PROOT_TMP_DIR=${linuxManager.tmpDir.absolutePath}"
        )

        val result = NativePty.createSubprocess(
            linuxManager.prootBin.absolutePath,
            args,
            env,
            workspaceDir.absolutePath
        )
        require(result.size == 3) { "Native PTY returned invalid result." }

        val session = TerminalShellSession(
            pid = result[0],
            readFd = ParcelFileDescriptor.adoptFd(result[1]),
            writeFd = ParcelFileDescriptor.adoptFd(result[2])
        )
        shellSession = session

        delay(200)
        if (!session.isConnected()) {
            val output = runCatching { session.readAvailable() }.getOrDefault("")
            session.close()
            throw IllegalStateException("Shell failed to start." + if (output.isNotBlank()) "\n$output" else "")
        }
        session
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        stopServerInternal()
    }

    private fun stopServerInternal() {
        val session = shellSession
        shellSession = null
        session?.close()
    }
}
