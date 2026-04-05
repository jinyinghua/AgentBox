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
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentBox MCP Server - Foreground Service
 *
 * 实现标准的 MCP (Model Context Protocol) over SSE 传输：
 * - GET /sse         → 建立 SSE 长连接，首条消息发送 endpoint 事件
 * - POST /message    → 接收 JSON-RPC 请求，通过 SSE 流返回响应
 *
 * 完整实现 MCP 生命周期：
 * initialize → initialized (notification) → tools/list → tools/call
 */
class McpService : Service() {

    companion object {
        const val TAG = "McpService"
        const val PORT = 8192
        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIFICATION_ID = 1

        /** 服务运行状态（供 UI 观察） */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 服务器日志回调（供 UI 显示终端日志） */
        var onLog: ((String) -> Unit)? = null

        private fun log(msg: String) {
            Log.d(TAG, msg)
            onLog?.invoke(msg)
        }

        /**
         * 获取本机局域网 WiFi IP 地址。
         * 优先 wlan 接口，排除回环和 IPv6。
         */
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "127.0.0.1"

                // 优先找 wlan 接口
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val isWlan = iface.name.startsWith("wlan", ignoreCase = true)
                    if (isWlan) {
                        for (addr in iface.inetAddresses) {
                            if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                                return addr.hostAddress!!
                            }
                        }
                    }
                }

                // 退而求其次：任何非回环 IPv4
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            return addr.hostAddress!!
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get IP", e)
            }
            return "127.0.0.1"
        }
    }

    // ===================== SSE 会话管理 =====================

    /**
     * 每个 SSE 连接对应一个 Session。
     * 客户端 POST /message?sessionId=xxx 时，通过 sessionId 找到对应的 Channel，
     * 将 JSON-RPC 响应推入 Channel，SSE 循环取出并发送。
     */
    private data class SseSession(
        val id: String,
        val responseChannel: Channel<String> = Channel(Channel.UNLIMITED)
    )

    private val sessions = ConcurrentHashMap<String, SseSession>()

    // ===================== Service 生命周期 =====================

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var toolExecutor: ToolExecutor

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
        log("MCP Server stopping...")
        isRunning = false
        // 关闭所有会话
        sessions.values.forEach { it.responseChannel.close() }
        sessions.clear()
        server?.stop(1000, 2000)
        server = null
        serviceScope.cancel()
        super.onDestroy()
        log("MCP Server stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===================== 前台通知 =====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps MCP Server running in background"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val ip = getLocalIpAddress()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentBox MCP Server")
            .setContentText("Running at http://$ip:$PORT/sse")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    // ===================== Ktor Server =====================

    private fun startServer() {
        if (server != null) return

        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(SSE)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

            routing {
                // ---- SSE 端点：建立长连接 ----
                sse("/sse") {
                    val sessionId = UUID.randomUUID().toString()
                    val session = SseSession(id = sessionId)
                    sessions[sessionId] = session

                    val ip = getLocalIpAddress()
                    val endpointUrl = "http://$ip:$PORT/message?sessionId=$sessionId"
                    log("SSE client connected: $sessionId")

                    try {
                        // MCP 规范：首条 SSE 事件必须是 endpoint，告诉客户端 POST 地址
                        send(ServerSentEvent(data = endpointUrl, event = "endpoint"))

                        // 持续从 Channel 取出响应，通过 SSE 推给客户端
                        for (responseJson in session.responseChannel) {
                            send(ServerSentEvent(data = responseJson, event = "message"))
                        }
                    } finally {
                        sessions.remove(sessionId)
                        log("SSE client disconnected: $sessionId")
                    }
                }

                // ---- Message 端点：接收 JSON-RPC 请求 ----
                post("/message") {
                    val sessionId = call.request.queryParameters["sessionId"]
                    if (sessionId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
                        return@post
                    }

                    val session = sessions[sessionId]
                    if (session == null) {
                        call.respond(HttpStatusCode.NotFound, "Session not found: $sessionId")
                        return@post
                    }

                    try {
                        val bodyText = call.receiveText()
                        log("← $bodyText")

                        val request = Json.decodeFromString<JsonRpcRequest>(bodyText)
                        val response = handleRequest(request)
                        val responseJson = Json.encodeToString(JsonRpcResponse.serializer(), response)

                        log("→ $responseJson")

                        // 将响应推入该会话的 SSE 通道
                        session.responseChannel.send(responseJson)

                        // POST 本身返回 202 Accepted（响应通过 SSE 异步推送）
                        call.respond(HttpStatusCode.Accepted, "Accepted")
                    } catch (e: Exception) {
                        log("Error handling message: ${e.message}")
                        // 即使处理出错，也要通过 SSE 推送错误响应
                        val errorResponse = JsonRpcResponse(
                            error = JsonRpcError(
                                code = -32700,
                                message = "Parse error: ${e.message}"
                            )
                        )
                        session.responseChannel.send(
                            Json.encodeToString(JsonRpcResponse.serializer(), errorResponse)
                        )
                        call.respond(HttpStatusCode.Accepted, "Accepted")
                    }
                }
            }
        }.start(wait = false)

        isRunning = true
        val ip = getLocalIpAddress()
        log("MCP Server started at http://$ip:$PORT/sse")
    }

    // ===================== MCP 协议处理 =====================

    private suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        return when (request.method) {

            // ---- 握手阶段 ----
            "initialize" -> {
                log("Client initializing...")
                JsonRpcResponse(
                    id = request.id,
                    result = McpTools.buildInitializeResult()
                )
            }

            // notifications/initialized 是通知（无 id），不需要响应
            "notifications/initialized" -> {
                log("Client initialized.")
                // 通知类消息不回复，但我们仍需返回一个对象让流程继续
                // 实际上不应该推送任何响应。用特殊标记处理。
                JsonRpcResponse(id = request.id) // id 为 null 的话不会被发送
            }

            // ---- 工具列表 ----
            "tools/list" -> {
                log("Client requesting tool list...")
                JsonRpcResponse(
                    id = request.id,
                    result = McpTools.buildToolListResult()
                )
            }

            // ---- 工具调用 ----
            "tools/call" -> {
                val params = Json.decodeFromJsonElement<CallToolParams>(
                    request.params ?: return errorResponse(request.id, -32602, "Missing params")
                )
                log("Calling tool: ${params.name}")

                val toolResult = toolExecutor.executeTool(params.name, params.arguments)
                JsonRpcResponse(
                    id = request.id,
                    result = toolExecutor.toJsonElement(toolResult)
                )
            }

            // ---- 未知方法 ----
            else -> {
                log("Unknown method: ${request.method}")
                errorResponse(request.id, -32601, "Method not found: ${request.method}")
            }
        }
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
    }
}
