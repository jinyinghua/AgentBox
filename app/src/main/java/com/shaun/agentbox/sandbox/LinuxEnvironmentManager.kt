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
 */
class LinuxEnvironmentManager(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnv"
        // 使用静态编译的 proot (来自 termux-packages 或 proot-me)
        private const val PROOT_URL = "https://sourceforge.net/projects/proot.mirror/files/v5.3.0/proot-v5.3.0-aarch64-static/download"
        // Alpine Mini Rootfs (ARM64)
        private const val ROOTFS_URL = "https://mirrors.aliyun.com/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz"
    }

    private val systemDir = File(context.filesDir, "system_rootfs")
    val prootBin = File(systemDir, "proot")
    val rootfsDir = File(systemDir, "alpine")
    val isInstalled: Boolean get() = prootBin.exists() && File(rootfsDir, "bin/sh").exists()

    /**
     * 安装环境 (下载并解压)
     * @param onProgress 进度回调 (0..100)
     */
    suspend fun install(onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled) {
            onProgress(100, "Already installed")
            return@withContext
        }

        systemDir.mkdirs()
        rootfsDir.mkdirs()

        // 1. 下载 proot
        onProgress(10, "Downloading proot engine...")
        downloadFile(PROOT_URL, prootBin)
        prootBin.setExecutable(true)

        // 2. 下载 Rootfs
        onProgress(30, "Downloading Alpine Rootfs...")
        val tarGzFile = File(systemDir, "alpine.tar.gz")
        downloadFile(ROOTFS_URL, tarGzFile)

        // 3. 解压 (必须处理 Symlinks)
        onProgress(60, "Extracting Rootfs (this may take a while)...")
        extractTarGz(tarGzFile, rootfsDir)
        tarGzFile.delete()

        // 4. 配置网络 (DNS)
        onProgress(95, "Configuring DNS...")
        setupDns()

        onProgress(100, "Installation complete")
    }

    private fun downloadFile(urlString: String, outFile: File) {
        var url = URL(urlString)
        var redirectCount = 0
        while (redirectCount < 5) {
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false // 手动处理重定向 (SourceForge需要)
            
            val status = connection.responseCode
            if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                status == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                status == java.net.HttpURLConnection.HTTP_SEE_OTHER) {
                val newUrl = connection.getHeaderField("Location")
                url = URL(url, newUrl)
                redirectCount++
                continue
            }
            
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            break
        }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream())).use { tarInput ->
            var entry = tarInput.nextTarEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else if (entry.isSymbolicLink) {
                    // 核心：在 Android 上还原 Linux 软链接
                    try {
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
        // 写入公共 DNS，否则 apk add 会失败
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    /**
     * 构造 proot 执行命令
     */
    fun buildProotCommand(workspaceDir: File, userCommand: String): Array<String> {
        return arrayOf(
            prootBin.absolutePath,
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
