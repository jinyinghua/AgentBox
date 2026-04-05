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
import java.net.URL

/**
 * Linux 环境管理器 (PRoot + Alpine)
 * 核心逻辑：下载静态编译的 proot 和 Alpine rootfs，并在 Android 私有目录下构建环境。
 */
class LinuxEnvironmentManager(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnv"
        
        // 使用 SourceForge 提供的静态编译 proot (aarch64)
        private const val PROOT_URL = "https://sourceforge.net/projects/proot.mirror/files/v5.3.0/proot-v5.3.0-aarch64-static/download"
        
        // 使用清华大学 TUNA 镜像的 Alpine Mini Rootfs (ARM64)
        private const val ROOTFS_URL = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.9-aarch64.tar.gz"
    }

    private val systemDir = File(context.filesDir, "system_rootfs")
    val prootBin = File(systemDir, "proot")
    val rootfsDir = File(systemDir, "alpine")

    // 检查环境是否完整
    val isInstalled: Boolean get() = prootBin.exists() && File(rootfsDir, "bin/sh").exists()

    /**
     * 安装环境 (下载并解压)
     * @param onProgress 进度回调 (进度值, 状态描述)
     */
    suspend fun install(onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled) {
            onProgress(100, "Already installed")
            return@withContext
        }

        if (!systemDir.exists()) systemDir.mkdirs()
        if (!rootfsDir.exists()) rootfsDir.mkdirs()

        // 1. 下载 proot
        onProgress(10, "Downloading proot engine...")
        downloadFile(PROOT_URL, prootBin)
        prootBin.setExecutable(true)

        // 2. 下载 Rootfs
        onProgress(30, "Downloading Alpine Rootfs...")
        val tarGzFile = File(systemDir, "alpine.tar.gz")
        downloadFile(ROOTFS_URL, tarGzFile)

        // 3. 解压 (处理 Symlinks)
        onProgress(60, "Extracting Rootfs (this may take a while)...")
        extractTarGz(tarGzFile, rootfsDir)
        tarGzFile.delete()

        // 4. 配置网络 (DNS)
        onProgress(95, "Configuring DNS...")
        setupDns()

        onProgress(100, "Installation complete")
    }

    /**
     * 带重定向处理和 User-Agent 的文件下载逻辑
     */
    private fun downloadFile(urlString: String, outFile: File) {
        try {
            var url = URL(urlString)
            var redirectCount = 0
            while (redirectCount < 5) {
                val connection = url.openConnection() as java.net.HttpURLConnection
                
                // ✅ 核心修复：添加模拟浏览器的 User-Agent 头部，防止被镜像站拦截 (解决 403 错误)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36")
                
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                
                val status = connection.responseCode
                if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                    status == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                    status == java.net.HttpURLConnection.HTTP_SEE_OTHER) {
                    val newUrl = connection.getHeaderField("Location")
                    url = URL(url, newUrl)
                    redirectCount++
                    continue
                }

                if (status != java.net.HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("HTTP Error $status: ${connection.responseMessage}")
                }
                
                connection.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return
            }
            throw java.io.IOException("Too many redirects")
        } catch (e: Exception) {
            // 抛出带有具体异常类名的信息，方便排查
            throw Exception("${e.javaClass.simpleName}: ${e.message ?: "no message"}", e)
        }
    }

    /**
     * 解压 tar.gz 并还原软链接
     */
    private fun extractTarGz(tarGzFile: File, destDir: File) {
        TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream())).use { tarInput ->
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
        etcDir.mkdirs()
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
