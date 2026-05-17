package com.shaun.agentbox.sandbox

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

class TerminalShellSession(
    private val session: Session,
    private val channel: ChannelShell,
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    fun write(text: String) {
        outputStream.write(text.toByteArray())
        outputStream.flush()
    }

    fun readAvailable(): String {
        if (!channel.isConnected) return ""
        val buffer = ByteArray(4096)
        val builder = StringBuilder()
        while (inputStream.available() > 0) {
            val read = inputStream.read(buffer)
            if (read <= 0) break
            builder.append(String(buffer, 0, read))
        }
        return builder.toString()
    }

    fun resize(columns: Int, rows: Int, widthPx: Int = columns * 8, heightPx: Int = rows * 16) {
        if (channel.isConnected) {
            channel.setPtySize(columns, rows, widthPx, heightPx)
        }
    }

    fun isConnected(): Boolean = session.isConnected && channel.isConnected

    fun close() {
        runCatching { outputStream.close() }
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}
