package com.shaun.agentbox.sandbox

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Runs a shell command inside proot through a dedicated PTY-backed shell session.
 *
 * Each invocation gets its own shell, which keeps MCP command semantics isolated
 * while still reusing the PTY/proot launch path that works in the in-app terminal.
 */
class PtyCommandRunner(context: Context) {

    companion object {
        private const val STARTUP_GRACE_MS = 200L
        private const val READ_POLL_DELAY_MS = 16L
        private const val EXIT_DRAIN_TIMEOUT_MS = 1_000L
        private const val MARKER_PREFIX = "__AGENTBOX_EXIT__"
        private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")
    }

    private val linuxManager = LinuxEnvironmentManager(context.applicationContext)

    data class PtyCommandResult(
        val output: String,
        val exitCode: Int,
        val rawWaitStatus: Int
    )

    suspend fun runCommand(
        workspaceDir: File,
        command: String,
        onOutputChunk: (String) -> Unit = {}
    ): PtyCommandResult = withContext(Dispatchers.IO) {
        check(linuxManager.isInstalled) { "Linux environment not installed." }

        coroutineScope {
            val session = openHeadlessShell(workspaceDir)
            val waitDeferred = async(Dispatchers.IO) { session.waitForExitStatus() }
            val token = UUID.randomUUID().toString().replace("-", "")
            val markerPrefix = "$MARKER_PREFIX:$token:"

            try {
                delay(STARTUP_GRACE_MS)
                if (waitDeferred.isCompleted) {
                    val startupOutput = normalizeOutput(runCatching { session.readAvailable() }.getOrDefault(""))
                    val rawStatus = waitDeferred.await()
                    throw IllegalStateException(
                        buildString {
                            append("Headless PTY shell failed to start")
                            append(" (wait status: ")
                            append(rawStatus)
                            append(", PROOT_TMP_DIR=")
                            append(linuxManager.tmpDir.absolutePath)
                            append(", env=")
                            append(linuxManager.buildHeadlessPtyEnvironmentArray().joinToString(" "))
                            append(")")
                            if (startupOutput.isNotBlank()) {
                                append("\n")
                                append(startupOutput)
                            }
                        }
                    )
                }

                session.write(buildWrappedCommand(command, token))

                val transcript = StringBuilder()
                var emittedLength = 0
                var parsedOutput = parseTranscript("", markerPrefix)

                while (currentCoroutineContext().isActive) {
                    val chunk = normalizeOutput(runCatching { session.readAvailable() }.getOrElse {
                        throw IllegalStateException("Failed to read PTY output: ${it.message}", it)
                    })
                    if (chunk.isNotEmpty()) {
                        transcript.append(chunk)
                        parsedOutput = parseTranscript(transcript.toString(), markerPrefix)
                        val visibleOutput = stripAnsi(parsedOutput.visibleOutput)
                        if (visibleOutput.length > emittedLength) {
                            val delta = visibleOutput.substring(emittedLength)
                            emittedLength = visibleOutput.length
                            onOutputChunk(delta)
                        }
                    }

                    if (parsedOutput.exitCode != null) {
                        withTimeoutOrNull(EXIT_DRAIN_TIMEOUT_MS) { waitDeferred.await() }
                    }

                    if (waitDeferred.isCompleted && chunk.isEmpty()) {
                        break
                    }

                    delay(READ_POLL_DELAY_MS)
                }

                val rawWaitStatus = waitDeferred.await()
                parsedOutput = parseTranscript(transcript.toString(), markerPrefix)
                val visibleOutput = stripAnsi(parsedOutput.visibleOutput)
                val exitCode = parsedOutput.exitCode ?: decodeWaitStatus(rawWaitStatus)
                if (visibleOutput.length > emittedLength) {
                    onOutputChunk(visibleOutput.substring(emittedLength))
                }

                PtyCommandResult(
                    output = visibleOutput,
                    exitCode = exitCode,
                    rawWaitStatus = rawWaitStatus
                )
            } finally {
                session.close()
            }
        }
    }

    private fun openHeadlessShell(workspaceDir: File): TerminalShellSession {
        val args = linuxManager.buildHeadlessPtyShellCommand(workspaceDir)
        val env = linuxManager.buildHeadlessPtyEnvironmentArray()
        val result = NativePty.createSubprocess(
            linuxManager.prootBin.absolutePath,
            args,
            env,
            workspaceDir.absolutePath
        )
        require(result.size == 3) { "Native PTY returned invalid result." }
        return TerminalShellSession(
            pid = result[0],
            readFd = ParcelFileDescriptor.adoptFd(result[1]),
            writeFd = ParcelFileDescriptor.adoptFd(result[2])
        )
    }

    private fun buildWrappedCommand(command: String, token: String): String {
        val escapedCommand = shellEscape(command)
        return buildString {
            append("/bin/sh -lc ")
            append(escapedCommand)
            append("\n")
            append("__agentbox_exit_code=${'$'}?\n")
            append("printf '\\n$MARKER_PREFIX:$token:%s\\n' \"${'$'}__agentbox_exit_code\"\n")
            append("exit\n")
        }
    }

    private fun shellEscape(text: String): String {
        return "'" + text.replace("'", "'\\''") + "'"
    }

    private fun parseTranscript(transcript: String, markerPrefix: String): ParsedOutput {
        val markerIndex = transcript.indexOf(markerPrefix)
        if (markerIndex >= 0) {
            val visible = transcript.substring(0, markerIndex)
            val afterMarker = transcript.substring(markerIndex + markerPrefix.length)
            val exitCode = afterMarker
                .takeWhile { it == '-' || it.isDigit() }
                .toIntOrNull()
            return ParsedOutput(
                visibleOutput = visible.trimStart('\n'),
                exitCode = exitCode
            )
        }

        return ParsedOutput(
            visibleOutput = stripTrailingPartialMarker(transcript, markerPrefix).trimStart('\n'),
            exitCode = null
        )
    }

    private fun stripTrailingPartialMarker(text: String, markerPrefix: String): String {
        val maxPrefixLength = minOf(text.length, markerPrefix.length - 1)
        for (length in maxPrefixLength downTo 1) {
            if (text.endsWith(markerPrefix.substring(0, length))) {
                return text.dropLast(length)
            }
        }
        return text
    }

    private fun normalizeOutput(text: String): String = text.replace("\r", "")

    private fun stripAnsi(text: String): String = text.replace(ANSI_ESCAPE_REGEX, "")

    private fun decodeWaitStatus(status: Int): Int {
        if (status < 0) return status
        val signal = status and 0x7f
        return if (signal == 0) {
            (status shr 8) and 0xff
        } else {
            128 + signal
        }
    }

    private data class ParsedOutput(
        val visibleOutput: String,
        val exitCode: Int?
    )
}
