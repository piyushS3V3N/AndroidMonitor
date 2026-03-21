package com.pi.androidmonitor;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DevCompanion";
    private static final String ACTION_USB_PERMISSION = "com.pi.androidmonitor.USB_PERMISSION";
    
    private enum ConnectionMode { AUTO, NETWORK, USB, ADB }
    private ConnectionMode currentMode = ConnectionMode.AUTO;
    
    private DataListAdapter podAdapter, k8sLogAdapter, dockerAdapter, processAdapter, radarAdapter, intelAdapter;
    private TextView cpuText, memText, diskText, netIoText, statusText;
    private LineChart cpuChart, ramChart, diskChart;
    private Spinner nsSpinner;
    private ImageButton btnSettings;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> namespaces = new ArrayList<>();
    
    // Connectivity
    private UsbManager usbManager;
    private ParcelFileDescriptor fileDescriptor;
    private FileInputStream inputStream;
    private FileOutputStream outputStream;
    private PrintWriter networkOut;
    private boolean isLinked = false;
    private String discoveredIp = null;
    private String manualIp = null;
    
    // Professional thread management
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(4);
    
    // Alert System
    private AlertManager alertManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler.post(this::setupImmersiveMode);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        alertManager = new AlertManager(this);
        
        initViews();
        setupRecyclerViews();
        setupCharts();
        
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        startDiscoveryLoop();
        startHardwareLinkLoop(); 
    }

    private void setupImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decorView = getWindow().getDecorView();
                if (decorView != null) {
                    decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Immersive mode setup failed", e);
        }
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
        btnSettings = findViewById(R.id.btn_settings);

        if (statusText != null) {
            statusText.setOnClickListener(v -> showNetworkConfigDialog());
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showNetworkConfigDialog());
        }
    }

    private void setupCharts() {
        setupSparkline(cpuChart); setupSparkline(ramChart); setupSparkline(diskChart);
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

    private void startDiscoveryLoop() {
        networkExecutor.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket(19091)) {
                socket.setBroadcast(true);
                byte[] buffer = new byte[1024];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());
                    if (msg.contains("KAIZUKA_BEACON")) {
                        discoveredIp = packet.getAddress().getHostAddress();
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Discovery failed", e); }
        });
    }

    private void startHardwareLinkLoop() {
        networkExecutor.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (isLinked) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                    continue;
                }
                
                // 1. Handle USB Mode
                if (currentMode == ConnectionMode.AUTO || currentMode == ConnectionMode.USB) {
                    UsbAccessory[] accs = usbManager.getAccessoryList();
                    if (accs != null && accs.length > 0) {
                        mainHandler.post(() -> requestUsbPermission(accs[0]));
                        try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                        continue;
                    }
                }

                // 2. Handle Network/ADB Modes
                if (currentMode != ConnectionMode.USB) {
                    List<String> targets = new ArrayList<>();
                    if (currentMode == ConnectionMode.ADB) {
                        targets.add("127.0.0.1"); // Forwarded via 'adb reverse tcp:19090 tcp:19090'
                    } else if (currentMode == ConnectionMode.NETWORK) {
                        if (manualIp != null) targets.add(manualIp);
                        if (discoveredIp != null) targets.add(discoveredIp);
                    } else if (currentMode == ConnectionMode.AUTO) {
                        if (manualIp != null) targets.add(manualIp);
                        if (discoveredIp != null) targets.add(discoveredIp);
                        targets.add("127.0.0.1");
                    }

                    for (String target : targets) {
                        if (target == null) continue;
                        try (Socket s = new Socket(target, 19090)) {
                            handleConnection(new BufferedReader(new InputStreamReader(s.getInputStream())), 
                                           new PrintWriter(s.getOutputStream(), true));
                            break;
                        } catch (Exception ignored) {}
                    }
                }
                
                mainHandler.post(() -> { if(statusText!=null) statusText.setText(R.string.status_awaiting_link); });
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        });
    }

    private void showNetworkConfigDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_network_config, null);
        EditText editIp = view.findViewById(R.id.edit_ip);
        Spinner modeSpinner = view.findViewById(R.id.spinner_conn_mode);
        View ipLayout = view.findViewById(R.id.ip_input_layout);
        View netSubtext = view.findViewById(R.id.tv_net_subtext);

        if (manualIp != null) editIp.setText(manualIp);

        String[] modes = {
            getString(R.string.mode_auto),
            getString(R.string.mode_network),
            getString(R.string.mode_usb),
            getString(R.string.mode_adb)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);
        modeSpinner.setSelection(currentMode.ordinal());

        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean isNet = (position == ConnectionMode.NETWORK.ordinal() || position == ConnectionMode.AUTO.ordinal());
                ipLayout.setVisibility(isNet ? View.VISIBLE : View.GONE);
                netSubtext.setVisibility(isNet ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setView(view)
                .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                    currentMode = ConnectionMode.values()[modeSpinner.getSelectedItemPosition()];
                    String ip = editIp.getText().toString().trim();
                    manualIp = ip.isEmpty() ? null : ip;
                    isLinked = false; // Force reconnect with new settings
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void requestUsbPermission(UsbAccessory accessory) {
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(accessory, pi);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbAccessory acc = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && acc != null) openAccessory(acc);
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())) {
                isLinked = false; closeAccessory();
            }
        }
    };

    private void openAccessory(UsbAccessory acc) {
        try {
            fileDescriptor = usbManager.openAccessory(acc);
            if (fileDescriptor != null) {
                FileDescriptor fd = fileDescriptor.getFileDescriptor();
                inputStream = new FileInputStream(fd);
                outputStream = new FileOutputStream(fd);
                networkExecutor.execute(() -> handleConnection(new BufferedReader(new InputStreamReader(inputStream)), new PrintWriter(outputStream, true)));
            }
        } catch (Exception e) { isLinked = false; }
    }

    private void closeAccessory() {
        try { if (fileDescriptor != null) fileDescriptor.close(); } catch (Exception ignored) {}
        fileDescriptor = null; outputStream = null;
    }

    private void handleConnection(BufferedReader in, PrintWriter out) {
        isLinked = true;
        networkOut = out;
        mainHandler.post(() -> { if(statusText!=null) statusText.setText(R.string.status_link_ok); });
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final String data = line;
                mainHandler.post(() -> handleJson(data));
            }
        } catch (Exception e) { Log.e(TAG, "Connection lost", e); }
        isLinked = false;
        networkOut = null;
    }

    private void handlePodSelection(String raw) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length >= 2) {
            try {
                JSONObject j = new JSONObject();
                j.put("type", "select_pod");
                j.put("namespace", parts[0]);
                j.put("pod", parts[1]);
                dispatchCommand(j.toString());
                
                String actionMsg = getString(R.string.action_focus_pod, parts[1]);
                NativeBridge.pushTerminal(actionMsg);
                k8sLogAdapter.setData(NativeBridge.getTerminal());
            } catch (Exception e) { Log.e(TAG, "Command failed", e); }
        }
    }

    private void dispatchCommand(String cmd) {
        networkExecutor.execute(() -> {
            try {
                String frame = cmd + "\n";
                if (networkOut != null) {
                    networkOut.println(cmd);
                    networkOut.flush();
                }
                if (outputStream != null) {
                    outputStream.write(frame.getBytes());
                    outputStream.flush();
                }
            } catch (Exception e) { Log.e(TAG, "Dispatch error", e); }
        });
    }

    private void handleJson(String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            String type = j.optString("type", "unknown");
            switch (type) {
                case "telemetry":
                    updateTelemetry(j);
                    break;
                case "k8s_pods":
                    podAdapter.setData(j.optString("data", "").split("\n"));
                    updateNamespaces(j.optString("data", ""));
                    break;
                case "docker":
                    dockerAdapter.setData(j.optString("data", "").split("\n"));
                    break;
                case "processes":
                    processAdapter.setData(j.optString("data", "").split("\n"));
                    break;
                case "sec_radar":
                    radarAdapter.setData(j.optString("data", "").split("\n"));
                    break;
                case "intel_news":
                    String intelMsg = j.optString("message", "");
                    alertManager.checkLogForErrors(intelMsg);
                    NativeBridge.pushLog(intelMsg);
                    intelAdapter.setData(NativeBridge.getLogs());
                    break;
                case "k8s_log":
                    String logMsg = j.optString("message", "");
                    alertManager.checkLogForErrors(logMsg);
                    NativeBridge.pushTerminal(logMsg);
                    k8sLogAdapter.setData(NativeBridge.getTerminal());
                    scroll(R.id.rv_k8s_logs, k8sLogAdapter);
                    break;
            }
        } catch (Exception e) { Log.e(TAG, "Parse error", e); }
    }

    private void updateTelemetry(JSONObject j) throws Exception {
        double cpu = j.optDouble("cpu", 0);
        double ram = j.optDouble("mem_used", 0);
        
        alertManager.checkTelemetry(cpu, ram);
        
        if(cpuText!=null) cpuText.setText(getString(R.string.cpu_format, cpu));
        if(memText!=null) memText.setText(getString(R.string.ram_format, ram));
        if(diskText!=null) diskText.setText(getString(R.string.disk_format, j.optDouble("disk_p", 0)));
        if(netIoText!=null) netIoText.setText(getString(R.string.net_format, j.optString("net_io", "--")));
        updateSparkline(cpuChart, j.optJSONArray("cpu_h"));
        updateSparkline(ramChart, j.optJSONArray("mem_h"));
        updateSparkline(diskChart, j.optJSONArray("disk_h"));
    }

    private void updateNamespaces(String data) {
        String[] lines = data.split("\n");
        boolean changed = false;
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length > 0 && !parts[0].isEmpty() && !namespaces.contains(parts[0])) {
                namespaces.add(parts[0]); changed = true;
            }
        }
        if (changed && nsSpinner != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, namespaces);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            nsSpinner.setAdapter(adapter);
        }
    }

    private void updateSparkline(LineChart chart, JSONArray h) throws Exception {
        if (chart == null || h == null) return;
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < h.length(); i++) entries.add(new Entry(i, (float) h.getDouble(i)));
        LineDataSet set = new LineDataSet(entries, "");
        set.setColor(Color.parseColor("#FF8C00")); set.setDrawCircles(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); set.setDrawFilled(true);
        set.setFillColor(Color.parseColor("#FF8C00")); set.setFillAlpha(30);
        set.setDrawValues(false); chart.setData(new LineData(set)); chart.invalidate();
    }

    private void scroll(int id, DataListAdapter adapter) {
        RecyclerView rv = findViewById(id);
        if (rv != null && adapter.getItemCount() > 0) rv.scrollToPosition(adapter.getItemCount() - 1);
    }

    @Override
    protected void onDestroy() { 
        super.onDestroy(); 
        networkExecutor.shutdownNow();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {} 
        closeAccessory(); 
    }
}
