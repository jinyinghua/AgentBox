package com.shaun.agentbox.mcp

import android.content.Context
import com.shaun.agentbox.sandbox.LinuxEnvironmentManager
import com.shaun.agentbox.sandbox.PtyCommandRunner
import com.shaun.agentbox.sandbox.SandboxManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP Tool 执行器 (升级版：注入完整环境变量)
 *
 * execute_command 现在采用异步模式：
 * 1) 启动进程后等待初始截获时间（5 秒），返回已捕获的输出 + pid。
 * 2) 进程不会因超时而终止，后台继续捕获后续输出。
 * 3) 可通过 check_command_output(pid) 获取新输出，
 *    通过 process_running_pids() 列出所有未结束的进程。
 */
class ToolExecutor(context: Context) {

    private val sandboxManager = SandboxManager(context)
    private val linuxManager = LinuxEnvironmentManager(context)
    private val ptyCommandRunner = PtyCommandRunner(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val teacherManager = AiTeacherManager(context)
    private val multiAgentManager = MultiAgentManager(context)
    private val multiAgentRuntimeManager = MultiAgentRuntimeManager.getInstance(context)

    companion object {
        private const val INITIAL_CAPTURE_TIMEOUT_MS = 5_000L
        private const val MAX_OUTPUT_LENGTH = 100_000

        /** 后台进程注册表，全局共享 */
        private val runningProcesses = ConcurrentHashMap<Long, RunningProcess>()
        private val pidCounter = AtomicLong(1L)
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 获取 pid 对应的进程信息（用于外部检查，如 SSH 管理） */
        fun getProcess(pid: Long): RunningProcess? = runningProcesses[pid]
    }

    /** 后台运行进程状态 */
    data class RunningProcess(
        val pid: Long,
        val command: String,
        val startTime: Long,
        /** 所有已捕获的输出（线程安全访问） */
        val outputBuffer: StringBuilder = StringBuilder(MAX_OUTPUT_LENGTH),
        /** 上次读取到的偏移量（用于增量读取） */
        var lastReadOffset: Int = 0,
        /** 进程是否已结束 */
        var isCompleted: Boolean = false,
        /** 进程退出码（结束时设置） */
        var exitCode: Int? = null,
        /** 捕获过程中的错误信息 */
        var error: String? = null
    )

    suspend fun executeTool(name: String, arguments: Map<String, JsonElement>): CallToolResult {
        return when (name) {
            "execute_command" -> {
                val command = arguments["command"]?.jsonPrimitive?.content
                    ?: return errorResult("Missing required argument: command")
                executeCommand(command)
            }
            "check_command_output" -> {
                val pid = arguments["pid"]?.jsonPrimitive?.longOrNull
                    ?: return errorResult("Missing required argument: pid")
                checkCommandOutput(pid)
            }
            "process_running_pids" -> {
                processRunningPids()
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

    /**
     * 异步执行命令：
     * - 启动进程后立即启动后台读取协程
     * - 等待 [INITIAL_CAPTURE_TIMEOUT_MS] 毫秒后返回已捕获的输出 + pid
     * - 进程继续在后台运行，后续可通过 checkCommandOutput(pid) 获取新输出
     */
    suspend fun executeCommand(command: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val pid = pidCounter.getAndIncrement()
            val runningProcess = RunningProcess(
                pid = pid,
                command = command,
                startTime = System.currentTimeMillis()
            )
            runningProcesses[pid] = runningProcess

            processScope.launch {
                try {
                    val result = ptyCommandRunner.runCommand(
                        workspaceDir = sandboxManager.workspaceDir,
                        command = command,
                        onOutputChunk = { chunk -> appendOutput(runningProcess, chunk) }
                    )
                    synchronized(runningProcess) {
                        runningProcess.exitCode = result.exitCode
                        runningProcess.isCompleted = true
                    }
                } catch (e: Exception) {
                    synchronized(runningProcess) {
                        runningProcess.error = e.message
                        runningProcess.isCompleted = true
                    }
                }
            }

            delay(INITIAL_CAPTURE_TIMEOUT_MS)

            val initialOutput: String
            val isCompleted: Boolean
            val exitCode: Int?
            val processError: String?
            synchronized(runningProcess) {
                initialOutput = runningProcess.outputBuffer.toString()
                runningProcess.lastReadOffset = initialOutput.length
                isCompleted = runningProcess.isCompleted
                exitCode = runningProcess.exitCode
                processError = runningProcess.error
            }

            val resultJson = buildJsonObject {
                put("pid", JsonPrimitive(pid))
                put("output", JsonPrimitive(initialOutput))
                put("is_completed", JsonPrimitive(isCompleted))
                if (exitCode != null) {
                    put("exit_code", JsonPrimitive(exitCode))
                } else {
                    put("exit_code", JsonNull)
                }
                if (processError != null) {
                    put("error", JsonPrimitive(processError))
                }
            }

            return@withContext CallToolResult(
                content = listOf(ToolContent(type = "text", text = resultJson.toString())),
                isError = false
            )
        } catch (e: Exception) {
            errorResult("Execution failed: ${e.message}")
        }
    }

    /**
     * 查看指定 pid 的后台进程是否有新输出。
     * 每次调用只会返回上次检查之后的新内容。
     * 如果进程已结束且所有输出已被读取，会自动从注册表中移除。
     */
    suspend fun checkCommandOutput(pid: Long): CallToolResult = withContext(Dispatchers.IO) {
        val rp = runningProcesses[pid]
            ?: return@withContext errorResult("No running process found with pid: $pid")

        val newOutput: String
        val isCompleted: Boolean
        val exitCode: Int?
        val processError: String?
        synchronized(rp) {
            val currentLen = rp.outputBuffer.length
            newOutput = rp.outputBuffer.substring(rp.lastReadOffset, currentLen)
            rp.lastReadOffset = currentLen
            isCompleted = rp.isCompleted
            exitCode = rp.exitCode
            processError = rp.error
        }

        // 如果进程已结束且所有输出已被读取，从注册表移除
        if (isCompleted && newOutput.isEmpty()) {
            runningProcesses.remove(pid)
        }

        val resultJson = buildJsonObject {
            put("pid", JsonPrimitive(pid))
            put("output", JsonPrimitive(newOutput))
            put("is_completed", JsonPrimitive(isCompleted))
            if (exitCode != null) {
                put("exit_code", JsonPrimitive(exitCode))
            } else {
                put("exit_code", JsonNull)
            }
            if (processError != null) {
                put("error", JsonPrimitive(processError))
            }
        }

        CallToolResult(
            content = listOf(ToolContent(type = "text", text = resultJson.toString())),
            isError = false
        )
    }

    /**
     * 列出所有仍在后台运行的进程 pid 列表。
     */
    suspend fun processRunningPids(): CallToolResult = withContext(Dispatchers.IO) {
        val activePids = runningProcesses.filter { (_, rp) ->
            synchronized(rp) { !rp.isCompleted }
        }.keys.toList()

        val resultJson = buildJsonObject {
            put("pids", buildJsonArray {
                activePids.forEach { add(JsonPrimitive(it)) }
            })
            put("count", JsonPrimitive(activePids.size))
        }

        CallToolResult(
            content = listOf(ToolContent(type = "text", text = resultJson.toString())),
            isError = false
        )
    }

    /**
     * 原阻塞式 executeCommand 保留为 executeCommandStreaming
     * 供多 Agent 运行时内部使用。
     */
    suspend fun executeCommandStreaming(
        command: String,
        onOutputChunk: (String) -> Unit
    ): CallToolResult = withContext(Dispatchers.IO) {
        try {
            if (!linuxManager.isInstalled) {
                return@withContext errorResult("Linux environment not installed. Please install it in the app first.")
            }

            val result = ptyCommandRunner.runCommand(
                workspaceDir = sandboxManager.workspaceDir,
                command = command,
                onOutputChunk = onOutputChunk
            )
            CallToolResult(
                content = listOf(ToolContent(type = "text", text = result.output)),
                isError = result.exitCode != 0
            )
        } catch (e: Exception) {
            errorResult("Execution failed: ${e.message}")
        }
    }

    private fun appendOutput(runningProcess: RunningProcess, chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(runningProcess) {
            if (runningProcess.outputBuffer.length >= MAX_OUTPUT_LENGTH) return
            val remaining = MAX_OUTPUT_LENGTH - runningProcess.outputBuffer.length
            val appendLen = minOf(remaining, chunk.length)
            runningProcess.outputBuffer.append(chunk, 0, appendLen)
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
                put("id", JsonPrimitive(sessionId))
                put("reply", JsonPrimitive(reply))
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
