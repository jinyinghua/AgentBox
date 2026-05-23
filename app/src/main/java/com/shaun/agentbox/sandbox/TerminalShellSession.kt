package com.shaun.agentbox.sandbox

import java.io.InputStream
import java.io.OutputStream

/**
 * A lightweight interactive shell session backed directly by a proot process.
 *
 * We intentionally avoid running sshd inside Android/proot because Android's seccomp
 * filter kills OpenSSH on some devices with SIGSYS ("Bad system call").
 */
class TerminalShellSession(
    private val process: Process,
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    private val writeLock = Any()

    fun write(text: String) {
        synchronized(writeLock) {
            outputStream.write(text.toByteArray())
            outputStream.flush()
        }
    }

    fun writeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        synchronized(writeLock) {
            outputStream.write(bytes, offset, length)
            outputStream.flush()
        }
    }

    fun readAvailable(): String {
        val buffer = ByteArray(4096)
        val builder = StringBuilder()
        while (true) {
            val available = runCatching { inputStream.available() }.getOrDefault(0)
            if (available <= 0) break
            val read = inputStream.read(buffer)
            if (read <= 0) break
            builder.append(String(buffer, 0, read))
        }
        return builder.toString()
    }

    fun resize(columns: Int, rows: Int, widthPx: Int = columns * 8, heightPx: Int = rows * 16) {
        // No-op for pipe-backed process sessions. A real PTY would be needed for SIGWINCH.
    }

    fun isConnected(): Boolean = process.isAlive

    fun close() {
        runCatching { outputStream.write("exit\n".toByteArray()) }
        runCatching { outputStream.flush() }
        runCatching { outputStream.close() }
        runCatching { inputStream.close() }
        runCatching { process.destroy() }
        runCatching { process.waitFor() }
        if (process.isAlive) {
            runCatching { process.destroyForcibly() }
        }
    }
}
