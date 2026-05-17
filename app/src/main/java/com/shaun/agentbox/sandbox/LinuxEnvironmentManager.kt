package com.shaun.agentbox.sandbox

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Linux 环境管理器
 *
 * 终端不再复用 MCP 的一次性 execute_command，而是在安装 Linux 环境时
 * 直接初始化 openssh，并在应用内通过 SSH 建立持久会话。
 */
class LinuxEnvironmentManager(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnv"
        private const val PROOT_ASSET = "proot"
        private const val ALPINE_ASSET = "alpine.tar.gz"
        private const val SSH_PORT = 8022
        private const val SSH_USER = "root"
    }

    private val systemDir = File(context.filesDir, "system_rootfs")
    val prootBin = File(systemDir, "proot")
    val rootfsDir = File(systemDir, "alpine")
    private val sshKeyDir = File(systemDir, "ssh")
    private val sshPrivateKeyFile = File(sshKeyDir, "id_rsa")
    private val sshPublicKeyFile = File(sshKeyDir, "id_rsa.pub")
    private val sshMetaFile = File(sshKeyDir, "meta.properties")

    val tmpDir: File
        get() {
            val dir = File(context.cacheDir, "proot_tmp")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val isInstalled: Boolean
        get() = prootBin.exists() && File(rootfsDir, "etc/os-release").exists()

    suspend fun install(onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            if (!systemDir.exists()) systemDir.mkdirs()
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(10, "Extracting proot engine...")
            copyAssetToFile(PROOT_ASSET, prootBin)
            prootBin.setExecutable(true, false)

            onProgress(30, "Installing Linux Rootfs...")
            context.assets.open(ALPINE_ASSET).use { assetStream ->
                extractTarGzFromStream(assetStream, rootfsDir)
            }

            onProgress(70, "Setting up DNS and shell environment...")
            setupDns()

            onProgress(80, "Installing OpenSSH and generating login credentials...")
            setupOpenSsh()

            onProgress(100, "Alpine Linux environment is ready!")
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            throw Exception("Asset Error [${e.javaClass.simpleName}]: ${e.message}", e)
        }
    }

    fun getSshHost(): String = "127.0.0.1"
    fun getSshPort(): Int = SSH_PORT
    fun getSshUser(): String = SSH_USER
    fun getSshPrivateKeyFile(): File = sshPrivateKeyFile

    fun isSshPrepared(): Boolean {
        return isInstalled &&
            sshPrivateKeyFile.exists() &&
            sshPublicKeyFile.exists() &&
            File(rootfsDir, "root/.ssh/authorized_keys").exists() &&
            File(rootfsDir, "usr/sbin/sshd").exists()
    }

    fun buildProotCommand(workspaceDir: File, userCommand: String): Array<String> {
        return arrayOf(
            prootBin.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/pts",
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-w", "/workspace",
            "/bin/sh", "-c", userCommand
        )
    }

    fun buildSshDaemonCommand(workspaceDir: File): Array<String> {
        val escapedAuthKeys = shellEscape("/root/.ssh/authorized_keys")

        val script = """
            mkdir -p /var/run /run/sshd /root/.ssh /tmp /workspace
            chmod 700 /root/.ssh
            chmod 600 $escapedAuthKeys || true
            mkdir -p /etc
            [ -f /etc/passwd ] || echo 'root:x:0:0:root:/root:/bin/sh' > /etc/passwd
            [ -f /etc/group ] || echo 'root:x:0:' > /etc/group
            exec /usr/sbin/sshd -D -e -f /etc/ssh/sshd_config -p $SSH_PORT -h /etc/ssh/ssh_host_rsa_key -o PidFile=/var/run/sshd.pid
        """.trimIndent()

        return arrayOf(
            prootBin.absolutePath,
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/pts",
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-w", "/workspace",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR=${tmpDir.absolutePath}",
            "/bin/sh", "-lc", script
        )
    }

    private fun copyAssetToFile(assetName: String, outFile: File) {
        outFile.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractTarGzFromStream(inputStream: InputStream, destDir: File) {
        GZIPInputStream(inputStream).use { gzipInput ->
            TarArchiveInputStream(gzipInput).use { tarInput ->
                var entry = tarInput.nextTarEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                        outFile.setExecutable(true, false)
                        outFile.setWritable(true, false)
                        outFile.setReadable(true, false)
                    } else if (entry.isSymbolicLink) {
                        try {
                            outFile.parentFile?.mkdirs()
                            Os.symlink(entry.linkName, outFile.absolutePath)
                        } catch (e: Exception) {
                            Log.e(TAG, "Symlink fail: ${entry.name}", e)
                        }
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            tarInput.copyTo(output)
                        }
                        if ((entry.mode and 73) != 0) {
                            outFile.setExecutable(true, false)
                        }
                        outFile.setWritable(true, false)
                        outFile.setReadable(true, false)
                    }
                    entry = tarInput.nextTarEntry
                }
            }
        }
    }

    private fun setupDns() {
        val etcDir = File(rootfsDir, "etc")
        if (!etcDir.exists()) etcDir.mkdirs()
        val resolv = File(etcDir, "resolv.conf")
        resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        resolv.setReadable(true, false)
        resolv.setWritable(true, false)
    }

    private fun setupOpenSsh() {
        sshKeyDir.mkdirs()
        val token = UUID.randomUUID().toString().replace("-", "")
        val comment = "agentbox-$token"

        runBootstrapCommand("apk update && apk add --no-cache openssh")
        runBootstrapCommand("mkdir -p /var/run /run/sshd /root/.ssh /etc/ssh")
        runBootstrapCommand("chmod 700 /root/.ssh")
        runBootstrapCommand("ssh-keygen -A")
        runBootstrapCommand("rm -f /root/.ssh/authorized_keys")
        runBootstrapCommand("ssh-keygen -t rsa -b 3072 -N '' -C ${shellEscape(comment)} -f /workspace/.agentbox_tmpkey")

        val privateKey = runBootstrapCommand("cat /workspace/.agentbox_tmpkey")
        val publicKey = runBootstrapCommand("cat /workspace/.agentbox_tmpkey.pub")
        sshPrivateKeyFile.writeText(privateKey)
        sshPublicKeyFile.writeText(publicKey)
        sshPrivateKeyFile.setReadable(true, true)
        sshPrivateKeyFile.setWritable(true, true)
        sshPublicKeyFile.setReadable(true, true)
        sshPublicKeyFile.setWritable(true, true)

        val escapedPublicKey = shellEscape(publicKey.trim())
        runBootstrapCommand("printf '%s\\n' $escapedPublicKey > /root/.ssh/authorized_keys")
        runBootstrapCommand("chmod 600 /root/.ssh/authorized_keys")
        runBootstrapCommand("rm -f /workspace/.agentbox_tmpkey /workspace/.agentbox_tmpkey.pub")
        runBootstrapCommand(buildSshdConfigScript())

        sshMetaFile.writeText(
            listOf(
                "host=${getSshHost()}",
                "port=${getSshPort()}",
                "user=${getSshUser()}"
            ).joinToString("\n") + "\n"
        )
    }

    private fun buildSshdConfigScript(): String {
        val config = """
            Port $SSH_PORT
            ListenAddress 127.0.0.1
            Protocol 2
            HostKey /etc/ssh/ssh_host_rsa_key
            PasswordAuthentication no
            KbdInteractiveAuthentication no
            ChallengeResponseAuthentication no
            PermitRootLogin prohibit-password
            PubkeyAuthentication yes
            AuthorizedKeysFile /root/.ssh/authorized_keys
            PidFile /var/run/sshd.pid
            PrintMotd no
            UsePAM no
            PermitTTY yes
            X11Forwarding no
            AllowTcpForwarding no
            ClientAliveInterval 30
            ClientAliveCountMax 3
            Subsystem sftp internal-sftp
        """.trimIndent()
        val escaped = shellEscape(config)
        return "printf '%s\n' $escaped > /etc/ssh/sshd_config"
    }

    private fun runBootstrapCommand(command: String): String {
        val workspaceDir = File(context.filesDir, "bootstrap_workspace")
        workspaceDir.mkdirs()
        val processBuilder = ProcessBuilder(*buildProotCommand(workspaceDir, command))
            .directory(workspaceDir)
            .redirectErrorStream(true)

        val env = processBuilder.environment()
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["TERM"] = "xterm-256color"
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Bootstrap command failed ($exitCode): $command\n$output")
        }
        return output.trimEnd()
    }

    private fun shellEscape(text: String): String {
        return "'" + text.replace("'", "'\\''") + "'"
    }
}
