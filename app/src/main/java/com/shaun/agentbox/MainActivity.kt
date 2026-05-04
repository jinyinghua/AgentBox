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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.shaun.agentbox.mcp.McpService
import com.shaun.agentbox.mcp.AiTeacherManager
import com.shaun.agentbox.mcp.MultiAgentManager
import com.shaun.agentbox.mcp.MultiAgentSession
import com.shaun.agentbox.mcp.MultiAgentRuntimeManager
import com.shaun.agentbox.mcp.MultiAgentRuntimeSnapshot
import com.shaun.agentbox.mcp.SubAgentModelConfig
import com.shaun.agentbox.mcp.SubAgentModelConfigManager
import com.shaun.agentbox.mcp.ToolExecutor
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
    val toolExecutor = remember { ToolExecutor(context) }
    val multiAgentManager = remember { MultiAgentManager(context) }
    val multiAgentRuntimeManager = remember { MultiAgentRuntimeManager.getInstance(context) }
    val subAgentConfigManager = remember { SubAgentModelConfigManager(context) }

    var envInstalled by remember { mutableStateOf(linuxManager.isInstalled) }
    var installProgress by remember { mutableIntStateOf(0) }
    var installStatus by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(McpService.isRunning) }
    var isFloatingRunning by remember { mutableStateOf(FloatingWindowService.isRunning) }
    var serverAddress by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSubAgentSettings by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showMultiAgentBoard by remember { mutableStateOf(false) }

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

    // Terminal State
    var commandInput by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

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

    if (showSubAgentSettings) {
        SubAgentSettingsScreen(
            manager = subAgentConfigManager,
            onBack = { showSubAgentSettings = false }
        )
        return@AgentBoxApp
    }

    if (showMultiAgentBoard) {
        MultiAgentBoardScreen(
            manager = multiAgentManager,
            runtimeManager = multiAgentRuntimeManager,
            onBack = { showMultiAgentBoard = false }
        )
        return@AgentBoxApp
    }

    // Terminal Screen
    if (showTerminal) {
        TerminalScreen(
            commandInput = commandInput,
            onCommandInputChange = { commandInput = it },
            terminalOutput = terminalOutput,
            isExecuting = isExecuting,
            envInstalled = envInstalled,
            onExecute = {
                if (commandInput.isNotBlank()) {
                    val executingCommand = commandInput
                    isExecuting = true
                    terminalOutput += "\nroot@agentbox:~$ $executingCommand\n"
                    scope.launch {
                        try {
                            val result = toolExecutor.executeCommand(executingCommand)
                            val commandOutput = result.content.joinToString("\n") { it.text }.trimEnd()
                            terminalOutput += if (commandOutput.isNotBlank()) "$commandOutput\n" else "\n"
                            if (result.isError) {
                                terminalOutput += "[exit: non-zero]\n"
                            }
                        } catch (e: Exception) {
                            terminalOutput += "Error: ${e.message}\n"
                        } finally {
                            terminalOutput += "root@agentbox:~$ "
                            commandInput = ""
                            isExecuting = false
                        }
                    }
                }
            },
            onClear = {
                terminalOutput = ""
                commandInput = ""
            },
            onBack = { showTerminal = false }
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
                    IconButton(onClick = { showTerminal = true }) {
                        Icon(Icons.Default.Terminal, contentDescription = "Terminal")
                    }
                    IconButton(onClick = { refreshTrigger++ }) { Icon(Icons.Default.Refresh, contentDescription = null) }
                    IconButton(onClick = { showLog = !showLog }) {
                        Icon(if (showLog) Icons.Default.Folder else Icons.Default.List, contentDescription = null)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("AI Teacher Settings") },
                                onClick = { showMenu = false; showSettings = true },
                                leadingIcon = { Icon(Icons.Default.School, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Sub-Agent Model Settings") },
                                onClick = { showMenu = false; showSubAgentSettings = true },
                                leadingIcon = { Icon(Icons.Default.SmartToy, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Multi-Agent Board") },
                                onClick = { showMenu = false; showMultiAgentBoard = true },
                                leadingIcon = { Icon(Icons.Default.List, null) }
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
fun TerminalScreen(
    commandInput: String,
    onCommandInputChange: (String) -> Unit,
    terminalOutput: String,
    isExecuting: Boolean,
    envInstalled: Boolean,
    onExecute: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val terminalBg = Color(0xFF0D1117)
    val terminalText = Color(0xFF58A6FF)
    val promptColor = Color(0xFF3FB950)

    LaunchedEffect(terminalOutput) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(terminalBg)
        ) {
            // Output area
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                color = terminalBg
            ) {
                Text(
                    text = terminalOutput.ifEmpty { "root@agentbox:~$ " },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = terminalText,
                    lineHeight = 18.sp
                )
            }

            // Input area
            Surface(
                tonalElevation = 3.dp,
                color = Color(0xFF161B22),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "$",
                            color = promptColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp
                        )
                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = onCommandInputChange,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("输入 Linux 命令…", color = Color.Gray) },
                            singleLine = true,
                            enabled = !isExecuting && envInstalled,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { if (commandInput.isNotBlank() && envInstalled) onExecute() }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = promptColor,
                                unfocusedBorderColor = Color(0xFF30363D),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0D1117),
                                unfocusedContainerColor = Color(0xFF0D1117),
                                cursorColor = promptColor
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                        )

                        if (isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = promptColor,
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = onExecute,
                                enabled = commandInput.isNotBlank() && envInstalled,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        color = if (commandInput.isNotBlank() && envInstalled) promptColor else Color(0xFF30363D),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Icon(
                                    Icons.Default.KeyboardReturn,
                                    contentDescription = "Execute",
                                    tint = Color.Black
                                )
                            }
                        }
                    }

                    Text(
                        text = "Tip: 按回车发送命令，支持常见 bash 命令（ls/cd/cat/apt 等）。",
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (!envInstalled) {
                    Text(
                        "⚠️ Linux environment not installed. Please install it first.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Yellow.copy(alpha = 0.2f))
                            .padding(8.dp),
                        color = Color.Yellow,
                        style = MaterialTheme.typography.labelSmall
                    )
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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentSettingsScreen(
    manager: SubAgentModelConfigManager,
    onBack: () -> Unit
) {
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("0.3") }
    var showApiKey by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val config = manager.loadConfig()
        endpoint = config.endpoint
        apiKey = config.apiKey
        model = config.model
        temperature = config.temperature.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sub-Agent Model Settings") },
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
                placeholder = { Text("gpt-4.1-mini") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = temperature,
                onValueChange = { temperature = it; isSaved = false },
                label = { Text("Temperature") },
                placeholder = { Text("0.3") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    manager.saveConfig(
                        SubAgentModelConfig(
                            endpoint = endpoint,
                            apiKey = apiKey,
                            model = model,
                            temperature = temperature.toDoubleOrNull() ?: 0.3
                        )
                    )
                    isSaved = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = endpoint.isNotEmpty() && apiKey.isNotEmpty() && model.isNotEmpty()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Sub-Agent Configuration")
            }

            if (isSaved) {
                Text("Configuration saved!", color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider()

            Text(
                "This model is used by internal worker agents. It is separate from AI Teacher, so you can use a cheaper model for autonomous multi-agent execution.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAgentBoardScreen(
    manager: MultiAgentManager,
    runtimeManager: MultiAgentRuntimeManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<MultiAgentSession>>(emptyList()) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var runtimeSnapshots by remember { mutableStateOf<Map<String, MultiAgentRuntimeSnapshot>>(emptyMap()) }

    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                sessions = manager.listSessions()
                runtimeSnapshots = runtimeManager.listRuntimes().associateBy { it.sessionId }
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(2000)
            refresh()
        }
    }

    val selectedSession = sessions.firstOrNull { it.id == selectedSessionId }
    val selectedRuntime = selectedSessionId?.let { runtimeSnapshots[it] }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selectedSession == null) "Multi-Agent Board" else selectedSession.title)
                        Text(
                            if (selectedSession == null) "Inspect shared sessions, worker status, and runtime state" else selectedSession.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedSession != null) {
                            selectedSessionId = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading && sessions.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                errorMessage != null -> {
                    Text(
                        text = "Load failed: ${errorMessage}",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                selectedSession == null -> {
                    if (sessions.isEmpty()) {
                        Text(
                            text = "No multi-agent session yet. Let the connected AI call create_multi_agent_session first.",
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(sessions, key = { it.id }) { session ->
                                ElevatedCard(onClick = { selectedSessionId = session.id }) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(session.title, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            session.objective,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val runtime = runtimeSnapshots[session.id]
                                        Text(
                                            "Status: ${session.status} · Runtime: ${runtime?.status ?: "inactive"} · Agents: ${session.agents.size} · Updated: ${formatTimestamp(session.updatedAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            ElevatedCard {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Runtime", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "State: ${selectedRuntime?.status ?: "inactive"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (selectedRuntime != null) {
                                        Text(
                                            "Current agent: ${selectedRuntime.currentAgentName ?: "(none)"} · Loops: ${selectedRuntime.loopCount}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Last tick: ${if (selectedRuntime.lastTickAt > 0) formatTimestamp(selectedRuntime.lastTickAt) else "(never)"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (!selectedRuntime.lastError.isNullOrBlank()) {
                                            Text(
                                                "Last error: ${selectedRuntime.lastError}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    } else {
                                        Text(
                                            "This session runtime is not active in memory. Board data is still persisted.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            ElevatedCard {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Objective", style = MaterialTheme.typography.titleSmall)
                                    Text(selectedSession.objective, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Session ID: ${selectedSession.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "Created: ${formatTimestamp(selectedSession.createdAt)} · Updated: ${formatTimestamp(selectedSession.updatedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        item {
                            Text("Agents", style = MaterialTheme.typography.titleMedium)
                        }

                        if (selectedSession.agents.isEmpty()) {
                            item {
                                Text(
                                    "No agents in this session yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(selectedSession.agents, key = { it.id }) { agent ->
                                ElevatedCard {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("${agent.name} · ${agent.role}", style = MaterialTheme.typography.titleSmall)
                                        Text("Task: ${agent.task}", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Status: ${agent.status}" + (agent.progress?.let { " · ${it}%" } ?: ""),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (agent.lastMessage.isNotBlank()) {
                                            Text(agent.lastMessage, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(
                                            "Updated: ${formatTimestamp(agent.updatedAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text("Recent Timeline", style = MaterialTheme.typography.titleMedium)
                        }

                        if (selectedSession.timeline.isEmpty()) {
                            item {
                                Text(
                                    "No timeline entries yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(selectedSession.timeline.asReversed(), key = { it.id }) { entry ->
                                ElevatedCard {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            buildString {
                                                append(entry.type)
                                                entry.agentName?.let {
                                                    append(" · ")
                                                    append(it)
                                                }
                                                entry.supervisor?.let {
                                                    append(" · ")
                                                    append(it)
                                                }
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            formatTimestamp(entry.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading && sessions.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}
