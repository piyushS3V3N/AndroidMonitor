package com.pi.androidmonitor;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class NativeBridge {
    private static final List<String> logs = new ArrayList<>();
    private static final List<String> terminal = new ArrayList<>();
    private static final int MAX_LINES = 500;

    static {
        try {
            System.loadLibrary("androidmonitor");
        } catch (UnsatisfiedLinkError e) {
            Log.e("NativeBridge", "Native library fallback");
        }
    }

    public static void pushLog(String log) {
        if (log == null) return;
        synchronized (logs) {
            logs.add(log.trim());
            if (logs.size() > MAX_LINES) logs.remove(0);
        }
    }

    public static String[] getLogs() {
        synchronized (logs) {
            return logs.toArray(new String[0]);
        }
    }

    private static StringBuilder currentTerminalLine = new StringBuilder();

    public static void pushTerminal(String data) {
        if (data == null) return;
        synchronized (terminal) {
            for (int i = 0; i < data.length(); i++) {
                char c = data.charAt(i);
                if (c == '\r') {
                    currentTerminalLine = new StringBuilder();
                } else if (c == '\n') {
                    if (currentTerminalLine.length() > 0) {
                        terminal.add(currentTerminalLine.toString());
                        currentTerminalLine = new StringBuilder();
                    }
                } else if (c == '\b' || c == 127) {
                    if (currentTerminalLine.length() > 0) {
                        currentTerminalLine.setLength(currentTerminalLine.length() - 1);
                    }
                } else {
                    // Preserving ALL characters (including ESC) for the UI parser
                    currentTerminalLine.append(c);
                }
            }
            while (terminal.size() > MAX_LINES) terminal.remove(0);
        }
    }

    public static String[] getTerminal() {
        synchronized (terminal) {
            String[] result = terminal.toArray(new String[0]);
            String partial = currentTerminalLine.toString();
            String[] finalResult = new String[result.length + 1];
            System.arraycopy(result, 0, finalResult, 0, result.length);
            finalResult[result.length] = partial;
            return finalResult;
        }
    }
}
