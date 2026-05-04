package com.shaun.agentbox.mcp

import android.content.Context
import com.shaun.agentbox.sandbox.LinuxEnvironmentManager
import com.shaun.agentbox.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Serializable
data class MultiAgentRuntimeSnapshot(
    val sessionId: String,
    val status: String,
    val currentAgentId: String? = null,
    val currentAgentName: String? = null,
    val lastTickAt: Long = 0L,
    val loopCount: Long = 0L,
    val lastError: String? = null
)

class MultiAgentRuntimeManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val manager = MultiAgentManager(appContext)
    private val modelClient = SubAgentModelClient(appContext)
    private val sandboxManager = SandboxManager(appContext)
    private val linuxManager = LinuxEnvironmentManager(appContext)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimes = ConcurrentHashMap<String, RuntimeState>()

    companion object {
        @Volatile
        private var INSTANCE: MultiAgentRuntimeManager? = null

        fun getInstance(context: Context): MultiAgentRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MultiAgentRuntimeManager(context).also { INSTANCE = it }
            }
        }

        private const val COMMAND_TIMEOUT_MS = 60_000L
        private const val MAX_OUTPUT_LENGTH = 100_000
        private const val LINUX_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val LOOP_DELAY_MS = 1500L
        private const val MAX_TOOL_RESULT_CHARS = 12000
        private const val MAX_ACTION_REPAIR_ATTEMPTS = 2
    }

    suspend fun start(sessionId: String): MultiAgentRuntimeSnapshot {
        val existing = runtimes[sessionId]
        if (existing != null && existing.status == "running") return existing.snapshot()

        val session = manager.getSession(sessionId)
        require(session.agents.isNotEmpty()) { "Cannot start runtime: no agents in session." }

        existing?.job?.cancel()
        manager.setSessionStatus(sessionId, "running", "Multi-agent runtime started.")

        val state = RuntimeState(sessionId = sessionId, status = "running")
        val job = scope.launch {
            runLoop(state)
        }
        state.job = job
        runtimes[sessionId] = state
        return state.snapshot()
    }

    suspend fun pause(sessionId: String): MultiAgentRuntimeSnapshot {
        val state = runtimes[sessionId] ?: throw IllegalArgumentException("Runtime not found: $sessionId")
        state.status = "paused"
        manager.setSessionStatus(sessionId, "paused", "Multi-agent runtime paused.")
        return state.snapshot()
    }

    suspend fun resume(sessionId: String): MultiAgentRuntimeSnapshot {
        val state = runtimes[sessionId] ?: return start(sessionId)
        state.status = "running"
        manager.setSessionStatus(sessionId, "running", "Multi-agent runtime resumed.")
        return state.snapshot()
    }

    suspend fun stop(sessionId: String): MultiAgentRuntimeSnapshot {
        val state = runtimes.remove(sessionId) ?: throw IllegalArgumentException("Runtime not found: $sessionId")
        state.status = "stopped"
        state.job?.cancel()
        manager.setSessionStatus(sessionId, "stopped", "Multi-agent runtime stopped.")
        return state.snapshot()
    }

    fun getRuntime(sessionId: String): MultiAgentRuntimeSnapshot? = runtimes[sessionId]?.snapshot()

    fun listRuntimes(): List<MultiAgentRuntimeSnapshot> = runtimes.values.map { it.snapshot() }

    private suspend fun runLoop(state: RuntimeState) {
        while (currentCoroutineContext().isActive && state.status != "stopped") {
            if (state.status == "paused") {
                delay(LOOP_DELAY_MS)
                continue
            }

            val session = runCatching { manager.getSession(state.sessionId) }.getOrElse {
                state.lastError = it.message
                state.status = "error"
                break
            }

            if (session.agents.isEmpty()) {
                manager.setSessionStatus(state.sessionId, "idle", "Runtime stopped because no agents remain.")
                state.status = "idle"
                break
            }

            val agent = session.agents[state.nextAgentIndex % session.agents.size]
            state.currentAgentId = agent.id
            state.currentAgentName = agent.name
            state.lastTickAt = System.currentTimeMillis()
            state.loopCount += 1

            runCatching {
                performAgentStep(session.id, agent.id)
            }.onFailure {
                state.lastError = it.message
                manager.updateAgentStatus(
                    session.id,
                    agent.id,
                    "blocked",
                    "Runtime error: ${it.message}",
                    agent.progress
                )
            }

            val refreshedSession = runCatching { manager.getSession(session.id) }.getOrNull()
            if (refreshedSession != null && refreshedSession.agents.isNotEmpty() && refreshedSession.agents.all { it.status == "done" }) {
                manager.setSessionStatus(session.id, "completed", "All worker agents reported done.")
                state.status = "completed"
                break
            }

            state.nextAgentIndex = (state.nextAgentIndex + 1) % session.agents.size
            delay(LOOP_DELAY_MS)
        }
    }

    private suspend fun performAgentStep(sessionId: String, agentId: String) {
        val session = manager.getSession(sessionId)
        val agent = session.agents.firstOrNull { it.id == agentId }
            ?: throw IllegalArgumentException("Agent not found: $agentId")

        val boardSummary = buildBoardSummary(session, agentId)
        val prompt = buildWorkerPrompt(session, agent, boardSummary)
        val action = requestValidatedWorkerAction(prompt)

        when (action.actionType) {
            "execute_command" -> {
                val command = action.args["command"] ?: throw IllegalArgumentException("Missing command")
                val result = executeCommand(command)
                manager.updateAgentStatus(sessionId, agentId, action.status, summarizeToolResult(action.summary, result), action.progress)
            }
            "read_file" -> {
                val path = action.args["path"] ?: throw IllegalArgumentException("Missing path")
                val result = readFile(path)
                manager.updateAgentStatus(sessionId, agentId, action.status, summarizeToolResult(action.summary, result), action.progress)
            }
            "modify_file" -> {
                val path = action.args["path"] ?: throw IllegalArgumentException("Missing path")
                val content = action.args["content"] ?: throw IllegalArgumentException("Missing content")
                val result = modifyFile(path, content)
                manager.updateAgentStatus(sessionId, agentId, action.status, summarizeToolResult(action.summary, result), action.progress)
            }
            "get_board" -> {
                val refreshed = buildBoardSummary(manager.getSession(sessionId), agentId)
                manager.updateAgentStatus(sessionId, agentId, action.status, summarizeText(action.summary, refreshed), action.progress)
            }
            "report" -> {
                manager.updateAgentStatus(sessionId, agentId, action.status, action.summary, action.progress)
            }
            else -> {
                manager.updateAgentStatus(sessionId, agentId, "blocked", "Unsupported action from sub-agent model: ${action.actionType}", agent.progress)
            }
        }
    }

    private fun buildWorkerPrompt(
        session: MultiAgentSession,
        agent: MultiAgentWorker,
        boardSummary: String
    ): String {
        return buildString {
            appendLine("You are worker agent '${agent.name}'.")
            appendLine("Role: ${agent.role}")
            appendLine("Task: ${agent.task}")
            appendLine("Overall objective: ${session.objective}")
            appendLine("Your current status: ${agent.status}")
            appendLine("Your latest message: ${agent.lastMessage.ifBlank { "(none)" }}")
            appendLine()
            appendLine("Shared board:")
            appendLine(boardSummary)
            appendLine()
            appendLine("Available actions:")
            appendLine("1. execute_command -> {\"command\":\"...\"}")
            appendLine("2. read_file -> {\"path\":\"...\"}")
            appendLine("3. modify_file -> {\"path\":\"...\",\"content\":\"...\"}")
            appendLine("4. get_board -> {}")
            appendLine("5. report -> {}")
            appendLine()
            appendLine("Rules:")
            appendLine("- You may NOT call ask_ai_teacher.")
            appendLine("- If you need guidance from the supervisor/main AI, use status 'waiting' or 'blocked' and write a precise question in summary.")
            appendLine("- Keep edits minimal and concrete.")
            appendLine("- If task is finished, use status 'done'.")
            appendLine("- Output JSON only.")
            appendLine()
            appendLine("Required JSON schema:")
            appendLine("{\"status\":\"running|waiting|blocked|done\",\"progress\":0,\"summary\":\"what you are doing / what you need\",\"action\":{\"type\":\"execute_command|read_file|modify_file|get_board|report\",\"args\":{}}}")
        }
    }

    private suspend fun requestValidatedWorkerAction(prompt: String): WorkerAction {
        var currentPrompt = prompt
        var lastError = "Unknown error"
        repeat(MAX_ACTION_REPAIR_ATTEMPTS) { attempt ->
            val raw = modelClient.complete(
                listOf(
                    AiMessage("system", WORKER_SYSTEM_PROMPT),
                    AiMessage("user", currentPrompt)
                )
            )
            try {
                return parseWorkerAction(raw)
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                currentPrompt = buildRepairPrompt(prompt, raw, lastError, attempt + 1)
            }
        }
        throw IllegalArgumentException("Worker action validation failed after $MAX_ACTION_REPAIR_ATTEMPTS attempts: $lastError")
    }

    private fun buildRepairPrompt(originalPrompt: String, raw: String, error: String, attempt: Int): String {
        return buildString {
            appendLine(originalPrompt)
            appendLine()
            appendLine("Your previous response was invalid JSON or violated the schema.")
            appendLine("Attempt: $attempt")
            appendLine("Validation error: $error")
            appendLine("Previous response:")
            appendLine(raw.take(4000))
            appendLine()
            appendLine("Reply again with JSON only and no markdown fences.")
            appendLine("Allowed action types only: execute_command, read_file, modify_file, get_board, report")
        }
    }

    private fun buildBoardSummary(session: MultiAgentSession, selfAgentId: String): String {
        val agentsText = session.agents.joinToString("\n") { worker ->
            val selfMark = if (worker.id == selfAgentId) "(you)" else ""
            "- ${worker.name}$selfMark | role=${worker.role} | task=${worker.task} | status=${worker.status} | progress=${worker.progress ?: -1} | last=${worker.lastMessage.take(280)}"
        }
        val timelineText = session.timeline.takeLast(12).joinToString("\n") { entry ->
            "- [${entry.type}] ${entry.agentName ?: entry.supervisor ?: "system"}: ${entry.message.take(280)}"
        }
        return "Session status=${session.status}\nAgents:\n$agentsText\nRecent timeline:\n$timelineText"
    }

    private fun parseWorkerAction(raw: String): WorkerAction {
        val jsonText = extractJsonObject(raw)
        val root = json.parseToJsonElement(jsonText).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing status")
        require(status in setOf("running", "waiting", "blocked", "done")) { "Invalid status: $status" }
        val summary = root["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing summary")
        val progress = root["progress"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100)
        val actionObj = root["action"]?.jsonObject ?: throw IllegalArgumentException("Missing action object")
        val actionType = actionObj["type"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing action type")
        require(actionType in setOf("execute_command", "read_file", "modify_file", "get_board", "report")) {
            "Unsupported action type: $actionType"
        }
        val argsObj = actionObj["args"]?.jsonObject ?: JsonObject(emptyMap())
        val args = argsObj.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("Action args must be primitive strings")
        }
        validateActionArgs(actionType, args)
        return WorkerAction(
            status = status,
            progress = progress,
            summary = summary,
            actionType = actionType,
            args = args
        )
    }

    private fun validateActionArgs(actionType: String, args: Map<String, String>) {
        when (actionType) {
            "execute_command" -> require(!args["command"].isNullOrBlank()) { "execute_command requires non-empty command" }
            "read_file" -> require(!args["path"].isNullOrBlank()) { "read_file requires non-empty path" }
            "modify_file" -> {
                require(!args["path"].isNullOrBlank()) { "modify_file requires non-empty path" }
                require(args.containsKey("content")) { "modify_file requires content" }
            }
            "get_board", "report" -> Unit
        }
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalArgumentException("Sub-agent response is not valid JSON: $raw")
        }
        return raw.substring(start, end + 1)
    }

    private fun summarizeToolResult(summary: String, result: ToolCallSummary): String {
        val toolText = if (result.isError) {
            "Tool failed: ${result.output}"
        } else {
            "Tool result: ${result.output}"
        }
        return summarizeText(summary, toolText)
    }

    private fun summarizeText(summary: String, extra: String): String {
        return buildString {
            append(summary)
            if (extra.isNotBlank()) {
                append("\n")
                append(extra.take(MAX_TOOL_RESULT_CHARS))
            }
        }.take(MAX_TOOL_RESULT_CHARS)
    }

    private suspend fun executeCommand(command: String): ToolCallSummary = withContext(Dispatchers.IO) {
        if (!linuxManager.isInstalled) {
            return@withContext ToolCallSummary(true, "Linux environment not installed.")
        }
        try {
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
                ToolCallSummary(exitCode != 0, output.ifBlank { "(no output)" })
            }
        } catch (e: Exception) {
            ToolCallSummary(true, "Execution failed: ${e.message}")
        }
    }

    private suspend fun readFile(path: String): ToolCallSummary = withContext(Dispatchers.IO) {
        try {
            val file = sandboxManager.resolveFile(path)
            if (!file.exists()) return@withContext ToolCallSummary(true, "File not found: $path")
            val content = if (file.isDirectory) {
                file.listFiles()?.joinToString("\n") { it.name } ?: "(empty)"
            } else {
                file.readText()
            }
            ToolCallSummary(false, content.take(MAX_OUTPUT_LENGTH))
        } catch (e: Exception) {
            ToolCallSummary(true, "Read failed: ${e.message}")
        }
    }

    private suspend fun modifyFile(path: String, content: String): ToolCallSummary = withContext(Dispatchers.IO) {
        try {
            val file = sandboxManager.resolveFile(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolCallSummary(false, "Successfully written to $path")
        } catch (e: Exception) {
            ToolCallSummary(true, "Write failed: ${e.message}")
        }
    }

    private data class RuntimeState(
        val sessionId: String,
        var status: String,
        var currentAgentId: String? = null,
        var currentAgentName: String? = null,
        var lastTickAt: Long = 0L,
        var loopCount: Long = 0L,
        var nextAgentIndex: Int = 0,
        var lastError: String? = null,
        var job: Job? = null
    ) {
        fun snapshot(): MultiAgentRuntimeSnapshot = MultiAgentRuntimeSnapshot(
            sessionId = sessionId,
            status = status,
            currentAgentId = currentAgentId,
            currentAgentName = currentAgentName,
            lastTickAt = lastTickAt,
            loopCount = loopCount,
            lastError = lastError
        )
    }

    private data class WorkerAction(
        val status: String,
        val progress: Int?,
        val summary: String,
        val actionType: String,
        val args: Map<String, String>
    )

    private data class ToolCallSummary(
        val isError: Boolean,
        val output: String
    )

    private val WORKER_SYSTEM_PROMPT = """
You are an autonomous worker agent inside AgentBox.
You must think step by step, but output only one JSON object.
You can use only the listed actions.
Never mention hidden reasoning.
If you need guidance from the supervisor/main AI, ask clearly in summary and choose waiting or blocked.
Do not fabricate tool outputs.
""".trimIndent()
}
