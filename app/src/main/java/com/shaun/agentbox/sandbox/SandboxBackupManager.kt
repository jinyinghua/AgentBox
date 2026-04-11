package com.shaun.agentbox.sandbox

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/**
 * 沙箱备份与恢复管理器
 * 负责导出和导入完整的沙箱环境，包含系统配置、软件及沙箱文件。
 */
class SandboxBackupManager(private val context: Context) {

    private val tag = "SandboxBackup"
    private val systemDir = File(context.filesDir, "system_rootfs")
    private val rootfsDir = File(systemDir, "alpine")
    private val workspaceDir = File(context.filesDir, "ai_workspace")

    /**
     * 导出完整环境到 .tar.gz
     */
    suspend fun exportFullBackup(outputStream: java.io.OutputStream, onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            onProgress(5, "Initializing export...")
            GzipCompressorOutputStream(outputStream).use { gzos ->
                TarArchiveOutputStream(gzos).use { tarOut ->
                    tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                    // 1. 导出 rootfs (系统和软件)
                    onProgress(10, "Packing system and software...")
                    if (rootfsDir.exists()) {
                        addDirectoryToTar(tarOut, rootfsDir, "rootfs")
                    }

                    // 2. 导出 workspace (沙箱文件)
                    onProgress(60, "Packing workspace files...")
                    if (workspaceDir.exists()) {
                        addDirectoryToTar(tarOut, workspaceDir, "workspace")
                    }

                    // 3. 元数据
                    onProgress(95, "Adding metadata...")
                    val metadata = "version=1.0\ntimestamp=${System.currentTimeMillis()}\ntarget=AgentBox"
                    val entry = TarArchiveEntry("backup_info.txt")
                    val bytes = metadata.toByteArray()
                    entry.size = bytes.size.toLong()
                    tarOut.putNextEntry(entry)
                    tarOut.write(bytes)
                    tarOut.closeEntry()
                }
            }
            onProgress(100, "Export completed!")
        } catch (e: Exception) {
            Log.e(tag, "Export failed", e)
            throw e
        }
    }

    /**
     * 从 .tar.gz 流导入
     */
    suspend fun importFullBackup(inputStream: InputStream, onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            onProgress(5, "Initializing import...")
            
            // 导入前清理现有环境
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            if (workspaceDir.exists()) workspaceDir.deleteRecursively()
            
            rootfsDir.mkdirs()
            workspaceDir.mkdirs()

            GzipCompressorInputStream(inputStream).use { gzis ->
                TarArchiveInputStream(gzis).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val path = entry.name
                        val destFile = when {
                            path.startsWith("rootfs/") -> File(rootfsDir, path.removePrefix("rootfs/"))
                            path.startsWith("workspace/") -> File(workspaceDir, path.removePrefix("workspace/"))
                            else -> null
                        }

                        if (destFile != null) {
                            if (entry.isDirectory) {
                                destFile.mkdirs()
                                destFile.setExecutable(true, false)
                                destFile.setWritable(true, false)
                                destFile.setReadable(true, false)
                            } else if (entry.isSymbolicLink) {
                                try {
                                    Os.symlink(entry.linkName, destFile.absolutePath)
                                } catch (e: Exception) {
                                    Log.e(tag, "Symlink fail: ${entry.name}", e)
                                }
                            } else {
                                destFile.parentFile?.mkdirs()
                                FileOutputStream(destFile).use { output ->
                                    tarIn.copyTo(output)
                                }
                                // 恢复权限 (模拟 mode)
                                if ((entry.mode and 73) != 0) {
                                    destFile.setExecutable(true, false)
                                }
                                destFile.setWritable(true, false)
                                destFile.setReadable(true, false)
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }

            // 恢复 proot
            val prootBin = File(systemDir, "proot")
            if (!prootBin.exists()) {
                onProgress(95, "Restoring proot engine...")
                context.assets.open("proot").use { input ->
                    FileOutputStream(prootBin).use { output -> input.copyTo(output) }
                }
                prootBin.setExecutable(true, false)
            }

            onProgress(100, "Import completed!")
        } catch (e: Exception) {
            Log.e(tag, "Import failed", e)
            throw e
        }
    }

    private fun addDirectoryToTar(tarOut: TarArchiveOutputStream, dir: File, rootName: String) {
        val baseUri = dir.toURI()
        dir.walkTopDown().forEach { file ->
            val relativePath = rootName + "/" + baseUri.relativize(file.toURI()).path
            if (relativePath == rootName + "/") return@forEach

            val entry = TarArchiveEntry(file, relativePath)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Files.isSymbolicLink(file.toPath())) {
                entry.linkName = Files.readSymbolicLink(file.toPath()).toString()
            }

            tarOut.putNextEntry(entry)
            if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                FileInputStream(file).use { it.copyTo(tarOut) }
            }
            tarOut.closeEntry()
        }
    }
}
