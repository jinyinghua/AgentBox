package com.shaun.agentbox.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@Serializable
data class AiMessage(val role: String, val content: String)

data class AiTeacherConfig(
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "gpt-4o"
)

class AiTeacherManager(private val context: Context) {
    private val historyFile = File(context.filesDir, "ai_teacher_history.json")
    private val configFile = File(context.filesDir, "ai_teacher_config.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun loadHistory(): MutableMap<String, MutableList<AiMessage>> {
        if (!historyFile.exists()) return mutableMapOf()
        return try {
            val content = historyFile.readText()
            val map = json.decodeFromString<Map<String, List<AiMessage>>>(content)
            map.mapValues { it.value.toMutableList() }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveHistory(history: Map<String, List<AiMessage>>) {
        historyFile.writeText(json.encodeToString(history))
    }

    fun loadConfig(): AiTeacherConfig {
        if (!configFile.exists()) {
            val defaultConfig = AiTeacherConfig()
            saveConfig(defaultConfig)
            return defaultConfig
        }
        return try {
            val root = json.parseToJsonElement(configFile.readText()).jsonObject
            AiTeacherConfig(
                endpoint = root["endpoint"]?.jsonPrimitive?.content ?: "https://api.openai.com/v1/chat/completions",
                apiKey = root["apiKey"]?.jsonPrimitive?.content ?: "",
                model = root["model"]?.jsonPrimitive?.content ?: "gpt-4o"
            )
        } catch (e: Exception) {
            AiTeacherConfig()
        }
    }

    fun saveConfig(config: AiTeacherConfig) {
        val jsonContent = buildJsonObject {
            put("endpoint", config.endpoint)
            put("apiKey", config.apiKey)
            put("model", config.model)
        }.toString()
        configFile.writeText(jsonContent)
    }

    fun isConfigured(): Boolean {
        val config = loadConfig()
        return config.apiKey.isNotEmpty() && config.apiKey != "YOUR_API_KEY_HERE"
    }

    suspend fun askTeacher(content: String, id: String?): Pair<String, String> = withContext(Dispatchers.IO) {
        val history = loadHistory()
        val sessionId = id ?: UUID.randomUUID().toString()
        // 移除 system prompt，初始化空消息列表
        val messages = history.getOrPut(sessionId) {
            mutableListOf()
        }
        
        messages.add(AiMessage("user", content))
        
        val config = loadConfig()
        if (config.apiKey.isEmpty() || config.apiKey == "YOUR_API_KEY_HERE") {
            throw Exception("AI Teacher not configured. Please set API key in Settings.")
        }
        
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("stream", false)  // 明确指定非流式传输
            putJsonArray("messages") {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            }
        }
        
        val url = URL(config.endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.doOutput = true
        
        try {
            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }
            
            val responseCode = conn.responseCode
            val responseStr = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "Unknown error"
            }
            
            if (responseCode !in 200..299) {
                throw Exception("API Error ($responseCode): $responseStr")
            }
            
            val responseJson = json.parseToJsonElement(responseStr).jsonObject
            val replyContent = responseJson["choices"]?.jsonArray?.get(0)?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content 
                ?: "No content in response"
                
            messages.add(AiMessage("assistant", replyContent))
            saveHistory(history)
            
            Pair(sessionId, replyContent)
        } finally {
            conn.disconnect()
        }
    }

    fun clearHistory() {
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }
}
