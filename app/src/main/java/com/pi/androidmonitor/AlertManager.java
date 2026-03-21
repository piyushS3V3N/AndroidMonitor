package com.pi.androidmonitor;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class AlertManager {
    private static final String CHANNEL_ID = "dev_monitor_alerts";
    private static final String CHANNEL_NAME = "System Alerts";
    private final Context context;
    private final NotificationManager notificationManager;
    private final Map<String, Long> lastAlertTimes = new HashMap<>();
    private static final long ALERT_COOLDOWN = 60000; // 1 minute

    public AlertManager(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Critical system and cluster alerts");
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void checkTelemetry(double cpu, double ram) {
        if (cpu > 90) {
            sendAlert("High CPU Usage", String.format(Locale.getDefault(), "CPU is at %.1f%%", cpu), "cpu_alert");
        }
        if (ram > 90) {
            sendAlert("High RAM Usage", String.format(Locale.getDefault(), "RAM is at %.1f%%", ram), "ram_alert");
        }
    }

    public void checkLogForErrors(String logLine) {
        if (logLine != null && (logLine.contains("[ERROR]") || logLine.contains("Critical") || logLine.contains("Panic"))) {
            sendAlert("Critical Log Detected", logLine, "log_alert");
        }
    }

    private void sendAlert(String title, String message, String alertKey) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastAlertTimes.get(alertKey);

        if (lastTime == null || (currentTime - lastTime) > ALERT_COOLDOWN) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            notificationManager.notify(alertKey.hashCode(), builder.build());
            lastAlertTimes.put(alertKey, currentTime);
        }
    }
}
