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
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * MCP Server - 单客户端版本，集成工具执行逻辑。
 */
class McpService : Service() {

    companion object {
        const val TAG = "McpService"
        const val PORT = 8192
        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        var onLog: ((String) -> Unit)? = null
        private fun log(msg: String) {
            Log.d(TAG, msg)
            onLog?.invoke(msg)
        }
    }

    // 单客户端会话
    private var currentSession: SseSession? = null
    private var server: CIOApplicationEngine? = null
    private lateinit var toolExecutor: ToolExecutor // 引用工具执行器

    override fun onCreate() {
        super.onCreate()
        toolExecutor = ToolExecutor(this)
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
        currentSession?.responseChannel?.close()
        currentSession = null
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

        server = embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
            install(SSE)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

            routing {
                // SSE 路由：建立单客户端长连接
                sse("/sse") {
                    if (currentSession != null) {
                        call.respond(HttpStatusCode.Conflict, "Another client is already connected.")
                        return@sse
                    }

                    val sessionId = UUID.randomUUID().toString()
                    val session = SseSession(id = sessionId)
                    currentSession = session
                    log("SSE client connected: $sessionId")

                    try {
                        // 告知客户端消息投递路径（POST /message）
                        send(ServerSentEvent(data = "/message", event = "endpoint"))

                        // 转发响应到客户端 SSE
                        for (response in session.responseChannel) {
                            send(ServerSentEvent(data = response, event = "message"))
                        }
                    } finally {
                        currentSession = null
                        log("SSE client disconnected: $sessionId")
                    }
                }

                // POST 路由：接收 JSON-RPC 消息
                post("/message") {
                    val session = currentSession
                    if (session == null) {
                        call.respond(HttpStatusCode.NotFound, "No active SSE session")
                        return@post
                    }

                    try {
                        val bodyText = call.receiveText()
                        log("← Received POST: $bodyText")

                        // 解析 JSON-RPC 请求并处理
                        val request = Json.decodeFromString(JsonRpcRequest.serializer(), bodyText)
                        val response = handleRequest(request) // 调用工具执行逻辑
                        val responseJson = Json.encodeToString(JsonRpcResponse.serializer(), response)

                        // 推送返回结果到 SSE 会话
                        session.responseChannel.send(responseJson)
                        call.respond(HttpStatusCode.Accepted, "Message processed")
                    } catch (e: Exception) {
                        log("Error processing message: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC request")
                    }
                }
            }
        }.start(wait = false)

        isRunning = true
        log("MCP Server started at http://127.0.0.1:$PORT/sse")
    }

    /**
     * 处理 JSON-RPC 请求，调用工具执行逻辑。
     */
    private suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            val params = request.params?.jsonObject?.let { jsonObject ->
                jsonObject.mapValues { (_, value) -> value }
            } ?: emptyMap()

            // 调用 ToolExecutor 执行请求的工具
            val result = toolExecutor.executeTool(request.method, params)

            JsonRpcResponse(
                id = request.id,
                result = toolExecutor.toJsonElement(result)
            )
        } catch (e: Exception) {
            log("Error in handleRequest: ${e.message}")
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = -32603,
                    message = "Internal error: ${e.message}"
                )
            )
        }
    }

    // 单客户端会话的数据结构
    private data class SseSession(
        val id: String,
        val responseChannel: Channel<String> = Channel(Channel.UNLIMITED)
    )
}