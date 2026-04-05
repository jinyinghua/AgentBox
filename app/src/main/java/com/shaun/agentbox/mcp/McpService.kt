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
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Server - 修复版：响应直接通过POST响应返回
 * 
 * MCP SSE传输规范：
 * 1. 客户端建立SSE连接，服务器返回endpoint事件
 * 2. 客户端通过HTTP POST发送请求
 * 3. 对于有id的请求，响应必须通过HTTP POST响应返回（同步）
 * 4. 对于无id的通知，服务器可以异步处理
 */
class McpService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
    }
    
    private val sessions = ConcurrentHashMap<String, SseSession>()
    
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
        sessions.values.forEach { it.cancel() }
        sessions.clear()
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
                // SSE端点 - 仅用于服务器主动推送和心跳
                sse("/sse") {
                    val sessionId = UUID.randomUUID().toString()
                    val session = SseSession(id = sessionId)
                    sessions[sessionId] = session
                    log("SSE Connected: $sessionId (Active: ${sessions.size})")
                    
                    try {
                        // 告知客户端消息POST终点
                        send(ServerSentEvent(data = "/message?sessionId=$sessionId", event = "endpoint"))
                        
                        var lastPing = System.currentTimeMillis()
                        while (isActive) {
                            val timeToNextPing = 15000L - (System.currentTimeMillis() - lastPing)
                            if (timeToNextPing <= 0) {
                                // 发送心跳保持连接
                                send(ServerSentEvent(data = "", event = "ping"))
                                lastPing = System.currentTimeMillis()
                                continue
                            }
                            
                            // 等待服务器主动推送的消息（如长时间任务进度）
                            kotlinx.coroutines.delay(minOf(timeToNextPing, 5000))
                        }
                    } catch (e: Exception) {
                        log("SSE Session Error ($sessionId): ${e.message}")
                    } finally {
                        sessions.remove(sessionId)
                        log("SSE Disconnected: $sessionId (Active: ${sessions.size})")
                    }
                }
                
                // POST消息端点 - 处理请求并同步返回响应
                post("/message") {
                    val sessionId = call.request.queryParameters["sessionId"]
                    
                    try {
                        val bodyText = call.receiveText()
                        log("← POST Received ($sessionId): ${bodyText.take(100)}...")
                        
                        val request = json.decodeFromString(JsonRpcRequest.serializer(), bodyText)
                        
                        // 【关键修复】对于有id的请求，同步处理并直接返回响应
                        if (request.id != null) {
                            val response = handleRequest(request)
                            val responseJson = json.encodeToString(JsonRpcResponse.serializer(), response)
                            log("→ POST Response: ${responseJson.take(100)}...")
                            call.respondText(responseJson, ContentType.Application.Json, HttpStatusCode.OK)
                        } else {
                            // 对于无id的通知，异步处理
                            log("Received notification: ${request.method}")
                            serviceScope.launch {
                                try {
                                    handleRequest(request)
                                } catch (e: Exception) {
                                    log("Notification error: ${e.message}")
                                }
                            }
                            call.respond(HttpStatusCode.NoContent)
                        }
                    } catch (e: Exception) {
                        log("POST Error: ${e.message}")
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    }
                }
            }
        }.start(wait = false)
        
        isRunning = true
        log("MCP Server started on port $PORT")
    }
    
    private suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            val result: JsonElement? = when (request.method) {
                "initialize" -> McpTools.buildInitializeResult()
                "notifications/initialized" -> null
                "tools/list" -> McpTools.buildToolListResult()
                "tools/call" -> {
                    val callParams = json.decodeFromJsonElement<CallToolParams>(
                        request.params ?: throw IllegalArgumentException("Missing params for tools/call")
                    )
                    val toolResult = toolExecutor.executeTool(callParams.name, callParams.arguments)
                    json.encodeToJsonElement(toolResult)
                }
                else -> {
                    // 未知方法
                    throw IllegalArgumentException("Unknown method: ${request.method}")
                }
            }
            JsonRpcResponse(id = request.id, result = result)
        } catch (e: Exception) {
            log("Method ${request.method} Error: ${e.message}")
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = -32603, message = e.message ?: "Internal error")
            )
        }
    }
    
    private data class SseSession(val id: String) {
        fun cancel() {
            // 清理资源
        }
    }
}
