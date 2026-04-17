package com.shaun.agentbox

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.provider.Settings
import android.net.Uri
import com.shaun.agentbox.ui.FloatingWindowService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.shaun.agentbox.mcp.McpService
import com.shaun.agentbox.mcp.AiTeacherManager
import com.shaun.agentbox.sandbox.LinuxEnvironmentManager
import com.shaun.agentbox.sandbox.SandboxManager
import com.shaun.agentbox.sandbox.SandboxBackupManager
import com.shaun.agentbox.ui.theme.AgentBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AgentBoxTheme { AgentBoxApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBoxApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sandboxManager = remember { SandboxManager(context) }
    val linuxManager = remember { LinuxEnvironmentManager(context) }
    val backupManager = remember { SandboxBackupManager(context) }
    val aiTeacherManager = remember { AiTeacherManager(context) }

    var envInstalled by remember { mutableStateOf(linuxManager.isInstalled) }
    var installProgress by remember { mutableIntStateOf(0) }
    var installStatus by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(McpService.isRunning) }
    var isFloatingRunning by remember { mutableStateOf(FloatingWindowService.isRunning) }
    var serverAddress by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isRunning = McpService.isRunning
            isFloatingRunning = FloatingWindowService.isRunning
            if (isRunning) {
                serverAddress = "http://${McpService.getLocalIpAddress()}:${McpService.PORT}/sse"
            }
            delay(1000)
        }
    }

    var currentRelativePath by remember { mutableStateOf("") }
    var currentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentRelativePath, refreshTrigger) {
        val dir = if (currentRelativePath.isEmpty()) {
            sandboxManager.workspaceDir
        } else {
            File(sandboxManager.workspaceDir, currentRelativePath)
        }
        currentFiles = (dir.listFiles()?.toList() ?: emptyList())
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
    }

    var logs by remember { mutableStateOf(listOf<String>()) }
    DisposableEffect(Unit) {
        McpService.onLog = { msg -> logs = (logs + msg).takeLast(200) }
        onDispose { McpService.onLog = null }
    }
    var showLog by remember { mutableStateOf(false) }

    // Backup/Restore State
    var backupProgress by remember { mutableIntStateOf(-1) }
    var backupStatus by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        uri?.let {
            scope.launch {
                try {
                    backupProgress = 0
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        backupManager.exportFullBackup(os) { p, s -> backupProgress = p; backupStatus = s }
                    }
                    Toast.makeText(context, "Export Successful", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally { backupProgress = -1 }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                try {
                    backupProgress = 0
                    context.contentResolver.openInputStream(it)?.use { isStream ->
                        backupManager.importFullBackup(isStream) { p, s -> backupProgress = p; backupStatus = s }
                    }
                    envInstalled = linuxManager.isInstalled
                    refreshTrigger++
                    Toast.makeText(context, "Import Successful", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import Failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally { backupProgress = -1 }
            }
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    // Settings Screen
    if (showSettings) {
        AiTeacherSettingsScreen(
            manager = aiTeacherManager,
            onBack = { showSettings = false }
        )
        return@AgentBoxApp
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AgentBox", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (currentRelativePath.isEmpty()) "/" else "/$currentRelativePath",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (currentRelativePath.isNotEmpty()) {
                        IconButton(onClick = { currentRelativePath = currentRelativePath.substringBeforeLast('/', "") }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) { Icon(Icons.Default.Refresh, contentDescription = null) }
                    IconButton(onClick = { showLog = !showLog }) {
                        Icon(if (showLog) Icons.Default.Folder else Icons.Default.Terminal, contentDescription = null)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("AI Teacher Settings") },
                                onClick = { showMenu = false; showSettings = true },
                                leadingIcon = { Icon(Icons.Default.School, null) }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Share Workspace (Zip)") },
                                onClick = {
                                    showMenu = false
                                    scope.launch {
                                        val zipFile = withContext(Dispatchers.IO) {
                                            val f = File(context.cacheDir, "workspace_${System.currentTimeMillis()}.zip")
                                            sandboxManager.zipWorkspace(f); f
                                        }
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Share Workspace"))
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null) }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Export Full Backup (.tar.gz)") },
                                onClick = {
                                    showMenu = false
                                    exportLauncher.launch("agentbox_backup_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.tar.gz")
                                },
                                leadingIcon = { Icon(Icons.Default.Backup, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Import Full Backup (.tar.gz)") },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream"))
                                },
                                leadingIcon = { Icon(Icons.Default.Restore, null) }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (backupProgress >= 0) {
                        LinearProgressIndicator(progress = { backupProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(text = backupStatus, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                    } else if (!envInstalled) {
                        LinearProgressIndicator(progress = { installProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (isInstalling) "Installing: $installStatus" else "Linux Env Not Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (!isInstalling) {
                                Button(
                                    onClick = {
                                        isInstalling = true
                                        scope.launch {
                                            try {
                                                linuxManager.install { p, s -> installProgress = p; installStatus = s }
                                                envInstalled = linuxManager.isInstalled
                                                Toast.makeText(context, "✅ Linux Env Installed!", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                installStatus = "Error: ${e.message}"
                                                Log.e("AgentBox", "Install failed", e)
                                                Toast.makeText(context, "❌ Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally { isInstalling = false }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) { Text("Install", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isRunning) "● Running" else "○ Stopped",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isRunning) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            if (isRunning) {
                                Text(serverAddress, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                if (!Settings.canDrawOverlays(context)) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                } else {
                                    val intent = Intent(context, FloatingWindowService::class.java)
                                    if (isFloatingRunning) context.stopService(intent) else context.startForegroundService(intent)
                                }
                            }) {
                                Icon(if (isFloatingRunning) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Float")
                            }
                            FilledTonalButton(
                                onClick = {
                                    val intent = Intent(context, McpService::class.java)
                                    if (isRunning) context.stopService(intent) else context.startForegroundService(intent)
                                },
                                enabled = envInstalled
                            ) {
                                Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text(if (isRunning) "Stop" else "Start")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (showLog) {
            LogPanel(logs = logs, modifier = Modifier.padding(padding).fillMaxSize())
        } else {
            if (currentFiles.isEmpty()) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty directory", color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    items(currentFiles, key = { it.absolutePath }) { file ->
                        FileListItem(file = file, onClick = {
                            if (file.isDirectory) {
                                currentRelativePath = if (currentRelativePath.isEmpty()) file.name else "$currentRelativePath/${file.name}"
                            }
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTeacherSettingsScreen(
    manager: AiTeacherManager,
    onBack: () -> Unit
) {
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val config = manager.loadConfig()
        endpoint = config.endpoint
        apiKey = config.apiKey
        model = config.model
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Teacher Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it; isSaved = false },
                label = { Text("API Endpoint") },
                placeholder = { Text("https://api.openai.com/v1/chat/completions") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; isSaved = false },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide" else "Show"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it; isSaved = false },
                label = { Text("Model Name") },
                placeholder = { Text("gpt-4o") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    manager.saveConfig(com.shaun.agentbox.mcp.AiTeacherConfig(endpoint, apiKey, model))
                    isSaved = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = endpoint.isNotEmpty() && apiKey.isNotEmpty() && model.isNotEmpty()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Configuration")
            }

            if (isSaved) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Configuration saved!", color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider()

            Text(
                "The AI Teacher allows the sandboxed AI to ask questions to a more powerful model. Configure your OpenAI-compatible API above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "Config file location:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                manager.loadConfig().let { "ai_teacher_config.json" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun FileListItem(file: File, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val info = if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else formatFileSize(file.length())
            Text("$info · ${dateFormat.format(Date(file.lastModified()))}", style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else Color.Gray
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(logs.size) { scrollState.animateScrollTo(scrollState.maxValue) }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Text(
            text = logs.joinToString("\n"),
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}
