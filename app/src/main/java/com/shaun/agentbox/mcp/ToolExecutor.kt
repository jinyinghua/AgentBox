package com.shaun.agentbox.mcp

import android.content.Context
import com.shaun.agentbox.sandbox.LinuxEnvironmentManager
import com.shaun.agentbox.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * MCP Tool 执行器 (接入 Plan B: Linux Container)
 */
class ToolExecutor(context: Context) {

    private val sandboxManager = SandboxManager(context)
    private val linuxManager = LinuxEnvironmentManager(context)
    private val json = Json { ignoreUnknownKeys = true }

    val workspaceDir: File get() = sandboxManager.workspaceDir

    companion object {
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_LENGTH = 100_000
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
            else -> errorResult("Unknown tool: $name")
        }
    }

    fun toJsonElement(result: CallToolResult): JsonElement {
        return json.encodeToJsonElement(result)
    }

    // ===================== Tool 实现 (Plan B: Proot 模式) =====================

    private suspend fun executeCommand(command: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            // 检查环境是否就绪
            if (!linuxManager.isInstalled) {
                return@withContext errorResult("Linux environment not installed. Please install it in the app first.")
            }

            withTimeout(COMMAND_TIMEOUT_MS) {
                // 核心：使用 proot 封装命令
                val prootCmd = linuxManager.buildProotCommand(sandboxManager.workspaceDir, command)

                val processBuilder = ProcessBuilder(*prootCmd)
                    .directory(sandboxManager.workspaceDir)
                    .redirectErrorStream(true)

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
                            } else {
                                append("\n\n... [OUTPUT TRUNCATED] ...")
                                break
                            }
                        }
                    }
                }

                val exitCode = process.waitFor()

                val resultText = buildString {
                    append(output)
                    if (isNotEmpty() && !endsWith('\n')) append('\n')
                    append("[exit code: $exitCode]")
                }

                CallToolResult(
                    content = listOf(ToolContent(type = "text", text = resultText)),
                    isError = exitCode != 0
                )
            }
        } catch (e: TimeoutCancellationException) {
            errorResult("Command timed out after ${COMMAND_TIMEOUT_MS / 1000}s: $command")
        } catch (e: Exception) {
            errorResult("Command execution failed: ${e.message}")
        }
    }

    private suspend fun readFile(path: String): CallToolResult = withContext(Dispatchers.IO) {
        try {
            val file = sandboxManager.resolveFile(path)
            if (!file.exists()) {
                return@withContext errorResult("File not found: $path")
            }
            if (file.isDirectory) {
                val listing = file.listFiles()?.joinToString("\n") { entry ->
                    val type = if (entry.isDirectory) "[DIR]" else "[FILE ${entry.length()}B]"
                    "$type ${entry.name}"
                } ?: "(empty directory)"
                CallToolResult(
                    content = listOf(ToolContent(type = "text", text = listing)),
                    isError = false
                )
            } else {
                val content = file.readText()
                val finalContent = if (content.length > MAX_OUTPUT_LENGTH) {
                    content.take(MAX_OUTPUT_LENGTH) + "\n\n... [FILE TRUNCATED] ..."
                } else {
                    content
                }
                CallToolResult(
                    content = listOf(ToolContent(type = "text", text = finalContent)),
                    isError = false
                )
            }
        } catch (e: SecurityException) {
            errorResult("Access denied (sandbox escape blocked): $path")
        } catch (e: Exception) {
            errorResult("Failed to read file: ${e.message}")
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
        } catch (e: SecurityException) {
            errorResult("Access denied (sandbox escape blocked): $path")
        } catch (e: Exception) {
            errorResult("Failed to write file: ${e.message}")
        }
    }

    private fun errorResult(message: String) = CallToolResult(
        content = listOf(ToolContent(type = "text", text = message)),
        isError = true
    )
}
