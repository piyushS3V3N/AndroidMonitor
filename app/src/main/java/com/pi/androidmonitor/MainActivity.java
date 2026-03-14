package com.pi.androidmonitor;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DevCompanion";
    private DataListAdapter logAdapter;
    private DataListAdapter terminalAdapter;
    private DataListAdapter processAdapter;
    private TextView statusText;
    private TextView cpuText, memText, gitText;
    private Socket socket;
    private PrintWriter out;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "!!! DASHBOARD STARTING - VERIFYING CONNECTIVITY !!!");
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        cpuText = findViewById(R.id.cpu_text);
        memText = findViewById(R.id.mem_text);
        gitText = findViewById(R.id.git_text);
        
        RecyclerView rvLogs = findViewById(R.id.rv_logs);
        logAdapter = new DataListAdapter(Color.parseColor("#03DAC6"));
        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        rvLogs.setAdapter(logAdapter);

        RecyclerView rvTerminal = findViewById(R.id.rv_terminal);
        terminalAdapter = new DataListAdapter(Color.parseColor("#BB86FC"));
        rvTerminal.setLayoutManager(new LinearLayoutManager(this));
        rvTerminal.setAdapter(terminalAdapter);

        RecyclerView rvProcesses = findViewById(R.id.rv_processes);
        processAdapter = new DataListAdapter(Color.parseColor("#03DAC5"));
        rvProcesses.setLayoutManager(new LinearLayoutManager(this));
        rvProcesses.setAdapter(processAdapter);

        setupButtons();
        startConnection();
    }

    private void setupButtons() {
        findViewById(R.id.btn_test).setOnClickListener(v -> sendCommand("npm test"));
        findViewById(R.id.btn_git).setOnClickListener(v -> sendCommand("git fetch"));
        findViewById(R.id.btn_build).setOnClickListener(v -> sendCommand("./gradlew assembleDebug"));
    }

    private void startConnection() {
        new Thread(() -> {
            // Initial delay to let Android system stabilize (Avoids SIGTRAP during early boot)
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            
            while (true) {
                Socket tempSocket = null;
                try {
                    Log.i(TAG, "Attempting connection to Laptop...");
                    tempSocket = new Socket("127.0.0.1", 19090);
                    socket = tempSocket;
                    out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    updateStatus("ONLINE", Color.parseColor("#03DAC6"));
                    Log.i(TAG, "!!! CONNECTED TO LAPTOP AGENT !!!");

                    String line;
                    while ((line = in.readLine()) != null) {
                        try {
                            JSONObject json = new JSONObject(line);
                            String type = json.getString("type");
                            
                            mainHandler.post(() -> handleJsonMessage(type, json));
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing JSON: " + line, e);
                        }
                    }
                } catch (Exception e) {
                    updateStatus("OFFLINE", Color.parseColor("#CF6679"));
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private void handleJsonMessage(String type, JSONObject json) {
        try {
            switch (type) {
                case "terminal":
                    NativeBridge.pushTerminal(json.getString("data"));
                    terminalAdapter.setData(NativeBridge.getTerminal());
                    RecyclerView rvTerminal = findViewById(R.id.rv_terminal);
                    rvTerminal.scrollToPosition(terminalAdapter.getItemCount() - 1);
                    break;
                case "log":
                    String logMsg = "[" + json.getString("level") + "] " + json.getString("message");
                    NativeBridge.pushLog(logMsg);
                    logAdapter.setData(NativeBridge.getLogs());
                    RecyclerView rvLogs = findViewById(R.id.rv_logs);
                    rvLogs.scrollToPosition(logAdapter.getItemCount() - 1);
                    break;
                case "status":
                    cpuText.setText("CPU: " + json.getString("cpu"));
                    memText.setText("MEM: " + json.getString("memory"));
                    break;
                case "git":
                    gitText.setText("GIT: " + json.getString("branch") + " (" + json.getString("status") + ")");
                    break;
                case "processes":
                    String procData = json.getString("data");
                    processAdapter.setData(procData.split("\n"));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling message type: " + type, e);
        }
    }

    private void updateStatus(String text, int color) {
        mainHandler.post(() -> {
            statusText.setText(text);
            statusText.setTextColor(color);
        });
    }

    private void sendCommand(String cmd) {
        if (out != null) {
            new Thread(() -> {
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "command");
                    json.put("command", cmd);
                    out.println(json.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending command", e);
                }
            }).start();
        }
    }
}
