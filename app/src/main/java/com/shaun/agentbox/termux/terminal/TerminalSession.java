package com.shaun.agentbox.termux.terminal;

import com.shaun.agentbox.sandbox.TerminalShellSession;

import java.nio.charset.StandardCharsets;

/**
 * Lightweight terminal session backed by the app's local proot shell.
 * It mimics the small subset of Termux TerminalSession API that TerminalView needs.
 */
public class TerminalSession extends TerminalOutput {

    private TerminalEmulator mEmulator;
    private TerminalSessionClient mClient;
    private TerminalShellSession mShellSession;
    private final Integer mTranscriptRows;
    private String mTitle;

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void setShellSession(TerminalShellSession shellSession) {
        this.mShellSession = shellSession;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        this.mClient = client;
        if (mEmulator != null) {
            mEmulator.updateTerminalSessionClient(client);
        }
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            if (mClient != null) mClient.setTerminalShellPid(this, -1);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        if (mShellSession != null) {
            mShellSession.resize(columns, rows, columns * cellWidthPixels, rows * cellHeightPixels);
        }
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public void appendOutput(byte[] data, int length) {
        if (mEmulator == null || data == null || length <= 0) return;
        mEmulator.append(data, length);
        notifyScreenUpdate();
    }

    public void appendOutput(String data) {
        if (data == null || data.isEmpty()) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        appendOutput(bytes, bytes.length);
    }

    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    public void finishIfRunning() {
        if (mShellSession != null) {
            try {
                mShellSession.write("exit\n");
            } catch (Exception ignored) {
            }
        }
    }

    public void reset() {
        if (mEmulator != null) {
            mEmulator.reset();
            notifyScreenUpdate();
        }
    }

    public boolean isRunning() {
        return mShellSession != null && mShellSession.isConnected();
    }

    public int getExitStatus() {
        return isRunning() ? 0 : -1;
    }

    public String getTitle() {
        return mTitle;
    }

    public int getPid() {
        return -1;
    }

    public String getCwd() {
        return null;
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellSession == null || !mShellSession.isConnected() || data == null || count <= 0) return;
        try {
            mShellSession.writeBytes(data, offset, count);
        } catch (Exception e) {
            if (mClient != null) mClient.logStackTraceWithMessage("TerminalSession", "Failed writing to local shell", e);
        }
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }
        StringBuilder builder = new StringBuilder();
        if (prependEscape) builder.append((char) 27);
        builder.appendCodePoint(codePoint);
        write(builder.toString());
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mTitle = newTitle;
        if (mClient != null) mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }
}
