package com.shaun.agentbox.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
            add(buildAskAiTeacherDef())
            add(buildCreateMultiAgentSessionDef())
            add(buildListMultiAgentSessionsDef())
            add(buildCreateMultiAgentAgentDef())
            add(buildUpdateMultiAgentStatusDef())
            add(buildGetMultiAgentBoardDef())
            add(buildCoordinateMultiAgentDef())
            add(buildStartMultiAgentRuntimeDef())
            add(buildPauseMultiAgentRuntimeDef())
            add(buildResumeMultiAgentRuntimeDef())
            add(buildStopMultiAgentRuntimeDef())
            add(buildGetMultiAgentRuntimeDef())
        }
    }

    private fun buildExecuteCommandDef() = buildJsonObject {
        put("name", "execute_command")
        put("description", "Execute a shell command in the Alpine Linux sandbox. You have full root-like access. You can install new tools using 'apk add <package>'. Python3 and Git can be installed this way.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string") }
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
        put("description", "Create or edit a file in the /workspace. Path is relative to /workspace.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string") }
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
            putJsonObject("tools") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", "AgentBox")
            put("version", "1.5.0")
        }
    }

    private fun buildAskAiTeacherDef() = buildJsonObject {
        put("name", "ask_ai_teacher")
        put("description", "Ask the separately configured advanced AI Teacher. Intended for the main orchestrator AI, not worker agents.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "The question or message for the AI teacher")
                }
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Optional session ID to continue a conversation")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("content")) }
        }
    }

    private fun buildCreateMultiAgentSessionDef() = buildJsonObject {
        put("name", "create_multi_agent_session")
        put("description", "Create a persistent multi-agent workspace/session.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Short session title")
                }
                putJsonObject("objective") {
                    put("type", "string")
                    put("description", "Overall user goal for this multi-agent task")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("title"))
                add(JsonPrimitive("objective"))
            }
        }
    }

    private fun buildListMultiAgentSessionsDef() = buildJsonObject {
        put("name", "list_multi_agent_sessions")
        put("description", "List existing multi-agent sessions.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {}
        }
    }

    private fun buildCreateMultiAgentAgentDef() = buildJsonObject {
        put("name", "create_multi_agent_agent")
        put("description", "Create a worker agent under a multi-agent session and assign its role and initial task.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("session_id") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("role") {
                    put("type", "string")
                    put("description", "Specialized role, for example planner, coder, reviewer")
                }
                putJsonObject("task") {
                    put("type", "string")
                    put("description", "Initial assigned task")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("session_id"))
                add(JsonPrimitive("name"))
                add(JsonPrimitive("role"))
                add(JsonPrimitive("task"))
            }
        }
    }

    private fun buildUpdateMultiAgentStatusDef() = buildJsonObject {
        put("name", "update_multi_agent_status")
        put("description", "Publish the latest worker progress, blockers, and handoff notes.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("session_id") { put("type", "string") }
                putJsonObject("agent_id") { put("type", "string") }
                putJsonObject("status") {
                    put("type", "string")
                    put("description", "Examples: running, waiting, blocked, done")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "Current work log, findings, blockers, or handoff note")
                }
                putJsonObject("progress") {
                    put("type", "integer")
                    put("description", "Optional progress percentage 0-100")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("session_id"))
                add(JsonPrimitive("agent_id"))
                add(JsonPrimitive("status"))
                add(JsonPrimitive("message"))
            }
        }
    }

    private fun buildGetMultiAgentBoardDef() = buildJsonObject {
        put("name", "get_multi_agent_board")
        put("description", "Read the shared board: all agents, their latest statuses, and the recent timeline.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("session_id") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("session_id")) }
        }
    }

    private fun buildCoordinateMultiAgentDef() = buildJsonObject {
        put("name", "coordinate_multi_agent")
        put("description", "Leave orchestrator feedback, corrections, or task reassignments after reviewing the board.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("session_id") { put("type", "string") }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "Supervisor feedback or coordination note")
                }
                putJsonObject("agent_id") {
                    put("type", "string")
                    put("description", "Optional target worker agent id")
                }
                putJsonObject("new_task") {
                    put("type", "string")
                    put("description", "Optional new task when redirecting a worker")
                }
                putJsonObject("supervisor") {
                    put("type", "string")
                    put("description", "Optional supervisor name, default orchestrator")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("session_id"))
                add(JsonPrimitive("message"))
            }
        }
    }

    private fun buildStartMultiAgentRuntimeDef() = buildSessionOnlyTool(
        name = "start_multi_agent_runtime",
        description = "Start the internal autonomous worker runtime for a session. Workers can use command/read/modify/board actions but not ask_ai_teacher."
    )

    private fun buildPauseMultiAgentRuntimeDef() = buildSessionOnlyTool(
        name = "pause_multi_agent_runtime",
        description = "Pause the internal autonomous worker runtime for a session."
    )

    private fun buildResumeMultiAgentRuntimeDef() = buildSessionOnlyTool(
        name = "resume_multi_agent_runtime",
        description = "Resume a paused internal autonomous worker runtime for a session."
    )

    private fun buildStopMultiAgentRuntimeDef() = buildSessionOnlyTool(
        name = "stop_multi_agent_runtime",
        description = "Stop the internal autonomous worker runtime for a session."
    )

    private fun buildGetMultiAgentRuntimeDef() = buildJsonObject {
        put("name", "get_multi_agent_runtime")
        put("description", "Get runtime state for one session or all active runtimes.")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "Optional session id. If omitted, returns all active runtimes.")
                }
            }
        }
    }

    private fun buildSessionOnlyTool(name: String, description: String) = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("session_id") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("session_id")) }
        }
    }
}
