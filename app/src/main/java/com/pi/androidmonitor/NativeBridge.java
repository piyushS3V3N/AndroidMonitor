package com.pi.androidmonitor;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class NativeBridge {
    private static final List<String> logs = new ArrayList<>();
    private static final List<String> terminal = new ArrayList<>();
    private static final int MAX_LINES = 100;

    static {
        try {
            System.loadLibrary("androidmonitor");
        } catch (UnsatisfiedLinkError e) {
            Log.e("NativeBridge", "Native library fallback");
        }
    }

    public static void pushLog(String log) {
        synchronized (logs) {
            logs.add(log);
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
        synchronized (terminal) {
            currentTerminalLine.append(data);
            if (data.contains("\n")) {
                String[] lines = currentTerminalLine.toString().split("\n");
                for (int i = 0; i < lines.length - 1; i++) {
                    terminal.add(lines[i]);
                }
                currentTerminalLine = new StringBuilder(lines[lines.length-1]);
                if (data.endsWith("\n")) {
                    terminal.add(currentTerminalLine.toString());
                    currentTerminalLine = new StringBuilder();
                }
            }
            while (terminal.size() > MAX_LINES) terminal.remove(0);
        }
    }

    public static String[] getTerminal() {
        synchronized (terminal) {
            String[] result = terminal.toArray(new String[0]);
            // If there's an active partial line, show it too
            if (currentTerminalLine.length() > 0) {
                String[] finalResult = new String[result.length + 1];
                System.arraycopy(result, 0, finalResult, 0, result.length);
                finalResult[result.length] = currentTerminalLine.toString();
                return finalResult;
            }
            return result;
        }
    }
}
