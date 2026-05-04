package com.shaun.agentbox.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL

class SubAgentModelClient(context: Context) {
    private val configManager = SubAgentModelConfigManager(context)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun complete(messages: List<AiMessage>): String = withContext(Dispatchers.IO) {
        val config = configManager.loadConfig()
        if (!configManager.isConfigured()) {
            throw IllegalStateException("Sub-agent model is not configured.")
        }

        val requestBody = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            put("temperature", config.temperature)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            }
        }

        val conn = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            doOutput = true
        }

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
                throw IllegalStateException("Sub-agent API error ($responseCode): $responseStr")
            }
            val responseJson = json.parseToJsonElement(responseStr).jsonObject
            responseJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw IllegalStateException("No content in sub-agent response")
        } finally {
            conn.disconnect()
        }
    }
}
