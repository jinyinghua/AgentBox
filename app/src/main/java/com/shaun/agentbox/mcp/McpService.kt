package com.shaun.agentbox.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

class McpService : Service() {

    companion object {
        const val TAG = "McpService"
        const val PORT = 8192
        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIFICATION_ID = 1
        private const val ALPINE_ROOTFS_PATH = "/data/local/tmp/alpine_rootfs"  // Path to Alpine rootfs
        @Volatile var isRunning: Boolean = false
            private set

        var onLog: ((String) -> Unit)? = null
        private fun log(msg: String) {
            Log.d(TAG, msg)
            onLog?.invoke(msg)
        }
    }

    private var server: CIOApplicationEngine? = null
    private lateinit var sseResChannel: Channel<String>

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        log("Stopping MCP Server...")
        isRunning = false
        server?.stop(1000, 2000)
        server = null
        sseResChannel.close()
        super.onDestroy()
        log("MCP Server stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "MCP Server", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps MCP Server running"
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentBox MCP Server")
            .setContentText("Listening at http://127.0.0.1:$PORT/sse")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startServer() {
        if (server != null) return

        sseResChannel = Channel(Channel.UNLIMITED)

        server = embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
            install(SSE)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

            routing {
                // SSE 长连接 - 单客户端实现
                sse("/sse") {
                    val sessionId = UUID.randomUUID().toString()
                    log("SSE client connected: $sessionId")

                    try {
                        // 通知客户端发消息的路由
                        send(ServerSentEvent(data = "/message", event = "endpoint"))

                        // 向客户端推送响应
                        for (response in sseResChannel) {
                            send(ServerSentEvent(data = response, event = "message"))
                        }
                    } finally {
                        log("SSE client disconnected: $sessionId")
                    }
                }

                // 接收客户端的 POST 数据
                post("/message") {
                    try {
                        val bodyText = call.receiveText()
                        log("← $bodyText")

                        // 处理JSONRPC请求
                        val request = Json.decodeFromString<JsonRpcRequest>(bodyText)
                        val response = handleRequestInRootFs(request) // Alpine RootFS
                        val responseJson = Json.encodeToString(JsonRpcResponse.serializer(), response)

                        // 将响应推到 SSE 通道
                        log("→ $responseJson")
                        sseResChannel.send(responseJson)
                        call.respond(HttpStatusCode.Accepted, "Accepted")
                    } catch (e: Exception) {
                        log("Error processing message: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "Invalid message")
                    }
                }
            }
        }.start(wait = false)

        isRunning = true
        log("MCP Server started at http://127.0.0.1:$PORT/sse")
    }

    private suspend fun handleRequestInRootFs(request: JsonRpcRequest): JsonRpcResponse {
        return when (request.method) {
            "read_file" -> {
                val path = request.params?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull
                if (path != null) {
                    val safePath = "$ALPINE_ROOTFS_PATH/$path"
                    val content = File(safePath).takeIf { it.exists() }?.readText()
                    JsonRpcResponse(id = request.id, result = Json.encodeToJsonElement(mapOf("content" to content)))
                } else {
                    errorResponse(request.id, -32602, "Missing 'path'")
                }
            }
            "write_to_file" -> {
                val path = request.params?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull
                val content = request.params?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                if (path != null && content != null) {
                    val safePath = "$ALPINE_ROOTFS_PATH/$path"
                    File(safePath).apply { parentFile.mkdirs() }.writeText(content)
                    JsonRpcResponse(id = request.id, result = Json.encodeToJsonElement(mapOf("message" to "File written successfully")))
                } else {
                    errorResponse(request.id, -32602, "Missing 'path' or 'content'")
                }
            }
            "execute_command" -> {
                val command = request.params?.jsonObject?.get("command")?.jsonPrimitive?.contentOrNull
                if (command != null) {
                    val process = ProcessBuilder("proot", "-r", ALPINE_ROOTFS_PATH, "sh", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText()
                    JsonRpcResponse(id = request.id, result = Json.encodeToJsonElement(mapOf("output" to output)))
                } else {
                    errorResponse(request.id, -32602, "Missing 'command'")
                }
            }
            else -> errorResponse(request.id, -32601, "Unknown method: ${request.method}")
        }
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
    }
}

@Serializable
data class JsonRpcRequest(
    val id: JsonElement?,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String
)