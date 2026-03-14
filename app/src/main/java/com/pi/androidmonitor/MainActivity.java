package com.pi.androidmonitor;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
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
        
        // Enable Full Screen Immersive Mode
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN);

        Log.i(TAG, "!!! DASHBOARD STARTING - IMMERSIVE MODE ENABLED !!!");
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
                    String[] tData = NativeBridge.getTerminal();
                    if (tData != null && terminalAdapter != null) {
                        terminalAdapter.setData(tData);
                        RecyclerView rvTerminal = findViewById(R.id.rv_terminal);
                        if (rvTerminal != null && terminalAdapter.getItemCount() > 0) {
                            rvTerminal.scrollToPosition(terminalAdapter.getItemCount() - 1);
                        }
                    }
                    break;
                case "log":
                    String logMsg = "[" + json.getString("level") + "] " + json.getString("message");
                    NativeBridge.pushLog(logMsg);
                    String[] lData = NativeBridge.getLogs();
                    if (lData != null && logAdapter != null) {
                        logAdapter.setData(lData);
                        RecyclerView rvLogs = findViewById(R.id.rv_logs);
                        if (rvLogs != null && logAdapter.getItemCount() > 0) {
                            rvLogs.scrollToPosition(logAdapter.getItemCount() - 1);
                        }
                    }
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
                case "project":
                    updateProjectTech(json.getString("tech"));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling message type: " + type, e);
        }
    }

    private void updateProjectTech(String tech) {
        mainHandler.post(() -> {
            Button btnTest = findViewById(R.id.btn_test);
            Button btnBuild = findViewById(R.id.btn_build);
            Button btnGit = findViewById(R.id.btn_git);

            if (btnTest == null || btnBuild == null || btnGit == null) {
                Log.e(TAG, "UI Buttons not found, cannot update project tech labels.");
                return;
            }

            if ("android".equals(tech)) {
                btnTest.setText("GRADLE TEST");
                btnBuild.setText("GRADLE ASSEMBLE");
                btnTest.setOnClickListener(v -> sendCommand("./gradlew test"));
                btnBuild.setOnClickListener(v -> sendCommand("./gradlew assembleDebug"));
            } else if ("nodejs".equals(tech)) {
                btnTest.setText("NPM TEST");
                btnBuild.setText("NPM BUILD");
                btnTest.setOnClickListener(v -> sendCommand("npm test"));
                btnBuild.setOnClickListener(v -> sendCommand("npm run build"));
            } else if ("maven".equals(tech)) {
                btnTest.setText("MVN TEST");
                btnBuild.setText("MVN PACKAGE");
                btnTest.setOnClickListener(v -> sendCommand("mvn test"));
                btnBuild.setOnClickListener(v -> sendCommand("mvn package"));
            }
        });
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
