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
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Server - 终极修复版
 * 
 * 问题根源分析：
 * 1. MCP SSE规范要求：请求通过HTTP POST发送，响应必须通过SSE流异步推送返回。POST本身只应返回202 Accepted。
 * 2. 客户端SDK(RikkaHub/Kotlin MCP SDK)存在一个行为：它会强行把服务器返回的空内容当作JSON解析，导致崩溃 `unexpected end of the input at path: $ JSON input: `。
 * 3. 我们之前的代码，要么是在 POST 返回了空内容导致客户端立即崩溃断连（引发Broken pipe），要么是在 SSE ping 里发了空内容导致它15秒后崩溃。
 * 
 * 解决方案：
 * - 恢复通过 SSE 推送结果的正确机制。
 * - 对于所有原本是空内容的响应（包括POST响应和SSE的ping事件），全部统一塞入一个空的合法JSON对象 `"{}"`，完美避开客户端的解析崩溃！
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
                sse("/sse") {
                    val sessionId = UUID.randomUUID().toString()
                    val session = SseSession(id = sessionId)
                    sessions[sessionId] = session
                    log("SSE Connected: $sessionId (Active: ${sessions.size})")
                    
                    try {
                        send(ServerSentEvent(data = "/message?sessionId=$sessionId", event = "endpoint"))
                        
                        var lastPing = System.currentTimeMillis()
                        while (isActive) {
                            val timeToNextPing = 15000L - (System.currentTimeMillis() - lastPing)
                            if (timeToNextPing <= 0) {
                                // 【关键修复1】发送心跳时携带合法的空JSON对象 "{}"，防止客户端强行解析空内容崩溃
                                send(ServerSentEvent(data = "{}", event = "ping"))
                                lastPing = System.currentTimeMillis()
                                continue
                            }
                            
                            val response = withTimeoutOrNull(timeToNextPing) {
                                session.responseChannel.receive()
                            }
                            
                            if (response != null) {
                                log("→ SSE Sending Response to $sessionId: ${response.take(50)}...")
                                send(ServerSentEvent(data = response, event = "message"))
                            }
                        }
                    } catch (e: Exception) {
                        log("SSE Session Error ($sessionId): ${e.message}")
                    } finally {
                        sessions.remove(sessionId)
                        log("SSE Disconnected: $sessionId (Active: ${sessions.size})")
                    }
                }
                
                post("/message") {
                    val sessionId = call.request.queryParameters["sessionId"]
                    val session = if (sessionId != null) sessions[sessionId] else sessions.values.firstOrNull()
                    
                    if (session == null) {
                        log("Error: POST received for session $sessionId but no SSE active")
                        call.respondText("{}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    
                    try {
                        val bodyText = call.receiveText()
                        log("← POST Received ($sessionId): ${bodyText.take(100)}...")
                        
                        val request = json.decodeFromString(JsonRpcRequest.serializer(), bodyText)
                        
                        // 异步执行并在SSE通道发送响应
                        serviceScope.launch {
                            try {
                                val response = handleRequest(request)
                                if (request.id != null) {
                                    val responseJson = json.encodeToString(JsonRpcResponse.serializer(), response)
                                    session.responseChannel.send(responseJson)
                                }
                            } catch (e: Exception) {
                                log("Async Task Error: ${e.message}")
                            }
                        }
                        
                        // 【关键修复2】返回202 Accepted，并携带 "{}"，防止客户端强行把POST返回体当作JSON解析导致崩溃
                        call.respondText("{}", ContentType.Application.Json, HttpStatusCode.Accepted)
                    } catch (e: Exception) {
                        log("POST Error: ${e.message}")
                        call.respondText("{\"error\":\"Invalid request\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
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
                else -> throw IllegalArgumentException("Unknown method: ${request.method}")
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
    
    private data class SseSession(
        val id: String,
        val responseChannel: Channel<String> = Channel(Channel.UNLIMITED)
    ) {
        fun cancel() {
            responseChannel.close()
        }
    }
}