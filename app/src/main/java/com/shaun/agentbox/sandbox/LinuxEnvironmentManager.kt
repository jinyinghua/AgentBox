package com.shaun.agentbox.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.GZIPInputStream

class LinuxEnvironmentManager(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnvManager"
        // 使用静态编译的 proot (来自 termux-packages 或 proot-me)
        private const val PROOT_URL = "https://sourceforge.net/projects/proot.mirror/files/v5.3.0/proot-v5.3.0-aarch64-static/download"
        // Alpine Mini Rootfs (ARM64)
        private const val ROOTFS_URL = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.9-aarch64.tar.gz"
    }

    private val systemDir = File(context.filesDir, "system_rootfs")
    private val binDir = File(context.filesDir, "bin")
    private val prootFile = File(binDir, "proot")

    fun isReady(): Boolean {
        return systemDir.exists() && prootFile.exists()
    }

    /**
     * 初始化环境：下载并解压必要的组件
     */
    fun setupEnvironment(onProgress: (String) -> Unit, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                if (!binDir.exists()) binDir.mkdirs()
                if (!systemDir.exists()) systemDir.mkdirs()

                // 1. 下载 proot
                if (!prootFile.exists()) {
                    onProgress("Downloading proot...")
                    downloadFile(PROOT_URL, prootFile)
                    prootFile.setExecutable(true)
                }

                // 2. 下载并解压 Alpine rootfs
                if (systemDir.list()?.isEmpty() == true) {
                    onProgress("Downloading and extracting Alpine rootfs...")
                    val tempFile = File(context.cacheDir, "alpine.tar.gz")
                    downloadFile(ROOTFS_URL, tempFile)
                    extractTarGz(tempFile, systemDir)
                    tempFile.delete()
                }

                onComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                onProgress("Error: ${e.message}")
                onComplete(false)
            }
        }.start()
    }

    /**
     * 执行命令（在 proot 环境中）
     */
    fun execute(command: String): String {
        if (!isReady()) return "Environment not ready"

        val processBuilder = ProcessBuilder()
        // 使用 proot 模拟 root 环境并挂载必要的系统目录
        val fullCommand = listOf(
            prootFile.absolutePath,
            "-0", // 模拟 root 用户 (fake root)
            "-r", systemDir.absolutePath, // 指定新的根目录
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/sh", "-c", command
        )

        processBuilder.command(fullCommand)
        processBuilder.redirectErrorStream(true)

        return try {
            val process = processBuilder.start()
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Execution failed: ${e.message}"
        }
    }

    private fun downloadFile(urlStr: String, destFile: File) {
        URL(urlStr).openStream().use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractTarGz(tarGzFile: File, outputDir: File) {
        // 在 Android 上，直接使用系统命令解压最简单（通常系统自带 tar）
        val process = ProcessBuilder()
            .command("tar", "-xzf", tarGzFile.absolutePath, "-C", outputDir.absolutePath)
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw Exception("Tar extraction failed with exit code $exitCode")
        }
    }
}
