package com.shaun.agentbox.sandbox

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Interactive shell session backed by a native pseudo terminal.
 */
class TerminalShellSession(
    private val pid: Int,
    private val readFd: ParcelFileDescriptor,
    private val writeFd: ParcelFileDescriptor,
    private val inputStream: InputStream = FileInputStream(readFd.fileDescriptor),
    private val outputStream: OutputStream = FileOutputStream(writeFd.fileDescriptor)
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
        NativePty.setWindowSize(writeFd.fd, rows, columns, widthPx, heightPx)
    }

    fun isConnected(): Boolean = NativePty.isProcessAlive(pid)

    fun waitForExitStatus(): Int = NativePty.waitFor(pid)

    fun close() {
        runCatching { outputStream.write("exit\n".toByteArray()) }
        runCatching { outputStream.flush() }
        runCatching { outputStream.close() }
        runCatching { inputStream.close() }
        runCatching { readFd.close() }
        runCatching { writeFd.close() }
        runCatching { NativePty.killProcess(pid, 15) }
        runCatching { NativePty.killProcess(pid, 9) }
    }
}
