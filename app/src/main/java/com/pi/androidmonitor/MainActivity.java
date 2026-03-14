package com.pi.androidmonitor;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DevCompanion";
    private DataListAdapter podAdapter, k8sLogAdapter, dockerAdapter, processAdapter, radarAdapter, intelAdapter;
    private TextView cpuText, memText, diskText, statusText;
    private Spinner nsSpinner;
    private Socket socket;
    private PrintWriter out;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> namespaces = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        initViews();
        setupRecyclerViews();
        startConnection();
    }

    private void initViews() {
        cpuText = findViewById(R.id.cpu_text);
        memText = findViewById(R.id.mem_text);
        diskText = findViewById(R.id.disk_text);
        statusText = findViewById(R.id.status_text);
        nsSpinner = findViewById(R.id.spinner_namespace);
    }

    private void setupRecyclerViews() {
        int orange = Color.parseColor("#FF8C00");
        int white = Color.parseColor("#EAEAEA");
        int red = Color.parseColor("#FF5555");

        podAdapter = new DataListAdapter(orange); 
        podAdapter.setOnItemClickListener(this::handlePodSelection);
        initRV(R.id.rv_k8s_pods, podAdapter);

        k8sLogAdapter = new DataListAdapter(white); initRV(R.id.rv_k8s_logs, k8sLogAdapter);
        dockerAdapter = new DataListAdapter(orange); initRV(R.id.rv_docker, dockerAdapter);
        processAdapter = new DataListAdapter(white); initRV(R.id.rv_processes, processAdapter);
        radarAdapter = new DataListAdapter(red); initRV(R.id.rv_radar, radarAdapter);
        intelAdapter = new DataListAdapter(orange); initRV(R.id.rv_intel, intelAdapter);
    }

    private void handlePodSelection(String rawLine) {
        try {
            String[] parts = rawLine.trim().split("\\s+");
            if (parts.length >= 2) {
                JSONObject j = new JSONObject();
                j.put("type", "select_pod");
                j.put("namespace", parts[0]);
                j.put("pod", parts[1]);
                if (out != null) new Thread(() -> out.println(j.toString())).start();
                NativeBridge.pushTerminal("\n--- FOCUS: " + parts[1] + " ---\n");
                k8sLogAdapter.setData(NativeBridge.getTerminal());
            }
        } catch (Exception ignored) {}
    }

    private void initRV(int id, DataListAdapter adapter) {
        RecyclerView rv = findViewById(id);
        if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(adapter); }
    }

    private void startConnection() {
        new Thread(() -> {
            while (true) {
                try {
                    socket = new Socket("127.0.0.1", 19090);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    mainHandler.post(() -> statusText.setText("CONNECTED"));
                    String line;
                    while ((line = in.readLine()) != null) {
                        final String data = line;
                        mainHandler.post(() -> handleJson(data));
                    }
                } catch (Exception e) {
                    mainHandler.post(() -> statusText.setText("OFFLINE"));
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void handleJson(String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            String type = j.getString("type");
            switch (type) {
                case "telemetry":
                    cpuText.setText("CPU: " + String.format("%.1f%%", j.getDouble("cpu")));
                    memText.setText("RAM: " + String.format("%.1fGB", j.getDouble("mem_used")));
                    diskText.setText("DSK: " + String.format("%.1f%%", j.getDouble("disk_p")));
                    break;
                case "k8s_pods": updatePods(j.getString("data")); break;
                case "docker": dockerAdapter.setData(j.getString("data").split("\n")); break;
                case "processes": processAdapter.setData(j.getString("data").split("\n")); break;
                case "sec_radar": radarAdapter.setData(j.getString("data").split("\n")); break;
                case "intel_news": 
                    NativeBridge.pushLog(j.getString("message")); // Reuse log buffer for intel
                    intelAdapter.setData(NativeBridge.getLogs());
                    break;
                case "k8s_log":
                    NativeBridge.pushTerminal(j.getString("message")); 
                    k8sLogAdapter.setData(NativeBridge.getTerminal());
                    scroll(R.id.rv_k8s_logs, k8sLogAdapter);
                    break;
            }
        } catch (Exception ignored) {}
    }

    private void updatePods(String rawData) {
        String[] lines = rawData.split("\n");
        podAdapter.setData(lines);
        for (String line : lines) {
            String ns = line.trim().split("\\s+")[0];
            if (!namespaces.contains(ns)) {
                namespaces.add(ns);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, namespaces);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                nsSpinner.setAdapter(adapter);
            }
        }
    }

    private void scroll(int id, DataListAdapter adapter) {
        RecyclerView rv = findViewById(id);
        if (rv != null && adapter.getItemCount() > 0) rv.scrollToPosition(adapter.getItemCount() - 1);
    }
}
