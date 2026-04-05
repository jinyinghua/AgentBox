package com.shaun.agentbox.sandbox

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 沙盒管理器。
 *
 * 管理 ai_workspace 目录——这是 AI Agent 的唯一操作空间。
 * 所有文件操作都必须通过 resolveFile() 进行路径校验，防止路径逃逸。
 */
class SandboxManager(private val context: Context) {

    val workspaceDir: File = File(context.filesDir, "ai_workspace").also { dir ->
        if (!dir.exists()) {
            dir.mkdirs()
            File(dir, "README.md").writeText(
                "# AgentBox Workspace\n\n" +
                "This is the sandboxed workspace for AI agents.\n" +
                "All file operations are restricted to this directory.\n"
            )
        }
    }

    /**
     * 解析相对路径为实际文件，并强制校验不能逃逸出沙盒。
     * @param relativePath 相对于 workspaceDir 的路径
     * @return 规范化后的 File 对象
     * @throws SecurityException 如果路径逃逸出沙盒
     */
    fun resolveFile(relativePath: String): File {
        val canonical = File(workspaceDir, relativePath).canonicalFile
        val sandboxCanonical = workspaceDir.canonicalPath

        if (!canonical.path.startsWith(sandboxCanonical)) {
            throw SecurityException(
                "Path traversal blocked: '$relativePath' resolves outside sandbox"
            )
        }
        return canonical
    }

    /**
     * 获取文件相对于 workspace 的路径
     */
    fun getRelativePath(file: File): String {
        return file.absolutePath
            .removePrefix(workspaceDir.absolutePath)
            .removePrefix(File.separator)
    }

    /**
     * 将整个 workspace 打包为 ZIP 文件。
     * 调用方应在 IO 线程执行。
     */
    fun zipWorkspace(outputFile: File) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
            workspaceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = getRelativePath(file)
                    if (entryName.isNotEmpty()) {
                        zipOut.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { input -> input.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
        }
    }
}
