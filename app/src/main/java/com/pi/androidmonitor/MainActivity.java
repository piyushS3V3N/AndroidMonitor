package com.pi.androidmonitor;

import android.content.Context;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DevCompanion";
    private DataListAdapter podAdapter, k8sLogAdapter, dockerAdapter, processAdapter, radarAdapter, intelAdapter;
    private TextView cpuText, memText, diskText, netIoText, statusText;
    private LineChart cpuChart, ramChart, diskChart;
    private Spinner nsSpinner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> namespaces = new ArrayList<>();
    
    // Hardware Link Objects
    private UsbManager usbManager;
    private UsbAccessory currentAccessory;
    private ParcelFileDescriptor fileDescriptor;
    private FileInputStream inputStream;
    private FileOutputStream outputStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        initViews();
        setupRecyclerViews();
        setupCharts();
        startHardwareLink(); 
    }

    private void initViews() {
        cpuText = findViewById(R.id.cpu_text);
        memText = findViewById(R.id.mem_text);
        diskText = findViewById(R.id.disk_text);
        netIoText = findViewById(R.id.net_io_text);
        statusText = findViewById(R.id.status_text);
        cpuChart = findViewById(R.id.cpu_chart);
        ramChart = findViewById(R.id.ram_chart);
        diskChart = findViewById(R.id.disk_chart);
        nsSpinner = findViewById(R.id.spinner_namespace);
    }

    private void setupCharts() {
        setupSparkline(cpuChart);
        setupSparkline(ramChart);
        setupSparkline(diskChart);
    }

    private void setupSparkline(LineChart chart) {
        if (chart == null) return;
        chart.getDescription().setEnabled(false); chart.getLegend().setEnabled(false);
        chart.getAxisLeft().setEnabled(false); chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setEnabled(false); chart.setDrawGridBackground(false);
        chart.setTouchEnabled(false); chart.setViewPortOffsets(0, 0, 0, 0);
    }

    private void setupRecyclerViews() {
        int orange = Color.parseColor("#FF8C00");
        int white = Color.parseColor("#EAEAEA");
        int red = Color.parseColor("#FF5555");
        podAdapter = new DataListAdapter(orange); podAdapter.setOnItemClickListener(this::handlePodSelection);
        initRV(R.id.rv_k8s_pods, podAdapter);
        k8sLogAdapter = new DataListAdapter(white); initRV(R.id.rv_k8s_logs, k8sLogAdapter);
        dockerAdapter = new DataListAdapter(orange); initRV(R.id.rv_docker, dockerAdapter);
        processAdapter = new DataListAdapter(white); initRV(R.id.rv_processes, processAdapter);
        radarAdapter = new DataListAdapter(red); initRV(R.id.rv_radar, radarAdapter);
        intelAdapter = new DataListAdapter(orange); initRV(R.id.rv_intel, intelAdapter);
    }

    private void initRV(int id, DataListAdapter adapter) {
        RecyclerView rv = findViewById(id);
        if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(adapter); }
    }

    private void startHardwareLink() {
        new Thread(() -> {
            while (true) {
                // Try USB Accessory Mode first (No ADB required)
                UsbAccessory[] accessories = usbManager.getAccessoryList();
                if (accessories != null && accessories.length > 0) {
                    openAccessory(accessories[0]);
                    break;
                }
                
                // Fallback to local socket (WiFi/Ethernet)
                try {
                    Socket socket = new Socket("127.0.0.1", 19090);
                    handleConnection(new BufferedReader(new InputStreamReader(socket.getInputStream())), new PrintWriter(socket.getOutputStream(), true));
                    break;
                } catch (Exception ignored) {}
                
                updateStatus("AWAITING.LINK", Color.parseColor("#444444"));
                try { Thread.sleep(3000); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void openAccessory(UsbAccessory accessory) {
        fileDescriptor = usbManager.openAccessory(accessory);
        if (fileDescriptor != null) {
            currentAccessory = accessory;
            FileDescriptor fd = fileDescriptor.getFileDescriptor();
            inputStream = new FileInputStream(fd);
            outputStream = new FileOutputStream(fd);
            handleConnection(new BufferedReader(new InputStreamReader(inputStream)), new PrintWriter(outputStream, true));
        }
    }

    private void handleConnection(BufferedReader in, PrintWriter out) {
        mainHandler.post(() -> statusText.setText("HW.LINK.OK"));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final String data = line;
                mainHandler.post(() -> handleJson(data));
            }
        } catch (Exception e) {
            Log.e(TAG, "Connection lost", e);
        }
    }

    private void handlePodSelection(String rawLine) {
        // ... (K8s interaction logic remains same as v4.2)
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
                    if (j.has("net_io")) netIoText.setText("NET: " + j.getString("net_io"));
                    updateSparkline(cpuChart, j.getJSONArray("cpu_h"));
                    updateSparkline(ramChart, j.getJSONArray("mem_h"));
                    updateSparkline(diskChart, j.getJSONArray("disk_h"));
                    break;
                case "k8s_pods": updatePods(j.getString("data")); break;
                case "docker": dockerAdapter.setData(j.getString("data").split("\n")); break;
                case "processes": processAdapter.setData(j.getString("data").split("\n")); break;
                case "sec_radar": radarAdapter.setData(j.getString("data").split("\n")); break;
                case "intel_news": 
                    NativeBridge.pushLog(j.getString("message"));
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

    private void updateSparkline(LineChart chart, JSONArray history) throws Exception {
        if (chart == null) return;
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < history.length(); i++) entries.add(new Entry(i, (float) history.getDouble(i)));
        LineDataSet set = new LineDataSet(entries, "");
        set.setColor(Color.parseColor("#FF8C00")); set.setDrawCircles(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); set.setDrawFilled(true);
        set.setFillColor(Color.parseColor("#FF8C00")); set.setFillAlpha(30);
        set.setDrawValues(false); chart.setData(new LineData(set)); chart.invalidate();
    }

    private void updatePods(String rawData) {
        String[] lines = rawData.split("\n"); podAdapter.setData(lines);
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

    private void updateStatus(String t, int col) {
        mainHandler.post(() -> { statusText.setText(t); statusText.setTextColor(col); });
    }
}
