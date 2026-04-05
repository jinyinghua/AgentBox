package com.shaun.agentbox

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.shaun.agentbox.mcp.McpService
import com.shaun.agentbox.sandbox.LinuxEnvironmentManager
import com.shaun.agentbox.sandbox.SandboxManager
import com.shaun.agentbox.ui.theme.AgentBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentBoxTheme {
                AgentBoxApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBoxApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sandboxManager = remember { SandboxManager(context) }
    val linuxManager = remember { LinuxEnvironmentManager(context) }

    var envInstalled by remember { mutableStateOf(linuxManager.isInstalled) }
    var installProgress by remember { mutableIntStateOf(0) }
    var installStatus by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }

    var isRunning by remember { mutableStateOf(McpService.isRunning) }
    var serverAddress by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            isRunning = McpService.isRunning
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
    var isExporting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AgentBox", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (currentRelativePath.isEmpty()) "/" else "/$currentRelativePath",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
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
                    IconButton(
                        onClick = {
                            if (!isExporting) {
                                isExporting = true
                                scope.launch {
                                    try {
                                        val zipFile = withContext(Dispatchers.IO) {
                                            val f = File(context.cacheDir, "export_${System.currentTimeMillis()}.zip")
                                            sandboxManager.zipWorkspace(f); f
                                        }
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Export Workspace"))
                                    } finally { isExporting = false }
                                }
                            }
                        }
                    ) { if (isExporting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Share, contentDescription = null) }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (!envInstalled) {
                        LinearProgressIndicator(
                            progress = { installProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                                                linuxManager.install { p, s ->
                                                    installProgress = p; installStatus = s
                                                }
                                                envInstalled = linuxManager.isInstalled
                                                Toast.makeText(context, "✅ Linux Env Installed!", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                installStatus = "Error: ${e.message}"
                                                android.util.Log.e("AgentBox", "Install failed", e)
                                                Toast.makeText(context, "❌ Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isInstalling = false
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Install", style = MaterialTheme.typography.labelSmall)
                                }
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
                        FilledTonalButton(onClick = {
                            val intent = Intent(context, McpService::class.java)
                            if (isRunning) context.stopService(intent) else context.startForegroundService(intent)
                        }, enabled = envInstalled) {
                            Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Text(if (isRunning) "Stop" else "Start")
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
                        FileListItem(file = file, onClick = { if (file.isDirectory) currentRelativePath = if (currentRelativePath.isEmpty()) file.name else "$currentRelativePath/${file.name}" })
                    }
                }
            }
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
                contentDescription = null, tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else Color.Gray
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
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}
