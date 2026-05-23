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

    fun getSshdLogFile(): File = File(rootfsDir, "tmp/agentbox-sshd.log")

    fun isSshPrepared(): Boolean {
        return isInstalled &&
            sshPrivateKeyFile.exists() &&
            sshPublicKeyFile.exists() &&
            File(rootfsDir, "root/.ssh/authorized_keys").exists() &&
            File(rootfsDir, "usr/sbin/sshd").exists() &&
            File(rootfsDir, "etc/ssh/sshd_config").exists()
    }

    suspend fun ensureSshPrepared() = withContext(Dispatchers.IO) {
        check(isInstalled) { "Linux environment not installed." }
        setupDns()
        if (!isSshPrepared()) {
            setupOpenSsh()
        } else {
            setupSshdConfigAndAccounts()
        }
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
            mkdir -p /var/run /run/sshd /root/.ssh /tmp /workspace /var/empty /var/empty/sshd
            chmod 700 /root/.ssh
            chmod 600 $escapedAuthKeys || true
            chmod 755 /var/empty /var/empty/sshd || true
            mkdir -p /etc
            [ -f /etc/passwd ] || echo 'root:x:0:0:root:/root:/bin/sh' > /etc/passwd
            [ -f /etc/group ] || echo 'root:x:0:' > /etc/group
            grep -q '^sshd:' /etc/passwd 2>/dev/null || echo 'sshd:x:22:22:sshd privsep:/var/empty:/sbin/nologin' >> /etc/passwd
            grep -q '^sshd:' /etc/group 2>/dev/null || echo 'sshd:x:22:' >> /etc/group
            [ -f /sbin/nologin ] || ln -sf /bin/false /sbin/nologin
            [ -f /etc/ssh/ssh_host_rsa_key ] || /usr/bin/ssh-keygen -A
            chmod 600 /etc/ssh/ssh_host_*_key 2>/dev/null || true
            chmod 644 /etc/ssh/ssh_host_*_key.pub 2>/dev/null || true
         rhd.log    /ig -h /etc/ssh/ssh_host_rsa_key -p $SSH_PORT -o PidFile=/var/run/sshd.pid 2>/tmp/agentbox-sshd-test.log
            tcode=$?
            if [ $tcode -ne 0 ]; then
              echo "sshd config test failed (exit $tcode)" >&2
              cat /tmp/agentbox-sshd-test.log >&2 2>/dev/null
              exit 111
            fi
            exec /usr/sbin/sshd -D -e -E /tmp/agentbox-sshd.log -f /etc/ssh/sshd_config -p $SSH_PORT -h /etc/ssh/ssh_host_rsa_key -o PidFile=/var/run/sshd.pid
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

        runBootstrapCommandAllowingKnownApkWarnings("apk update && apk add --no-cache openssh")
        setupSshdConfigAndAccounts()
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

    private fun setupSshdConfigAndAccounts() {
        val etcDir = File(rootfsDir, "etc")
        val sshDir = File(etcDir, "ssh")
        val rootSshDir = File(rootfsDir, "root/.ssh")
        val runSshdDir = File(rootfsDir, "run/sshd")
        val varRunSshdDir = File(rootfsDir, "var/run/sshd")
        val varEmptyDir = File(rootfsDir, "var/empty")
        val varEmptySshdDir = File(rootfsDir, "var/empty/sshd")
        sshDir.mkdirs()
        rootSshDir.mkdirs()
        runSshdDir.mkdirs()
        varRunSshdDir.mkdirs()
        varEmptyDir.mkdirs()
        varEmptySshdDir.mkdirs()
        rootSshDir.setReadable(true, false)
        rootSshDir.setWritable(true, false)
        rootSshDir.setExecutable(true, false)

        val passwd = File(etcDir, "passwd")
        if (!passwd.exists()) passwd.writeText("root:x:0:0:root:/root:/bin/sh\n")
        ensureLine(passwd, "sshd:", "sshd:x:22:22:sshd privsep:/var/empty:/sbin/nologin")

        val group = File(etcDir, "group")
        if (!group.exists()) group.writeText("root:x:0:\n")
        ensureLine(group, "sshd:", "sshd:x:22:")

        val nologin = File(rootfsDir, "sbin/nologin")
        if (!nologin.exists()) {
            nologin.parentFile?.mkdirs()
            runCatching { Os.symlink("/bin/false", nologin.absolutePath) }
        }

        File(sshDir, "sshd_config").writeText(buildSshdConfigText())
        File(rootfsDir, "tmp").mkdirs()
    }

    private fun ensureLine(file: File, prefix: String, line: String) {
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        if (lines.none { it.startsWith(prefix) }) {
            lines.add(line)
            file.writeText(lines.joinToString("\n") + "\n")
        }
    }

    private fun buildSshdConfigText(): String {
        return """
            Port $SSH_PORT
            ListenAddress 127.0.0.1
            HostKey /etc/ssh/ssh_host_rsa_key
            PasswordAuthentication no
            PermitEmptyPasswords no
            PermitRootLogin yes
            PubkeyAuthentication yes
            AuthorizedKeysFile .ssh/authorized_keys
            StrictModes no
            PidFile /var/run/sshd.pid
            PrintMotd no
            PermitTTY yes
            X11Forwarding no
            AllowTcpForwarding no
            ClientAliveInterval 30
            ClientAliveCountMax 3
            LogLevel DEBUG3
            Subsystem sftp internal-sftp
        """.trimIndent() + "\n"
    }

    private fun buildSshdConfigScript(): String {
        val escaped = shellEscape(buildSshdConfigText())
        return "printf '%s\n' $escaped > /etc/ssh/sshd_config"
    }

    private fun runBootstrapCommandAllowingKnownApkWarnings(command: String): String {
        return try {
            runBootstrapCommand(command)
        } catch (e: Exception) {
            // apk exit code 2 means non-fatal trigger script errors in proot.
            // Package files are still installed successfully.
            if (command.contains("apk")) {
                Log.w(TAG, "apk returned non-zero exit code (trigger errors in proot), ignoring: ${e.message}")
                ""
            } else {
                throw e
            }
        }
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
