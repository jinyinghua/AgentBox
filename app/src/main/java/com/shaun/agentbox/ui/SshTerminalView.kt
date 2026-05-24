package com.shaun.agentbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.shaun.agentbox.sandbox.TerminalShellSession
import com.shaun.agentbox.termux.terminal.TerminalSession
import com.shaun.agentbox.termux.terminal.TerminalSessionClient
import com.shaun.agentbox.termux.view.TerminalView
import com.shaun.agentbox.termux.view.TerminalViewClient

@Composable
fun SshTerminalView(
    shellSession: TerminalShellSession,
    modifier: Modifier = Modifier,
    onTitleChange: (String) -> Unit = {},
    onStatusChange: (String) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val terminalClient = remember(shellSession) {
        AgentBoxTerminalSessionClient(context, clipboard, onTitleChange, onStatusChange, onError)
    }
    val terminalSession = remember(shellSession) {
        TerminalSession(5000, terminalClient).also {
            it.setShellSession(shellSession)
            terminalClient.bindSession(it)
        }
    }

    DisposableEffect(shellSession) {
        terminalSession.setShellSession(shellSession)
        terminalClient.bindSession(terminalSession)
        onDispose {
            terminalClient.unbind()
        }
    }

    LaunchedEffect(shellSession, terminalSession) {
        try {
            terminalClient.start(shellSession, terminalSession)
        } catch (e: Exception) {
            onError(e.message ?: "Terminal session failed")
            onStatusChange("Shell disconnected")
        }
    }

    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(terminalClient)
                attachSession(terminalSession)
                setKeepScreenOn(true)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setOnClickListener { showSoftKeyboard() }
                setOnTouchListener { view, _ ->
                    (view as? TerminalView)?.showSoftKeyboard()
                    false
                }
                post { showSoftKeyboard() }
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.setTerminalViewClient(terminalClient)
            view.attachSession(terminalSession)
            view.requestFocus()
        }
    )
}

private class AgentBoxTerminalSessionClient(
    private val context: Context,
    private val clipboard: ClipboardManager,
    private val onTitleChange: (String) -> Unit,
    private val onStatusChange: (String) -> Unit,
    private val onError: (String) -> Unit
) : TerminalSessionClient, TerminalViewClient {

    @Volatile
    private var started = false

    fun bindSession(session: TerminalSession) {
        session.updateTerminalSessionClient(this)
    }

    fun unbind() {
        started = false
    }

    suspend fun start(shellSession: TerminalShellSession, session: TerminalSession) {
        if (started) return
        started = true
        onStatusChange("Connected to local proot shell")
        session.setShellSession(shellSession)
        try {
            session.write("export HOME=/root USER=root LOGNAME=root TERM=xterm-256color\n")
            session.write("cd /workspace\n")
            session.write("clear\n")
        } catch (e: Exception) {
            onError(e.message ?: "Terminal initialization failed")
            onStatusChange("Shell disconnected")
            return
        }
        while (started && shellSession.isConnected()) {
            val chunk = try {
                shellSession.readAvailable()
            } catch (e: Exception) {
                onError(e.message ?: "Terminal read failed")
                onStatusChange("Shell disconnected")
                break
            }
            if (chunk.isNotEmpty()) {
                session.appendOutput(chunk)
            }
            kotlinx.coroutines.delay(16)
        }
        onStatusChange("Shell disconnected")
    }

    override fun onTextChanged(changedSession: TerminalSession) = Unit

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChange(changedSession.title ?: "Terminal")
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onStatusChange("Shell finished")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            session?.write(text)
        }
    }

    override fun onBell(session: TerminalSession) {
        onStatusChange("Bell")
    }

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int = 0

    override fun onScale(scale: Float): Float = scale.coerceIn(0.7f, 2.2f)

    override fun onSingleTapUp(e: MotionEvent) = Unit

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        onError("$tag: $message")
    }

    override fun logWarn(tag: String, message: String) {
        onStatusChange("$tag: $message")
    }

    override fun logInfo(tag: String, message: String) = Unit

    override fun logDebug(tag: String, message: String) = Unit

    override fun logVerbose(tag: String, message: String) = Unit

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        onError("$tag: $message: ${e.message}")
    }

    override fun logStackTrace(tag: String, e: Exception) {
        onError("$tag: ${e.message}")
    }
}
