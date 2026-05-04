package com.shaun.agentbox.mcp

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

data class SubAgentModelConfig(
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "gpt-4.1-mini",
    val temperature: Double = 0.3
)

class SubAgentModelConfigManager(private val context: Context) {
    private val configFile = File(context.filesDir, "sub_agent_model_config.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun loadConfig(): SubAgentModelConfig {
        if (!configFile.exists()) {
            val defaultConfig = SubAgentModelConfig()
            saveConfig(defaultConfig)
            return defaultConfig
        }
        return try {
            val root = json.parseToJsonElement(configFile.readText()).jsonObject
            SubAgentModelConfig(
                endpoint = root["endpoint"]?.jsonPrimitive?.content ?: "https://api.openai.com/v1/chat/completions",
                apiKey = root["apiKey"]?.jsonPrimitive?.content ?: "",
                model = root["model"]?.jsonPrimitive?.content ?: "gpt-4.1-mini",
                temperature = root["temperature"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.3
            )
        } catch (_: Exception) {
            SubAgentModelConfig()
        }
    }

    fun saveConfig(config: SubAgentModelConfig) {
        val jsonContent = buildJsonObject {
            put("endpoint", config.endpoint)
            put("apiKey", config.apiKey)
            put("model", config.model)
            put("temperature", config.temperature)
        }.toString()
        configFile.writeText(jsonContent)
    }

    fun isConfigured(): Boolean {
        val config = loadConfig()
        return config.apiKey.isNotBlank() && config.apiKey != "YOUR_API_KEY_HERE"
    }
}
