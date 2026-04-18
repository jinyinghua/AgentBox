package com.shaun.agentbox.mcp

import android.content.Context
import com.shaun.agentbox.sandbox.LinuxEnvironmentManager
import com.shaun.agentbox.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * MCP Tool 执行器 (升级版：注入完整环境变量)
 */
class ToolExecutor(context: Context) {

    private val sandboxManager = SandboxManager(context)
    private val linuxManager = LinuxEnvironmentManager(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val teacherManager = AiTeacherManager(context)

    companion object {
        private const val COMMAND_TIMEOUT_MS = 60_000L // 增加到 60s 以便 apk 安装软件
        private const val MAX_OUTPUT_LENGTH = 100_000
        
        // 【修复1】标准的 Linux PATH
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

                // 【修复1 & 3】注入核心环境变量
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
                            if (totalRead <= MAX_OUTPUT_LENGTH) {
                                append(buffer, 0, read)
                            }
                        }
                    }
                }

                val exitCode = process.waitFor()

                CallToolResult(
                    content = listOf(ToolContent(type = "text", text = output + "\n[exit code: $exitCode]")),
                    isError = exitCode != 0
                )
            }
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
            
            CallToolResult(
                content = listOf(ToolContent(type = "text", text = content)),
                isError = false
            )
        } catch (e: Exception) {
            errorResult("Read failed: ${e.message}")
        }
    }

    private suspend fun modifyFile(path: String, content: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val file = sandboxManager.resolveFile(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            CallToolResult(
                content = listOf(ToolContent(type = "text", text = "Successfully written to $path")),
                isError = false
            )
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
            
            CallToolResult(
                content = listOf(ToolContent(type = "text", text = resultText)),
                isError = false
            )
        } catch (e: Exception) {
            errorResult("Ask AI Teacher failed: ${e.message}")
        }
    }

}