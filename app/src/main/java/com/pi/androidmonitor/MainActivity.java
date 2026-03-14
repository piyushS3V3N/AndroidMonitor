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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DevCompanion";
    private static final String ACTION_USB_PERMISSION = "com.pi.androidmonitor.USB_PERMISSION";
    
    private DataListAdapter podAdapter, k8sLogAdapter, dockerAdapter, processAdapter, radarAdapter, intelAdapter;
    private TextView cpuText, memText, diskText, netIoText, statusText;
    private LineChart cpuChart, ramChart, diskChart;
    private Spinner nsSpinner;
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
        
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        startDiscoveryLoop();
        startHardwareLinkLoop(); 
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
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket(19091);
                socket.setBroadcast(true);
                byte[] buffer = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());
                    if (msg.contains("KAIZUKA_BEACON")) {
                        discoveredIp = packet.getAddress().getHostAddress();
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Discovery failed", e); }
        }).start();
    }

    private void startHardwareLinkLoop() {
        new Thread(() -> {
            while (true) {
                if (isLinked) { try { Thread.sleep(5000); } catch (Exception ignored) {} continue; }
                UsbAccessory[] accs = usbManager.getAccessoryList();
                if (accs != null && accs.length > 0) {
                    mainHandler.post(() -> requestUsbPermission(accs[0]));
                    try { Thread.sleep(5000); } catch (Exception ignored) {} continue;
                }
                if (discoveredIp != null) {
                    try {
                        Socket s = new Socket(discoveredIp, 19090);
                        handleConnection(new BufferedReader(new InputStreamReader(s.getInputStream())), new PrintWriter(s.getOutputStream(), true));
                    } catch (Exception ignored) {}
                }
                try {
                    Socket s = new Socket("127.0.0.1", 19090);
                    handleConnection(new BufferedReader(new InputStreamReader(s.getInputStream())), new PrintWriter(s.getOutputStream(), true));
                } catch (Exception ignored) {}
                mainHandler.post(() -> { if(statusText!=null) statusText.setText("AWAITING.LINK"); });
                try { Thread.sleep(3000); } catch (Exception ignored) {}
            }
        }).start();
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
                new Thread(() -> handleConnection(new BufferedReader(new InputStreamReader(inputStream)), new PrintWriter(outputStream, true))).start();
            }
        } catch (Exception e) { isLinked = false; }
    }

    private void closeAccessory() {
        try { if (fileDescriptor != null) fileDescriptor.close(); } catch (Exception ignored) {}
        fileDescriptor = null; outputStream = null;
    }

    private void handleConnection(BufferedReader in, PrintWriter out) {
        isLinked = true;
        networkOut = out; // Store for command dispatch
        mainHandler.post(() -> { if(statusText!=null) statusText.setText("HW.LINK.OK"); });
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
                
                NativeBridge.pushTerminal("\n[ACTION] FOCUSING POD: " + parts[1] + "\n");
                k8sLogAdapter.setData(NativeBridge.getTerminal());
            } catch (Exception e) { Log.e(TAG, "Command failed", e); }
        }
    }

    private void dispatchCommand(String cmd) {
        new Thread(() -> {
            try {
                String frame = cmd + "\n";
                // 1. Try Network Path
                if (networkOut != null) {
                    networkOut.println(cmd);
                    networkOut.flush();
                }
                // 2. Try USB Path
                if (outputStream != null) {
                    outputStream.write(frame.getBytes());
                    outputStream.flush();
                }
            } catch (Exception e) { Log.e(TAG, "Dispatch error", e); }
        }).start();
    }

    private void handleJson(String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            String type = j.optString("type", "unknown");
            if ("telemetry".equals(type)) {
                if(cpuText!=null) cpuText.setText("CPU: " + String.format("%.1f%%", j.optDouble("cpu", 0)));
                if(memText!=null) memText.setText("RAM: " + String.format("%.1fGB", j.optDouble("mem_used", 0)));
                if(diskText!=null) diskText.setText("DSK: " + String.format("%.1f%%", j.optDouble("disk_p", 0)));
                if(netIoText!=null) netIoText.setText("NET: " + j.optString("net_io", "--"));
                updateSparkline(cpuChart, j.optJSONArray("cpu_h"));
                updateSparkline(ramChart, j.optJSONArray("mem_h"));
                updateSparkline(diskChart, j.optJSONArray("disk_h"));
            } else if ("k8s_pods".equals(type)) {
                podAdapter.setData(j.optString("data", "").split("\n"));
                updateNamespaces(j.optString("data", ""));
            } else if ("docker".equals(type)) {
                dockerAdapter.setData(j.optString("data", "").split("\n"));
            } else if ("processes".equals(type)) {
                processAdapter.setData(j.optString("data", "").split("\n"));
            } else if ("sec_radar".equals(type)) {
                radarAdapter.setData(j.optString("data", "").split("\n"));
            } else if ("intel_news".equals(type)) {
                NativeBridge.pushLog(j.optString("message", ""));
                intelAdapter.setData(NativeBridge.getLogs());
            } else if ("k8s_log".equals(type)) {
                NativeBridge.pushTerminal(j.optString("message", ""));
                k8sLogAdapter.setData(NativeBridge.getTerminal());
                scroll(R.id.rv_k8s_logs, k8sLogAdapter);
            }
        } catch (Exception e) { Log.e(TAG, "Parse error", e); }
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
    protected void onDestroy() { super.onDestroy(); try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {} closeAccessory(); }
}
