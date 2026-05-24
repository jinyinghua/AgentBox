package com.shaun.agentbox.sandbox;

import java.io.IOException;

public final class NativePty {
    static {
        System.loadLibrary("agentboxpty");
    }

    private NativePty() {}

    public static native int[] createSubprocess(String cmd, String[] args, String[] env, String cwd) throws IOException;
    public static native void setWindowSize(int fd, int rows, int cols, int width, int height);
    public static native void killProcess(int pid, int signal);
    public static native boolean isProcessAlive(int pid);
    public static native int waitFor(int pid);
}
