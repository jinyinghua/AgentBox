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

    private data class TeacherConfig(val endpoint: String, val apiKey: String, val model: String)

    private fun getConfig(): TeacherConfig? {
        if (!configFile.exists()) {
            val defaultConfig = buildJsonObject {
                put("endpoint", "https://api.openai.com/v1/chat/completions")
                put("apiKey", "YOUR_API_KEY_HERE")
                put("model", "gpt-4o")
            }
            configFile.writeText(defaultConfig.toString())
            return null
        }
        return try {
            val root = json.parseToJsonElement(configFile.readText()).jsonObject
            val endpoint = root["endpoint"]?.jsonPrimitive?.content ?: "https://api.openai.com/v1/chat/completions"
            val apiKey = root["apiKey"]?.jsonPrimitive?.content ?: ""
            val model = root["model"]?.jsonPrimitive?.content ?: "gpt-4o"
            TeacherConfig(endpoint, apiKey, model)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun askTeacher(content: String, id: String?): Pair<String, String> = withContext(Dispatchers.IO) {
        val history = loadHistory()
        val sessionId = id ?: UUID.randomUUID().toString()
        val messages = history.getOrPut(sessionId) {
            mutableListOf(AiMessage("system", "You are an AI teacher assisting another AI. Provide accurate, helpful, and concise answers."))
        }
        
        messages.add(AiMessage("user", content))
        
        val config = getConfig() ?: throw Exception("Please configure API key in ${configFile.absolutePath}")
        
        val requestBody = buildJsonObject {
            put("model", config.model)
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
}
