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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.net.NetworkInterface
import java.util.UUID

/**
 * MCP Server - 稳定性增强版
 */
class McpService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = false // 确保输出是单行，防止破坏 SSE 格式
    }

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

        fun getLocalIpAddress(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "127.0.0.1"
                interfaces.firstOrNull { it.isUp && !it.isLoopback }?.inetAddresses
                    ?.toList()?.firstOrNull { !it.isLoopbackAddress && it.hostAddress.contains('.') }
                    ?.hostAddress ?: "127.0.0.1"
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching local IP", e)
                "127.0.0.1"
            }
        }
    }

    private var currentSession: SseSession? = null
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
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
        log("Stopping MCP Server...")
        isRunning = false
        server?.stop(500, 1000)
        server = null
        currentSession?.responseChannel?.close()
        currentSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MCP Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentBox MCP Server")
            .setContentText("Running at http://${getLocalIpAddress()}:$PORT/sse")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startServer() {
        if (server != null) return

        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(SSE)
            install(ContentNegotiation) { json(json) }

            routing {
                sse("/sse") {
                    val sessionId = UUID.randomUUID().toString()
                    val session = SseSession(id = sessionId)
                    currentSession = session
                    log("SSE Connected: $sessionId")

                    try {
                        // 包含 sessionId 在 endpoint 中以符合规范约定，尽管我们全局复用 session
                        send(ServerSentEvent(data = "/message?sessionId=$sessionId", event = "endpoint"))
                        
                        var lastPing = System.currentTimeMillis()
                        // 移除独立协程发送心跳，避免由于并发调用 send() 导致底层 Socket Crash 或流损坏
                        while (isActive) {
                            val timeToNextPing = 15000L - (System.currentTimeMillis() - lastPing)
                            if (timeToNextPing <= 0) {
                                send(ServerSentEvent(data = "ping", event = "ping"))
                                lastPing = System.currentTimeMillis()
                                continue
                            }
                            
                            val response = withTimeoutOrNull(timeToNextPing) {
                                session.responseChannel.receive()
                            }
                            
                            if (response != null) {
                                log("→ SSE Sending Response: ${response.take(50)}...")
                                send(ServerSentEvent(data = response, event = "message"))
                            }
                        }
                    } catch (e: Exception) {
                        log("SSE Session Error ($sessionId): ${e.message}")
                    } finally {
                        if (currentSession?.id == sessionId) {
                            currentSession = null
                        }
                        log("SSE Disconnected: $sessionId")
                    }
                }

                post("/message") {
                    val session = currentSession
                    if (session == null) {
                        log("Error: POST received but no SSE session active")
                        call.respond(HttpStatusCode.ServiceUnavailable, "No active SSE session")
                        return@post
                    }

                    try {
                        val bodyText = call.receiveText()
                        log("← POST Received: ${bodyText.take(100)}...")
                        val request = json.decodeFromString(JsonRpcRequest.serializer(), bodyText)
                        
                        serviceScope.launch {
                            try {
                                val response = handleRequest(request)
                                val responseJson = json.encodeToString(JsonRpcResponse.serializer(), response)
                                session.responseChannel.send(responseJson)
                            } catch (e: Exception) {
                                log("Async Task Error: ${e.message}")
                            }
                        }
                        
                        // MCP 规范要求 POST 请求必须返回 202 Accepted 且 Body 必须为空
                        call.respondText("", status = HttpStatusCode.Accepted)
                    } catch (e: Exception) {
                        log("POST Parse Error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, "Invalid Request")
                    }
                }
            }
        }.start(wait = false)

        isRunning = true
        log("MCP Server started")
    }

    private suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            val result: JsonElement? = when (request.method) {
                "initialize" -> McpTools.buildInitializeResult()
                "notifications/initialized" -> null
                "tools/list" -> McpTools.buildToolListResult()
                "tools/call" -> {
                    val callParams = json.decodeFromJsonElement<CallToolParams>(request.params ?: throw Exception("Missing params"))
                    val toolResult = toolExecutor.executeTool(callParams.name, callParams.arguments)
                    json.encodeToJsonElement(toolResult)
                }
                else -> {
                    val params = request.params?.jsonObject?.mapValues { it.value } ?: emptyMap()
                    json.encodeToJsonElement(toolExecutor.executeTool(request.method, params))
                }
            }
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: Exception) {
            log("Method ${request.method} Error: ${e.message}")
            JsonRpcResponse(id = request.id, error = JsonRpcError(code = -32603, message = e.message ?: "Unknown error"))
        }
    }

    private data class SseSession(val id: String, val responseChannel: Channel<String> = Channel(Channel.UNLIMITED))
}
