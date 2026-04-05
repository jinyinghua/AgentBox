package com.shaun.agentbox.sandbox

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Linux 环境管理器 (Assets 模式)
 * 核心逻辑：从 APK 内置的 Assets 目录提取 proot 和 Alpine rootfs。
 * 解决了网络环境（403 错误、防火墙等）导致安装失败的问题。
 */
class LinuxEnvironmentManager(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnv"
        // 对应 assets 中的文件名
        private const val PROOT_ASSET = "proot"
        private const val ALPINE_ASSET = "alpine.tar.gz"
    }

    private val systemDir = File(context.filesDir, "system_rootfs")
    val prootBin = File(systemDir, "proot")
    val rootfsDir = File(systemDir, "alpine")

    // 检查环境是否完整
    val isInstalled: Boolean get() = prootBin.exists() && File(rootfsDir, "bin/sh").exists()

    /**
     * 安装环境 (从 Assets 复制并解压)
     * @param onProgress 进度回调 (进度值, 状态描述)
     */
    suspend fun install(onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            if (isInstalled) {
                onProgress(100, "Already installed")
                return@withContext
            }

            if (!systemDir.exists()) systemDir.mkdirs()
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            // 1. 提取 proot
            onProgress(20, "Extracting proot engine from assets...")
            copyAssetToFile(PROOT_ASSET, prootBin)
            prootBin.setExecutable(true)

            // 2. 解压 Alpine Rootfs (直接从 Assets 流读取，节省存储空间)
            onProgress(50, "Installing Alpine Rootfs (this may take 1-2 minutes)...")
            context.assets.open(ALPINE_ASSET).use { assetStream ->
                extractTarGzFromStream(assetStream, rootfsDir)
            }

            // 3. 配置网络 (DNS)
            onProgress(95, "Finalizing environment...")
            setupDns()

            onProgress(100, "Installation successful!")
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            // 修改这里：抛出更详细的错误（包含完整的类名和堆栈的最顶层信息）
            throw Exception("Asset Error [${e.javaClass.simpleName}]: ${e.message}", e)
        }
    }

    private fun copyAssetToFile(assetName: String, outFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 直接从输入流解压 tar.gz
     */
    private fun extractTarGzFromStream(inputStream: InputStream, destDir: File) {
        TarArchiveInputStream(GzipCompressorInputStream(inputStream)).use { tarInput ->
            var entry = tarInput.nextTarEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else if (entry.isSymbolicLink) {
                    try {
                        // 在 Android 上创建 Linux 软链接
                        Os.symlink(entry.linkName, outFile.absolutePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create symlink: ${entry.name} -> ${entry.linkName}", e)
                    }
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        tarInput.copyTo(output)
                    }
                }
                entry = tarInput.nextTarEntry
            }
        }
    }

    private fun setupDns() {
        val etcDir = File(rootfsDir, "etc")
        if (!etcDir.exists()) etcDir.mkdirs()
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    /**
     * 构造 proot 执行命令
     */
    fun buildProotCommand(workspaceDir: File, userCommand: String): Array<String> {
        return arrayOf(
            prootBin.absolutePath,
            "-0",                                  // 模拟 root 权限
            "-r", rootfsDir.absolutePath,          // 根目录
            "-b", "/dev",                          // 挂载设备
            "-b", "/proc",                         // 挂载进程信息
            "-b", "/sys",                          // 挂载系统信息
            "-b", "${workspaceDir.absolutePath}:/workspace", // 挂载 AI 工作区
            "-w", "/workspace",                    // 设置容器内工作目录
            "/bin/sh", "-c", userCommand
        )
    }
}
