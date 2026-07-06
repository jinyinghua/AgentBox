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

    private val linuxManager = LinuxEnvironmentManager(context)
    private var shellSession: TerminalShellSession? = null

    suspend fun ensureServerRunning(workspaceDir: File) = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }
    }

    suspend fun openShell(workspaceDir: File): TerminalShellSession = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }
        stopServerInternal()

        val args = linuxManager.buildInteractivePtyShellCommand(workspaceDir)
        val env = linuxManager.buildHeadlessPtyEnvironmentArray()

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
            val exitStatus = NativePty.waitFor(result[0])
            session.close()
            throw IllegalStateException(
                buildString {
                    append("Shell failed to start")
                    append(" (wait status: ")
                    append(exitStatus)
                    append(", PROOT_TMP_DIR=")
                    append(linuxManager.tmpDir.absolutePath)
                    append(")")
                    if (output.isNotBlank()) {
                        append("\n")
                        append(output)
                    }
                }
            )
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
