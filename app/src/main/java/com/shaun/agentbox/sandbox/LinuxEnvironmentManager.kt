package com.shaun.agentbox.sandbox

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Linux 环境管理器 (升级版：解决权限和环境缺失问题)
 */
class LinuxEnvironmentManager(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnv"
        private const val PROOT_ASSET = "proot"
        private const val ALPINE_ASSET = "alpine.tar"
    }

    private val systemDir = File(context.filesDir, "system_rootfs")
    val prootBin = File(systemDir, "proot")
    val rootfsDir = File(systemDir, "alpine")

    val tmpDir: File get() {
        val dir = File(context.cacheDir, "proot_tmp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    val isInstalled: Boolean get() = prootBin.exists() && File(rootfsDir, "etc/os-release").exists()

    suspend fun install(onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            if (!systemDir.exists()) systemDir.mkdirs()
            if (rootfsDir.exists()) rootfsDir.deleteRecursively() // 彻底重装以修复旧的权限问题
            rootfsDir.mkdirs()

            onProgress(10, "Extracting proot engine...")
            copyAssetToFile(PROOT_ASSET, prootBin)
            prootBin.setExecutable(true, false)

            onProgress(30, "Installing Linux Rootfs (Fixing permissions)...")
            context.assets.open(ALPINE_ASSET).use { assetStream ->
                extractTarGzFromStream(assetStream, rootfsDir)
            }

            onProgress(90, "Setting up DNS and Network...")
            setupDns()

            onProgress(100, "Alpine Linux environment is ready!")
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            throw e
        }
    }

    private fun copyAssetToFile(assetName: String, outFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractTarGzFromStream(inputStream: InputStream, destDir: File) {
        TarArchiveInputStream(inputStream).use { tarInput ->
            var entry = tarInput.nextTarEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    // 【修复3】确保目录有写入和进入权限，解决 apk add 权限问题
                    outFile.setExecutable(true, false)
                    outFile.setWritable(true, false)
                    outFile.setReadable(true, false)
                } else if (entry.isSymbolicLink) {
                    try {
                        Os.symlink(entry.linkName, outFile.absolutePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Symlink fail: ${entry.name}", e)
                    }
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        tarInput.copyTo(output)
                    }
                    // 【修复1】恢复执行权限
                    if ((entry.mode and 73) != 0) {
                        outFile.setExecutable(true, false)
                    }
                    // 【修复3】确保文件对所有者可写
                    outFile.setWritable(true, false)
                    outFile.setReadable(true, false)
                }
                entry = tarInput.nextTarEntry
            }
        }
    }

    private fun setupDns() {
        val etcDir = File(rootfsDir, "etc")
        if (!etcDir.exists()) etcDir.mkdirs()
        // 增加权限确保 resolv.conf 可被 apk 读取
        val resolv = File(etcDir, "resolv.conf")
        resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        resolv.setReadable(true, false)
        resolv.setWritable(true, false)
    }

    fun buildProotCommand(workspaceDir: File, userCommand: String): Array<String> {
        return arrayOf(
            prootBin.absolutePath,
            "-0", // 模拟 Root
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/pts", // 增加 pts 挂载支持某些命令
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-w", "/workspace",
            "/bin/sh", "-c", userCommand
        )
    }
}
