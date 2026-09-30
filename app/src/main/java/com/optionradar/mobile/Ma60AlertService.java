package com.optionradar.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class Ma60AlertService extends Service {

    public static final String PREF = "optionradar_mobile";
    public static final String KEY_URL = "server_url";
    public static final String KEY_ENABLED = "ma60_alert_enabled";
    public static final String KEY_LAST_EVENT = "ma60_last_event";
    public static final String DEFAULT_URL = "http://100.100.100.100:19092";

    private static final String MONITOR_CHANNEL = "optionradar_ma60_monitor";
    private static final String ALERT_CHANNEL = "optionradar_ma60_alerts";
    private static final int MONITOR_NOTIFICATION_ID = 6059;
    private static final int ALERT_NOTIFICATION_ID = 6060;
    private static final long POLL_MS = 1000L;
    private static final double MAX_EVENT_AGE_SEC = 180.0;

    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs().getBoolean(KEY_ENABLED, true)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(MONITOR_NOTIFICATION_ID, buildMonitorNotification("1分K MA60 原生監看中"));
        acquireWakeLock();
        startWorkerIfNeeded();
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void startWorkerIfNeeded() {
        if (worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                long begin = System.currentTimeMillis();
                try {
                    if (!prefs().getBoolean(KEY_ENABLED, true)) {
                        stopSelf();
                        break;
                    }
                    pollOnce();
                } catch (Throwable ignored) {
                    // Tailscale / PC 端短暫離線時持續重試。
                }
                long used = System.currentTimeMillis() - begin;
                long sleep = Math.max(100L, POLL_MS - used);
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "OptionRadar-MA60-Alert");
        worker.setDaemon(true);
        worker.start();
    }

    private void pollOnce() throws Exception {
        String base = prefs().getString(KEY_URL, DEFAULT_URL);
        if (base == null || base.trim().isEmpty()) return;
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/ma60-alert?ts=" + System.currentTimeMillis()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) return;

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject j = new JSONObject(sb.toString());
            String eventId = j.optString("event_id", "").trim();
            if (eventId.isEmpty()) return;

            String lastEvent = prefs().getString(KEY_LAST_EVENT, "");
            if (eventId.equals(lastEvent)) return;

            double age = j.optDouble("age_sec", Double.MAX_VALUE);
            if (Double.isNaN(age) || age < 0) age = Double.MAX_VALUE;
            if (age > MAX_EVENT_AGE_SEC) return;

            String direction = j.optString("direction", "");
            if (!"UP".equals(direction) && !"DOWN".equals(direction)) return;

            showCrossNotification(j);
            prefs().edit().putString(KEY_LAST_EVENT, eventId).apply();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void showCrossNotification(JSONObject j) {
        String direction = j.optString("direction", "");
        boolean up = "UP".equals(direction);
        double close = j.optDouble("close", 0.0);
        double ma60 = j.optDouble("ma60", 0.0);
        String eventTime = j.optString("event_time", "");
        String code = j.optString("future_code", "TX00");

        String title = up ? "台指 1分K 突破 MA60" : "台指 1分K 跌破 MA60";
        String body = String.format(
                Locale.TAIWAN,
                "%s｜收盤 %.1f %s MA60 %.1f%s",
                code,
                close,
                up ? ">" : "<",
                ma60,
                eventTime.isEmpty() ? "" : "｜" + eventTime
        );

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ALERT_CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ALERT_NOTIFICATION_ID, b.build());
    }

    private Notification buildMonitorNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, MONITOR_CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("OptionRadar MA60 通知")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL,
                "MA60 背景監看",
                NotificationManager.IMPORTANCE_LOW
        );
        monitor.setDescription("維持 OptionRadar 與電腦端 MA60 訊號監看");
        monitor.setSound(null, null);
        monitor.enableVibration(false);
        nm.createNotificationChannel(monitor);

        NotificationChannel alerts = new NotificationChannel(
                ALERT_CHANNEL,
                "1分K MA60 突破 / 跌破",
                NotificationManager.IMPORTANCE_HIGH
        );
        alerts.setDescription("台指1分K收盤穿越MA60時通知");
        alerts.enableVibration(true);
        alerts.setVibrationPattern(new long[]{0, 250, 120, 250});
        nm.createNotificationChannel(alerts);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OptionRadar:MA60Monitor");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
