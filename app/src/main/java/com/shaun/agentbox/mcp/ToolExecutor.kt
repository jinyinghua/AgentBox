package com.shaun.agentbox.mcp

import android.content.Context
import com.shaun.agentbox.sandbox.LinuxEnvironmentManager
import com.shaun.agentbox.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP Tool 执行器 (升级版：注入完整环境变量)
 */
class ToolExecutor(context: Context) {

    private val sandboxManager = SandboxManager(context)
    private val linuxManager = LinuxEnvironmentManager(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val teacherManager = AiTeacherManager(context)
    private val multiAgentManager = MultiAgentManager(context)
    private val multiAgentRuntimeManager = MultiAgentRuntimeManager.getInstance(context)

    companion object {
        private const val COMMAND_TIMEOUT_MS = 60_000L
        private const val MAX_OUTPUT_LENGTH = 100_000
        private const val LINUX_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }

    suspend fun executeTool(name: String, arguments: Map<String, JsonElement>): CallToolResult {
        return when (name) {
            "execute_command" -> {
                val command = arguments["command"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: command")
                executeCommand(command)
            }
            "read_file" -> {
                val path = arguments["path"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: path")
                readFile(path)
            }
            "modify_file" -> {
                val path = arguments["path"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: path")
                val content = arguments["content"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: content")
                modifyFile(path, content)
            }
            "ask_ai_teacher" -> {
                val content = arguments["content"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: content")
                val id = arguments["id"]?.jsonPrimitive?.content
                askAiTeacher(content, id)
            }
            "create_multi_agent_session" -> {
                val title = arguments["title"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: title")
                val objective = arguments["objective"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: objective")
                createMultiAgentSession(title, objective)
            }
            "list_multi_agent_sessions" -> listMultiAgentSessions()
            "create_multi_agent_agent" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                val nameValue = arguments["name"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: name")
                val role = arguments["role"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: role")
                val task = arguments["task"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: task")
                createMultiAgentAgent(sessionId, nameValue, role, task)
            }
            "update_multi_agent_status" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                val agentId = arguments["agent_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: agent_id")
                val status = arguments["status"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: status")
                val message = arguments["message"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: message")
                val progress = arguments["progress"]?.jsonPrimitive?.intOrNull
                updateMultiAgentStatus(sessionId, agentId, status, message, progress)
            }
            "get_multi_agent_board" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                getMultiAgentBoard(sessionId)
            }
            "coordinate_multi_agent" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                val message = arguments["message"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: message")
                val agentId = arguments["agent_id"]?.jsonPrimitive?.content
                val newTask = arguments["new_task"]?.jsonPrimitive?.content
                val supervisor = arguments["supervisor"]?.jsonPrimitive?.content ?: "orchestrator"
                coordinateMultiAgent(sessionId, message, agentId, newTask, supervisor)
            }
            "start_multi_agent_runtime" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                startMultiAgentRuntime(sessionId)
            }
            "pause_multi_agent_runtime" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                pauseMultiAgentRuntime(sessionId)
            }
            "resume_multi_agent_runtime" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                resumeMultiAgentRuntime(sessionId)
            }
            "stop_multi_agent_runtime" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: session_id")
                stopMultiAgentRuntime(sessionId)
            }
            "get_multi_agent_runtime" -> {
                val sessionId = arguments["session_id"]?.jsonPrimitive?.content
                getMultiAgentRuntime(sessionId)
            }
            else -> errorResult("Unknown tool: $name")
        }
    }

    suspend fun executeCommand(command: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            if (!linuxManager.isInstalled) {
                return@withContext errorResult("Linux environment not installed. Please install it in the app first.")
            }

            withTimeout(COMMAND_TIMEOUT_MS) {
                val prootCmd = linuxManager.buildProotCommand(sandboxManager.workspaceDir, command)
                val processBuilder = ProcessBuilder(*prootCmd)
                    .directory(sandboxManager.workspaceDir)
                    .redirectErrorStream(true)

                val env = processBuilder.environment()
                env["PATH"] = LINUX_PATH
                env["HOME"] = "/root"
                env["USER"] = "root"
                env["LOGNAME"] = "root"
                env["TERM"] = "xterm-256color"
                env["PROOT_TMP_DIR"] = linuxManager.tmpDir.absolutePath

                val process = processBuilder.start()
                val output = buildString {
                    process.inputStream.bufferedReader().use { reader ->
                        var totalRead = 0
                        val buffer = CharArray(4096)
                        var read: Int
                        while (reader.read(buffer).also { read = it } != -1) {
                            totalRead += read
                            if (totalRead <= MAX_OUTPUT_LENGTH) append(buffer, 0, read)
                        }
                    }
                }

                val exitCode = process.waitFor()
                CallToolResult(
                    content = listOf(ToolContent(type = "text", text = if (output.isNotEmpty()) output else "")),
                    isError = exitCode != 0
                )
            }
        } catch (e: Exception) {
            errorResult("Execution failed: ${e.message}")
        }
    }

    suspend fun executeCommandStreaming(
        command: String,
        onOutputChunk: (String) -> Unit
    ): CallToolResult = withContext(Dispatchers.IO) {
        try {
            if (!linuxManager.isInstalled) {
                return@withContext errorResult("Linux environment not installed. Please install it in the app first.")
            }

            val prootCmd = linuxManager.buildProotCommand(sandboxManager.workspaceDir, command)
            val processBuilder = ProcessBuilder(*prootCmd)
                .directory(sandboxManager.workspaceDir)
                .redirectErrorStream(true)

            val env = processBuilder.environment()
            env["PATH"] = LINUX_PATH
            env["HOME"] = "/root"
            env["USER"] = "root"
            env["LOGNAME"] = "root"
            env["TERM"] = "xterm-256color"
            env["PROOT_TMP_DIR"] = linuxManager.tmpDir.absolutePath

            val process = processBuilder.start()
            val output = buildString {
                process.inputStream.bufferedReader().use { reader ->
                    var totalRead = 0
                    val buffer = CharArray(1024)
                    var read: Int
                    while (reader.read(buffer).also { read = it } != -1) {
                        if (read <= 0) continue
                        val chunk = String(buffer, 0, read)
                        onOutputChunk(chunk)
                        if (totalRead < MAX_OUTPUT_LENGTH) {
                            val remaining = MAX_OUTPUT_LENGTH - totalRead
                            val appendLen = minOf(remaining, read)
                            append(chunk, 0, appendLen)
                            totalRead += appendLen
                        }
                    }
                }
            }

            val exitCode = process.waitFor()
            CallToolResult(
                content = listOf(ToolContent(type = "text", text = output)),
                isError = exitCode != 0
            )
        } catch (e: Exception) {
            errorResult("Execution failed: ${e.message}")
        }
    }

    private suspend fun readFile(path: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val file = sandboxManager.resolveFile(path)
            if (!file.exists()) return@withContext errorResult("File not found: $path")
            val content = if (file.isDirectory) {
                file.listFiles()?.joinToString("\n") { it.name } ?: "(empty)"
            } else {
                file.readText()
            }
            CallToolResult(content = listOf(ToolContent(type = "text", text = content)), isError = false)
        } catch (e: Exception) {
            errorResult("Read failed: ${e.message}")
        }
    }

    private suspend fun modifyFile(path: String, content: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val file = sandboxManager.resolveFile(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            CallToolResult(content = listOf(ToolContent(type = "text", text = "Successfully written to $path")), isError = false)
        } catch (e: Exception) {
            errorResult("Write failed: ${e.message}")
        }
    }

    private fun errorResult(message: String) = CallToolResult(
        content = listOf(ToolContent(type = "text", text = message)),
        isError = true
    )

    private suspend fun askAiTeacher(content: String, id: String?): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val (sessionId, reply) = teacherManager.askTeacher(content, id)
            val resultText = buildJsonObject {
                put("id", sessionId)
                put("reply", reply)
            }.toString()
            CallToolResult(content = listOf(ToolContent(type = "text", text = resultText)), isError = false)
        } catch (e: Exception) {
            errorResult("Ask AI Teacher failed: ${e.message}")
        }
    }

    private suspend fun createMultiAgentSession(title: String, objective: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val session = multiAgentManager.createSession(title, objective)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(session))), isError = false)
        } catch (e: Exception) {
            errorResult("Create multi-agent session failed: ${e.message}")
        }
    }

    private suspend fun listMultiAgentSessions(): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val sessions = multiAgentManager.listSessions()
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(sessions))), isError = false)
        } catch (e: Exception) {
            errorResult("List multi-agent sessions failed: ${e.message}")
        }
    }

    private suspend fun createMultiAgentAgent(sessionId: String, name: String, role: String, task: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val agent = multiAgentManager.createAgent(sessionId, name, role, task)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(agent))), isError = false)
        } catch (e: Exception) {
            errorResult("Create multi-agent agent failed: ${e.message}")
        }
    }

    private suspend fun updateMultiAgentStatus(sessionId: String, agentId: String, status: String, message: String, progress: Int?): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val agent = multiAgentManager.updateAgentStatus(sessionId, agentId, status, message, progress)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(agent))), isError = false)
        } catch (e: Exception) {
            errorResult("Update multi-agent status failed: ${e.message}")
        }
    }

    private suspend fun getMultiAgentBoard(sessionId: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val session = multiAgentManager.getSession(sessionId)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(session))), isError = false)
        } catch (e: Exception) {
            errorResult("Get multi-agent board failed: ${e.message}")
        }
    }

    private suspend fun coordinateMultiAgent(sessionId: String, message: String, agentId: String?, newTask: String?, supervisor: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val session = multiAgentManager.coordinateAgent(sessionId, message, agentId, newTask, supervisor)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(session))), isError = false)
        } catch (e: Exception) {
            errorResult("Coordinate multi-agent failed: ${e.message}")
        }
    }

    private suspend fun startMultiAgentRuntime(sessionId: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val runtime = multiAgentRuntimeManager.start(sessionId)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(runtime))), isError = false)
        } catch (e: Exception) {
            errorResult("Start multi-agent runtime failed: ${e.message}")
        }
    }

    private suspend fun pauseMultiAgentRuntime(sessionId: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val runtime = multiAgentRuntimeManager.pause(sessionId)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(runtime))), isError = false)
        } catch (e: Exception) {
            errorResult("Pause multi-agent runtime failed: ${e.message}")
        }
    }

    private suspend fun resumeMultiAgentRuntime(sessionId: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val runtime = multiAgentRuntimeManager.resume(sessionId)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(runtime))), isError = false)
        } catch (e: Exception) {
            errorResult("Resume multi-agent runtime failed: ${e.message}")
        }
    }

    private suspend fun stopMultiAgentRuntime(sessionId: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val runtime = multiAgentRuntimeManager.stop(sessionId)
            CallToolResult(content = listOf(ToolContent(type = "text", text = json.encodeToString(runtime))), isError = false)
        } catch (e: Exception) {
            errorResult("Stop multi-agent runtime failed: ${e.message}")
        }
    }

    private suspend fun getMultiAgentRuntime(sessionId: String?): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val payload = if (sessionId.isNullOrBlank()) {
                json.encodeToString(multiAgentRuntimeManager.listRuntimes())
            } else {
                json.encodeToString(multiAgentRuntimeManager.getRuntime(sessionId))
            }
            CallToolResult(content = listOf(ToolContent(type = "text", text = payload)), isError = false)
        } catch (e: Exception) {
            errorResult("Get multi-agent runtime failed: ${e.message}")
        }
    }
}
