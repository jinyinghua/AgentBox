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

// ===================== JSON-RPC 基础结构 =====================

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

// ===================== MCP 协议特有结构 =====================

/** tools/call 请求参数 */
@Serializable
data class CallToolParams(
    val name: String,
    val arguments: Map<String, JsonElement> = emptyMap()
)

/** tools/call 返回结果 (MCP规范) */
@Serializable
data class CallToolResult(
    val content: List<ToolContent>,
    @SerialName("isError") val isError: Boolean = false
)

@Serializable
data class ToolContent(
    val type: String = "text",
    val text: String
)

// ===================== 工具定义构建器 =====================

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
        put("description", "Execute a shell command inside the sandbox environment. Returns stdout and stderr. Commands have a 30-second timeout.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "The shell command to execute")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("command")) }
        }
    }

    private fun buildReadFileDef() = buildJsonObject {
        put("name", "read_file")
        put("description", "Read the content of a file in the sandbox workspace. Path is relative to workspace root.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to the file from workspace root")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        }
    }

    private fun buildModifyFileDef() = buildJsonObject {
        put("name", "modify_file")
        put("description", "Create or overwrite a file in the sandbox workspace. Parent directories are created automatically. Path is relative to workspace root.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to the file from workspace root")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "The content to write to the file")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("content"))
            }
        }
    }

    // ===================== 服务器信息 =====================

    fun buildInitializeResult(): JsonElement = buildJsonObject {
        put("protocolVersion", "2024-11-05")
        putJsonObject("capabilities") {
            putJsonObject("tools") {
                put("listChanged", false)
            }
        }
        putJsonObject("serverInfo") {
            put("name", "AgentBox")
            put("version", "1.0.0")
        }
    }
}
