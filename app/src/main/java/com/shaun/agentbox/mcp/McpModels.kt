package com.shaun.agentbox.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class JsonRpcRequest(
    @Required val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    @Required val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class CallToolParams(
    val name: String,
    val arguments: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class CallToolResult(
    val content: List<ToolContent>,
    @SerialName("isError") val isError: Boolean
)

@Serializable
data class ToolContent(
    val type: String,
    val text: String
)

object McpTools {

    fun buildToolListResult(): JsonElement = buildJsonObject {
        putJsonArray("tools") {
            add(buildExecuteCommandDef())
            add(buildReadFileDef())
            add(buildModifyFileDef())
        }
    }

    private fun buildExecuteCommandDef() = buildJsonObject {
        put("name", "execute_command")
        put("description", "Execute a shell command in the Alpine Linux sandbox. The environment is persistent. You have full root-like access. Standard Linux tools (ls, grep, cat) are available in PATH. You can install new tools using 'apk add <package>'. Python3 and Git can be installed this way.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "The shell command to execute. Example: 'apk update && apk add python3'")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("command")) }
        }
    }

    private fun buildReadFileDef() = buildJsonObject {
        put("name", "read_file")
        put("description", "Read a file from the workspace. Path is relative to /workspace.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to file")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        }
    }

    private fun buildModifyFileDef() = buildJsonObject {
        put("name", "modify_file")
        put("description", "Create or edit a file in the /workspace.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to file")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "New content of the file")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("content"))
            }
        }
    }

    fun buildInitializeResult(): JsonElement = buildJsonObject {
        put("protocolVersion", "2024-11-05")
        putJsonObject("capabilities") {
            putJsonObject("tools") {
                put("listChanged", false)
            }
        }
        putJsonObject("serverInfo") {
            put("name", "AgentBox")
            put("version", "1.1.0")
        }
    }
}
