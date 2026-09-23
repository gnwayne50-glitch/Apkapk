package com.optionradar.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String PREF = "optionradar_mobile";
    private static final String KEY_URL = "server_url";
    private static final String KEY_ALERT_ENABLED = "ma60_alert_enabled";
    private static final String KEY_KLINE_TF = "native_kline_tf";
    private static final String KEY_MA5 = "native_kline_ma5";
    private static final String KEY_MA10 = "native_kline_ma10";
    private static final String KEY_MA20 = "native_kline_ma20";
    private static final String KEY_MA60 = "native_kline_ma60";
    private static final String DEFAULT_URL = "http://100.100.100.100:19092";
    private static final int REQ_NOTIFY = 601;
    private static final long DASHBOARD_POLL_MS = 750L;
    private static final long KLINE_POLL_MS = 1000L;
    private static final long TURNOVER_POLL_MS = 5000L;

    private static final int C_BG = Color.rgb(10, 13, 18);
    private static final int C_PANEL = Color.rgb(18, 23, 32);
    private static final int C_PANEL2 = Color.rgb(23, 29, 39);
    private static final int C_LINE = Color.rgb(38, 46, 58);
    private static final int C_TEXT = Color.rgb(244, 246, 248);
    private static final int C_MUTED = Color.rgb(137, 147, 163);
    private static final int C_RED = Color.rgb(255, 93, 103);
    private static final int C_GREEN = Color.rgb(52, 211, 153);
    private static final int C_AMBER = Color.rgb(245, 185, 66);
    private static final int C_BLUE = Color.rgb(96, 165, 250);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService klineNet = Executors.newSingleThreadExecutor();
    private final ExecutorService turnoverNet = Executors.newSingleThreadExecutor();
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private final AtomicBoolean klineRequestInFlight = new AtomicBoolean(false);
    private final AtomicBoolean turnoverRequestInFlight = new AtomicBoolean(false);
    private volatile boolean polling = false;
    private int consecutiveFailures = 0;

    private String serverUrl;

    private TextView badge;
    private TextView txPrice;
    private TextView sessionText;
    private TextView updatedText;
    private TextView callScore;
    private TextView putScore;
    private TextView scoreGap;
    private TextView ma60Title;
    private TextView ma60Detail;
    private LinearLayout callRows;
    private LinearLayout putRows;
    private TextView connectionStatus;
    private TextView serverInfo;
    private TextView latencyText;
    private KlineChartView klineChart;
    private TextView klineStatus;
    private TextView klineInfo;
    private TurnoverChartView turnoverChart;
    private TextView turnoverTrend;
    private TextView turnoverNow;
    private TextView turnoverEstimate;
    private TextView turnoverDelta10;
    private TextView turnoverRatio;
    private TextView turnoverHint;
    private TextView turnoverMeta;
    private String currentKlineTf = "1";
    private boolean resetKlineViewport = true;
    private final Map<String, Button> tfButtons = new HashMap<>();
    private final Map<Integer, Button> maButtons = new HashMap<>();

    private String lastCallJson = "";
    private String lastPutJson = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 21, 28));
        getWindow().setNavigationBarColor(Color.rgb(16, 21, 28));

        serverUrl = prefs().getString(KEY_URL, DEFAULT_URL);
        currentKlineTf = prefs().getString(KEY_KLINE_TF, "1");
        if (!("1".equals(currentKlineTf) || "5".equals(currentKlineTf) || "15".equals(currentKlineTf) || "60".equals(currentKlineTf))) {
            currentKlineTf = "1";
        }
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        syncAlertService();

        if (DEFAULT_URL.equals(serverUrl)) {
            ui.postDelayed(this::showServerSettings, 350);
        }
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int side = dp(12);
        page.setPadding(side, dp(10), side, dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Header
        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("OptionRadar", 21, C_TEXT, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(44), 1f));

        badge = text("CONNECT", 11, C_AMBER, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundBg(Color.rgb(52, 42, 17), Color.rgb(116, 91, 29), 999));
        header.addView(badge, lpWrap(dp(68), dp(30)));

        Button settings = new Button(this);
        settings.setText("設定");
        settings.setTextColor(C_TEXT);
        settings.setTextSize(12);
        settings.setAllCaps(false);
        settings.setPadding(dp(6), 0, dp(6), 0);
        settings.setBackground(roundBg(C_PANEL2, C_LINE, 10));
        settings.setOnClickListener(v -> showServerSettings());
        LinearLayout.LayoutParams settingsLp = lpWrap(dp(68), dp(36));
        settingsLp.leftMargin = dp(8);
        header.addView(settings, settingsLp);
        page.addView(header);

        txPrice = text("--", 38, C_TEXT, true);
        txPrice.setPadding(0, dp(12), 0, 0);
        page.addView(txPrice);

        LinearLayout sub = hbox();
        sessionText = text("等待資料｜TX00", 12, C_MUTED, false);
        updatedText = text("更新 --:--:--", 12, C_MUTED, false);
        updatedText.setGravity(Gravity.END);
        sub.addView(sessionText, new LinearLayout.LayoutParams(0, dp(30), 1f));
        sub.addView(updatedText, new LinearLayout.LayoutParams(0, dp(30), 1f));
        page.addView(sub);

        // Score cards
        LinearLayout scores = hbox();
        scores.setPadding(0, dp(8), 0, 0);
        scores.addView(scoreCard("CALL 多方分數", true), new LinearLayout.LayoutParams(0, dp(118), 1f));
        View spacer = new View(this);
        scores.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
        scores.addView(scoreCard("PUT 空方分數", false), new LinearLayout.LayoutParams(0, dp(118), 1f));
        page.addView(scores);

        // MA60 card
        page.addView(sectionTitle("1分K MA60"));
        LinearLayout maCard = panel();
        ma60Title = text("背景監看中", 18, C_BLUE, true);
        ma60Detail = text("等待主機回傳 MA60 狀態｜收盤確認穿越才通知", 12, C_MUTED, false);
        ma60Detail.setPadding(0, dp(8), 0, 0);
        maCard.addView(ma60Title);
        maCard.addView(ma60Detail);
        page.addView(maCard);

        // Native K-line chart
        page.addView(sectionTitle("原生 K線"));
        LinearLayout kCard = panel();
        kCard.setPadding(dp(8), dp(9), dp(8), dp(10));

        LinearLayout tfRow = hbox();
        tfRow.setGravity(Gravity.CENTER_VERTICAL);
        for (String tf : new String[]{"1", "5", "15", "60"}) {
            Button b = compactButton(tf + "K");
            final String selectedTf = tf;
            b.setOnClickListener(v -> selectKlineTf(selectedTf));
            tfButtons.put(tf, b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (!"1".equals(tf)) lp.leftMargin = dp(6);
            tfRow.addView(b, lp);
        }
        kCard.addView(tfRow);

        LinearLayout maRow = hbox();
        maRow.setPadding(0, dp(7), 0, dp(5));
        maRow.setGravity(Gravity.CENTER_VERTICAL);
        boolean[] maDefault = new boolean[]{
                prefs().getBoolean(KEY_MA5, true), prefs().getBoolean(KEY_MA10, true),
                prefs().getBoolean(KEY_MA20, true), prefs().getBoolean(KEY_MA60, true)};
        int[] maPeriods = new int[]{5, 10, 20, 60};
        for (int i = 0; i < maPeriods.length; i++) {
            int period = maPeriods[i];
            Button b = compactButton("MA" + period);
            maButtons.put(period, b);
            final int pMa = period;
            final String prefKey = period == 5 ? KEY_MA5 : period == 10 ? KEY_MA10 : period == 20 ? KEY_MA20 : KEY_MA60;
            b.setSelected(maDefault[i]);
            b.setOnClickListener(v -> {
                boolean on = !b.isSelected();
                b.setSelected(on);
                prefs().edit().putBoolean(prefKey, on).apply();
                if (klineChart != null) klineChart.setMaEnabled(pMa, on);
                refreshMaButtonStyles();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            maRow.addView(b, lp);
        }
        kCard.addView(maRow);

        klineChart = new KlineChartView(this);
        klineChart.setVisibleCount(80);
        klineChart.setMaEnabled(5, maDefault[0]);
        klineChart.setMaEnabled(10, maDefault[1]);
        klineChart.setMaEnabled(20, maDefault[2]);
        klineChart.setMaEnabled(60, maDefault[3]);
        GradientDrawable chartBg = roundBg(Color.rgb(10, 15, 22), C_LINE, 12);
        klineChart.setBackground(chartBg);
        kCard.addView(klineChart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        klineStatus = text("等待 K 線資料", 11, C_MUTED, false);
        klineInfo = text("左右拖曳可查看較早 K 棒｜雙指縮放留待下一版", 10, C_MUTED, false);
        klineStatus.setPadding(dp(3), dp(7), dp(3), 0);
        klineInfo.setPadding(dp(3), dp(3), dp(3), 0);
        kCard.addView(klineStatus);
        kCard.addView(klineInfo);
        page.addView(kCard);
        refreshTfButtonStyles();
        refreshMaButtonStyles();

        // v0.5 TAIEX estimated turnover research
        page.addView(sectionTitle("加權預估量研究"));
        LinearLayout turnoverCard = panel();
        turnoverTrend = text("等待加權成交金額", 18, C_BLUE, true);
        turnoverCard.addView(turnoverTrend);

        LinearLayout tvRow1 = hbox();
        tvRow1.setPadding(0, dp(8), 0, 0);
        LinearLayout tvNowBox = miniMetric("目前成交", true);
        turnoverNow = (TextView) tvNowBox.getChildAt(1);
        LinearLayout tvEstBox = miniMetric("今日估量", true);
        turnoverEstimate = (TextView) tvEstBox.getChildAt(1);
        tvRow1.addView(tvNowBox, new LinearLayout.LayoutParams(0, dp(58), 1f));
        LinearLayout.LayoutParams tvEstLp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        tvEstLp.leftMargin = dp(7);
        tvRow1.addView(tvEstBox, tvEstLp);
        turnoverCard.addView(tvRow1);

        LinearLayout tvRow2 = hbox();
        tvRow2.setPadding(0, dp(7), 0, 0);
        LinearLayout tvDBox = miniMetric("10分估量變化", false);
        turnoverDelta10 = (TextView) tvDBox.getChildAt(1);
        LinearLayout tvRBox = miniMetric("20日量能倍數", false);
        turnoverRatio = (TextView) tvRBox.getChildAt(1);
        tvRow2.addView(tvDBox, new LinearLayout.LayoutParams(0, dp(54), 1f));
        LinearLayout.LayoutParams tvRLp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        tvRLp.leftMargin = dp(7);
        tvRow2.addView(tvRBox, tvRLp);
        turnoverCard.addView(tvRow2);

        turnoverChart = new TurnoverChartView(this);
        turnoverChart.setBackground(roundBg(Color.rgb(10, 15, 22), C_LINE, 12));
        LinearLayout.LayoutParams tvChartLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        tvChartLp.topMargin = dp(9);
        turnoverCard.addView(turnoverChart, tvChartLp);

        turnoverHint = text("藍線＝今日預估收盤成交金額｜綠線＝目前累積成交金額", 11, C_MUTED, false);
        turnoverMeta = text("等待 /api/mobile/turnover", 10, C_MUTED, false);
        turnoverHint.setPadding(0, dp(7), 0, 0);
        turnoverMeta.setPadding(0, dp(4), 0, 0);
        turnoverCard.addView(turnoverHint);
        turnoverCard.addView(turnoverMeta);
        page.addView(turnoverCard);

        // CALL Top3
        page.addView(sectionTitle("CALL Top 3"));
        callRows = panel();
        callRows.setPadding(0, 0, 0, 0);
        renderPlaceholder(callRows, true);
        page.addView(callRows);

        // PUT Top3
        page.addView(sectionTitle("PUT Top 3"));
        putRows = panel();
        putRows.setPadding(0, 0, 0, 0);
        renderPlaceholder(putRows, false);
        page.addView(putRows);

        // System status
        page.addView(sectionTitle("系統連線狀態"));
        LinearLayout status = panel();
        connectionStatus = text("● 尚未連線", 15, C_AMBER, true);
        serverInfo = text(serverUrl, 12, C_MUTED, false);
        latencyText = text("延遲：--", 12, C_MUTED, false);
        serverInfo.setPadding(0, dp(8), 0, 0);
        latencyText.setPadding(0, dp(6), 0, 0);
        status.addView(connectionStatus);
        status.addView(serverInfo);
        status.addView(latencyText);
        page.addView(status);

        TextView footer = text("OptionRadar Android v0.5 Native Turnover｜K線 + 加權估量研究", 11, C_MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, dp(6));
        page.addView(footer);

        return root;
    }

    private LinearLayout miniMetric(String title, boolean strong) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(9), dp(6), dp(9), dp(5));
        box.setBackground(roundBg(C_PANEL2, C_LINE, 10));
        TextView lab = text(title, 10, C_MUTED, false);
        TextView value = text("--", strong ? 17 : 15, C_TEXT, true);
        value.setPadding(0, dp(2), 0, 0);
        box.addView(lab);
        box.addView(value);
        return box;
    }

    private Button compactButton(String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setTextColor(C_MUTED);
        b.setBackground(roundBg(C_PANEL2, C_LINE, 9));
        return b;
    }

    private void selectKlineTf(String tf) {
        if (!("1".equals(tf) || "5".equals(tf) || "15".equals(tf) || "60".equals(tf))) return;
        if (!tf.equals(currentKlineTf)) {
            currentKlineTf = tf;
            prefs().edit().putString(KEY_KLINE_TF, tf).apply();
            resetKlineViewport = true;
            if (klineChart != null) klineChart.clearBars();
            if (klineStatus != null) setIfChanged(klineStatus, tf + "K｜載入中…");
            refreshTfButtonStyles();
        }
        requestKlineNow();
    }

    private void refreshTfButtonStyles() {
        for (Map.Entry<String, Button> e : tfButtons.entrySet()) {
            boolean on = e.getKey().equals(currentKlineTf);
            Button b = e.getValue();
            b.setTextColor(on ? C_TEXT : C_MUTED);
            b.setBackground(roundBg(on ? Color.rgb(34, 52, 78) : C_PANEL2, on ? C_BLUE : C_LINE, 9));
        }
    }

    private void refreshMaButtonStyles() {
        for (Map.Entry<Integer, Button> e : maButtons.entrySet()) {
            Button b = e.getValue();
            boolean on = b.isSelected();
            int activeColor;
            if (e.getKey() == 5) activeColor = Color.rgb(241, 200, 75);
            else if (e.getKey() == 10) activeColor = Color.rgb(85, 167, 255);
            else if (e.getKey() == 20) activeColor = Color.rgb(223, 136, 255);
            else activeColor = Color.rgb(255, 152, 82);
            b.setTextColor(on ? activeColor : C_MUTED);
            b.setBackground(roundBg(on ? Color.rgb(26, 35, 47) : C_PANEL2, on ? activeColor : C_LINE, 9));
        }
    }

    private View scoreCard(String title, boolean isCall) {
        LinearLayout card = panel();
        card.setPadding(dp(13), dp(12), dp(13), dp(10));
        TextView t = text(title, 12, C_MUTED, false);
        TextView score = text("--", 36, isCall ? C_RED : C_GREEN, true);
        TextView hint = text(isCall ? "等待即時分數" : "等待即時分數", 11, C_MUTED, false);
        hint.setPadding(0, dp(3), 0, 0);
        card.addView(t);
        card.addView(score);
        card.addView(hint);
        if (isCall) {
            callScore = score;
            scoreGap = hint;
        } else {
            putScore = score;
        }
        return card;
    }

    private TextView sectionTitle(String s) {
        TextView v = text(s, 14, C_TEXT, true);
        v.setPadding(dp(2), dp(14), 0, dp(7));
        return v;
    }

    private LinearLayout panel() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(13), dp(12), dp(13), dp(12));
        v.setBackground(roundBg(C_PANEL, C_LINE, 15));
        return v;
    }

    private LinearLayout hbox() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private GradientDrawable roundBg(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams lpWrap(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
    }

    @Override
    protected void onPause() {
        stopPolling();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        net.shutdownNow();
        klineNet.shutdownNow();
        turnoverNet.shutdownNow();
        super.onDestroy();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        ui.post(pollRunnable);
        ui.post(klinePollRunnable);
        ui.post(turnoverPollRunnable);
    }

    private void stopPolling() {
        polling = false;
        ui.removeCallbacks(pollRunnable);
        ui.removeCallbacks(klinePollRunnable);
        ui.removeCallbacks(turnoverPollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            if (requestInFlight.compareAndSet(false, true)) {
                net.execute(() -> {
                    try {
                        fetchSnapshot();
                    } finally {
                        requestInFlight.set(false);
                    }
                });
            }
            ui.postDelayed(this, DASHBOARD_POLL_MS);
        }
    };

    private final Runnable klinePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            requestKlineNow();
            ui.postDelayed(this, KLINE_POLL_MS);
        }
    };

    private final Runnable turnoverPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            requestTurnoverNow();
            ui.postDelayed(this, TURNOVER_POLL_MS);
        }
    };

    private void requestTurnoverNow() {
        if (!polling) return;
        if (turnoverRequestInFlight.compareAndSet(false, true)) {
            turnoverNet.execute(() -> {
                try {
                    fetchTurnover();
                } finally {
                    turnoverRequestInFlight.set(false);
                }
            });
        }
    }

    private void requestKlineNow() {
        if (!polling) return;
        if (klineRequestInFlight.compareAndSet(false, true)) {
            klineNet.execute(() -> {
                try {
                    fetchKlines();
                } finally {
                    klineRequestInFlight.set(false);
                }
            });
        }
    }

    private void kickPollersNow() {
        if (!polling) return;
        ui.removeCallbacks(pollRunnable);
        ui.removeCallbacks(klinePollRunnable);
        ui.removeCallbacks(turnoverPollRunnable);
        ui.post(pollRunnable);
        ui.post(klinePollRunnable);
        ui.post(turnoverPollRunnable);
    }

    private void fetchKlines() {
        if (!hasNetwork()) return;
        String base = serverUrl == null ? "" : serverUrl.trim();
        if (base.isEmpty()) return;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection conn = null;
        try {
            URL u = new URL(base + "/api/mobile/klines?tf=" + currentKlineTf + "&ts=" + System.currentTimeMillis());
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                final int httpCode = code;
                ui.post(() -> {
                    if (klineStatus != null) setIfChanged(klineStatus, currentKlineTf + "K｜K線 API HTTP " + httpCode);
                });
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject j = new JSONObject(sb.toString());
            ui.post(() -> applyKlines(j));
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ui.post(() -> {
                if (klineStatus != null) setIfChanged(klineStatus, currentKlineTf + "K｜K線暫時無法更新：" + msg);
            });
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void applyKlines(JSONObject j) {
        if (klineChart == null) return;
        int tf = j.optInt("timeframe", 1);
        if (!String.valueOf(tf).equals(currentKlineTf)) return; // stale response after timeframe switch
        JSONArray bars = j.optJSONArray("bars");
        int visible = j.optInt("visible_bars", 80);
        visible = Math.max(20, Math.min(120, visible));
        klineChart.setVisibleCount(visible);
        klineChart.setBars(bars, resetKlineViewport);
        resetKlineViewport = false;

        int count = bars == null ? 0 : bars.length();
        String session = j.optString("session", "");
        String update = j.optString("updated_at", "--");
        if (count <= 0) {
            setIfChanged(klineStatus, currentKlineTf + "K｜目前沒有 K 線資料" + (session.isEmpty() ? "" : "｜" + session));
            setIfChanged(klineInfo, "API 已連線｜休市或主機尚未累積K線時會正常顯示空資料");
            return;
        }
        JSONObject last = bars.optJSONObject(count - 1);
        String lastTime = last == null ? "--" : last.optString("display_time", "--");
        String o = last == null ? "--" : numeric(last.opt("open"));
        String h = last == null ? "--" : numeric(last.opt("high"));
        String l = last == null ? "--" : numeric(last.opt("low"));
        String c = last == null ? "--" : numeric(last.opt("close"));
        setIfChanged(klineStatus, currentKlineTf + "K｜收到 " + count + " 根｜顯示 " + visible + " 根｜更新 " + update);
        setIfChanged(klineInfo, lastTime + "｜O " + o + " H " + h + " L " + l + " C " + c + "｜左右拖曳查看較早K棒");
    }

    private String numeric(Object value) {
        if (!(value instanceof Number)) return value == null ? "--" : String.valueOf(value);
        double d = ((Number)value).doubleValue();
        if (Math.abs(d - Math.rint(d)) < 0.000001) return String.valueOf((long)Math.rint(d));
        return String.format(Locale.TAIWAN, "%.1f", d);
    }

    private void fetchTurnover() {
        if (!hasNetwork()) return;
        String base = serverUrl == null ? "" : serverUrl.trim();
        if (base.isEmpty()) return;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection conn = null;
        try {
            URL u = new URL(base + "/api/mobile/turnover?ts=" + System.currentTimeMillis());
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(1800);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                final int httpCode = code;
                ui.post(() -> {
                    if (turnoverMeta != null) setIfChanged(turnoverMeta, "加權量能 API HTTP " + httpCode);
                });
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject j = new JSONObject(sb.toString());
            ui.post(() -> applyTurnover(j));
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ui.post(() -> {
                if (turnoverMeta != null) setIfChanged(turnoverMeta, "加權量能暫時無法更新：" + msg);
            });
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void applyTurnover(JSONObject j) {
        if (turnoverChart == null) return;
        JSONObject s = j.optJSONObject("summary");
        JSONArray series = j.optJSONArray("series");
        if (s == null) {
            setIfChanged(turnoverMeta, "主機尚未提供加權估量 summary");
            return;
        }
        boolean ok = s.optBoolean("ok", false);
        if (!ok) {
            setIfChanged(turnoverTrend, "等待加權成交金額");
            setIfChanged(turnoverMeta, s.optString("source_status", "等待資料") + "｜" + s.optString("error", ""));
            turnoverChart.clearData();
            return;
        }
        double current = s.optDouble("turnover_yi", 0.0);
        double estimate = s.optDouble("estimated_final_yi", 0.0);
        double delta10 = s.optDouble("estimate_change_10m_yi", 0.0);
        double deltaPct = s.optDouble("estimate_change_10m_pct", 0.0);
        double ratio = s.optDouble("volume_ratio", 0.0);
        double avg20 = s.optDouble("avg20_final_yi", 0.0);
        String trend = s.optString("trend", "--");
        String pv = s.optString("price_volume_hint", "--");
        String confidence = s.optString("confidence", "--");
        int histDays = s.optInt("history_days", 0);
        int avgDays = s.optInt("avg20_days", 0);
        String avgSource = s.optString("avg20_source", "等待歷史資料");
        String update = s.optString("updated_at", "--");

        int trendColor = C_BLUE;
        if (trend.contains("放量") || trend.contains("上修")) trendColor = C_RED;
        else if (trend.contains("下修") || trend.contains("降溫")) trendColor = C_GREEN;
        turnoverTrend.setTextColor(trendColor);
        setIfChanged(turnoverTrend, trend + "｜" + pv);
        setIfChanged(turnoverNow, formatYi(current));
        setIfChanged(turnoverEstimate, formatYi(estimate));
        setIfChanged(turnoverDelta10, String.format(Locale.TAIWAN, "%+,.0f億  (%+.1f%%)", delta10, deltaPct));
        setIfChanged(turnoverRatio, ratio > 0 ? String.format(Locale.TAIWAN, "%.2fx", ratio) : "暖機中");
        String avgText = avg20 > 0 ? "20日參考 " + formatYi(avg20) : "本機歷史 " + histDays + "/20日";
        setIfChanged(turnoverHint, "藍線＝今日估量｜綠線＝目前成交｜" + avgText);
        setIfChanged(turnoverMeta, "更新 " + update + "｜信心 " + confidence + "｜" + avgSource + (avgDays > 0 ? " " + avgDays + "日" : ""));
        turnoverChart.setData(series, avg20);
    }

    private String formatYi(double v) {
        if (!(v > 0)) return "--";
        return v >= 100 ? String.format(Locale.TAIWAN, "%,.0f億", v) : String.format(Locale.TAIWAN, "%,.1f億", v);
    }

    private void fetchSnapshot() {
        if (!hasNetwork()) {
            onPollError("手機目前沒有可用網路");
            return;
        }
        String base = serverUrl == null ? "" : serverUrl.trim();
        if (base.isEmpty()) {
            onPollError("尚未設定主機網址");
            return;
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        long started = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(base + "/api/mobile/snapshot?ts=" + System.currentTimeMillis());
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject j = new JSONObject(sb.toString());
            long latency = Math.max(0, System.currentTimeMillis() - started);
            consecutiveFailures = 0;
            ui.post(() -> applySnapshot(j, latency));
        } catch (Throwable e) {
            onPollError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void onPollError(String msg) {
        consecutiveFailures++;
        if (consecutiveFailures < 2) return; // avoid one-off flicker
        ui.post(() -> setOffline(msg));
    }

    private void applySnapshot(JSONObject j, long latency) {
        setLive();

        Double price = firstDouble(j, "future_price", "tx_price", "price");
        if (price != null) setIfChanged(txPrice, formatPrice(price));

        String session = firstString(j, "session", "market_session");
        if (session == null || session.trim().isEmpty()) session = "LIVE";
        setIfChanged(sessionText, session + "｜TX00");

        Double bull = firstDouble(j, "bull_score", "call_score");
        Double bear = firstDouble(j, "bear_score", "put_score");
        if (bull != null) setIfChanged(callScore, formatScore(bull));
        if (bear != null) setIfChanged(putScore, formatScore(bear));
        if (bull != null && bear != null) {
            double gap = bull - bear;
            setIfChanged(scoreGap, "分數差 " + (gap >= 0 ? "+" : "") + Math.round(gap));
        }

        JSONArray calls = j.optJSONArray("call_top3");
        JSONArray puts = j.optJSONArray("put_top3");
        if (calls != null) {
            String s = calls.toString();
            if (!s.equals(lastCallJson)) {
                lastCallJson = s;
                renderTop3(callRows, calls, true);
            }
        }
        if (puts != null) {
            String s = puts.toString();
            if (!s.equals(lastPutJson)) {
                lastPutJson = s;
                renderTop3(putRows, puts, false);
            }
        }

        Double ma = firstDouble(j, "ma60", "ma60_value", "m1_ma60");
        String maState = firstString(j, "ma60_state", "m1_ma60_state");
        updateMa60(price, ma, maState);

        String apiTime = firstString(j, "server_time", "update_time", "updated_at");
        String now = new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date());
        setIfChanged(updatedText, "更新 " + now);
        setIfChanged(serverInfo, serverUrl + (apiTime == null ? "" : "｜Server " + apiTime));
        setIfChanged(latencyText, "API 延遲：約 " + latency + " ms｜原生 JSON 更新");
    }

    private void updateMa60(Double price, Double ma, String state) {
        boolean alertEnabled = prefs().getBoolean(KEY_ALERT_ENABLED, true);
        if (ma != null && ma > 0) {
            String title;
            int color;
            if (state != null && !state.trim().isEmpty()) {
                String u = state.toUpperCase(Locale.ROOT);
                if (u.contains("ABOVE") || u.contains("UP") || u.contains("上")) {
                    title = "價格在 MA60 上方";
                    color = C_RED;
                } else if (u.contains("BELOW") || u.contains("DOWN") || u.contains("下")) {
                    title = "價格在 MA60 下方";
                    color = C_GREEN;
                } else {
                    title = "MA60 狀態：" + state;
                    color = C_BLUE;
                }
            } else if (price != null) {
                title = price >= ma ? "價格在 MA60 上方" : "價格在 MA60 下方";
                color = price >= ma ? C_RED : C_GREEN;
            } else {
                title = "MA60 " + formatPrice(ma);
                color = C_BLUE;
            }
            ma60Title.setTextColor(color);
            setIfChanged(ma60Title, title);
            setIfChanged(ma60Detail, "1分K MA60=" + formatPrice(ma) + "｜收盤穿越通知：" + (alertEnabled ? "啟用" : "關閉"));
        } else {
            ma60Title.setTextColor(C_BLUE);
            setIfChanged(ma60Title, alertEnabled ? "背景 MA60 通知監看中" : "MA60 通知已關閉");
            setIfChanged(ma60Detail, "主機 snapshot 尚未提供 MA60 數值｜穿越通知仍由 /api/ma60-alert 監看");
        }
    }

    private void renderPlaceholder(LinearLayout host, boolean call) {
        host.removeAllViews();
        host.addView(topHeader());
        for (int i = 0; i < 3; i++) {
            JSONObject empty = new JSONObject();
            addOptionRow(host, empty, call, i == 2);
        }
    }

    private void renderTop3(LinearLayout host, JSONArray arr, boolean call) {
        host.removeAllViews();
        host.addView(topHeader());
        int count = Math.min(3, arr.length());
        for (int i = 0; i < count; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) o = new JSONObject();
            addOptionRow(host, o, call, i == 2 && count == 3);
        }
        for (int i = count; i < 3; i++) {
            addOptionRow(host, new JSONObject(), call, i == 2);
        }
    }

    private View topHeader() {
        LinearLayout row = optionRowBase();
        addCell(row, "商品", 1.55f, Gravity.START, C_MUTED, 10, false);
        addCell(row, "履約", .85f, Gravity.END, C_MUTED, 10, false);
        addCell(row, "Bid", .72f, Gravity.END, C_MUTED, 10, false);
        addCell(row, "Ask", .72f, Gravity.END, C_MUTED, 10, false);
        addCell(row, "分數", .65f, Gravity.END, C_MUTED, 10, false);
        return row;
    }

    private void addOptionRow(LinearLayout host, JSONObject o, boolean call, boolean last) {
        LinearLayout row = optionRowBase();
        String code = optText(o, "code");
        String strike = optText(o, "strike");
        String bid = optText(o, "bid");
        String ask = optText(o, "ask");
        String score = optText(o, "score");
        if (code.isEmpty()) code = "--";
        if (strike.isEmpty()) strike = "--";
        if (bid.isEmpty()) bid = "--";
        if (ask.isEmpty()) ask = "--";
        if (score.isEmpty()) score = "--";

        addCell(row, code, 1.55f, Gravity.START, call ? C_RED : C_GREEN, 12, true);
        addCell(row, strike, .85f, Gravity.END, C_TEXT, 12, false);
        addCell(row, bid, .72f, Gravity.END, C_RED, 12, false);
        addCell(row, ask, .72f, Gravity.END, C_GREEN, 12, false);
        addCell(row, score, .65f, Gravity.END, C_TEXT, 12, false);
        if (!last) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(0, Color.TRANSPARENT);
            row.setBackground(bg);
        }
        host.addView(row);
        if (!last) {
            View line = new View(this);
            line.setBackgroundColor(C_LINE);
            host.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
    }

    private LinearLayout optionRowBase() {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setMinimumHeight(dp(40));
        return row;
    }

    private void addCell(LinearLayout row, String s, float weight, int gravity, int color, int sp, boolean bold) {
        TextView v = text(s, sp, color, bold);
        v.setGravity(Gravity.CENTER_VERTICAL | gravity);
        v.setSingleLine(true);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
    }

    private String optText(JSONObject o, String key) {
        Object x = o.opt(key);
        if (x == null || x == JSONObject.NULL) return "";
        if (x instanceof Number) {
            double d = ((Number) x).doubleValue();
            if (Math.abs(d - Math.rint(d)) < 0.000001) return String.valueOf((long)Math.rint(d));
            return String.format(Locale.TAIWAN, "%.1f", d);
        }
        return String.valueOf(x);
    }

    private void setLive() {
        badge.setText("LIVE");
        badge.setTextColor(Color.rgb(102, 228, 166));
        badge.setBackground(roundBg(Color.rgb(17, 53, 35), Color.rgb(31, 105, 68), 999));
        connectionStatus.setTextColor(C_GREEN);
        setIfChanged(connectionStatus, "● LIVE｜Tailscale / JSON API 正常");
    }

    private void setOffline(String reason) {
        badge.setText("OFFLINE");
        badge.setTextColor(C_AMBER);
        badge.setBackground(roundBg(Color.rgb(52, 42, 17), Color.rgb(116, 91, 29), 999));
        connectionStatus.setTextColor(C_AMBER);
        setIfChanged(connectionStatus, "● OFFLINE｜" + reason);
        setIfChanged(latencyText, "背景會自動重試，不重新載入整個畫面");
    }

    private void setIfChanged(TextView v, String s) {
        if (v == null || s == null) return;
        CharSequence old = v.getText();
        if (old == null || !s.contentEquals(old)) v.setText(s);
    }

    private String formatPrice(double d) {
        if (Math.abs(d - Math.rint(d)) < 0.000001) {
            return NumberFormat.getIntegerInstance(Locale.TAIWAN).format((long)Math.rint(d));
        }
        return NumberFormat.getNumberInstance(Locale.TAIWAN).format(d);
    }

    private String formatScore(double d) {
        return String.valueOf(Math.round(d));
    }

    private Double firstDouble(JSONObject j, String... keys) {
        for (String k : keys) {
            if (!j.has(k) || j.isNull(k)) continue;
            Object x = j.opt(k);
            if (x instanceof Number) return ((Number)x).doubleValue();
            try { return Double.parseDouble(String.valueOf(x).replace(",", "")); }
            catch (Throwable ignored) { }
        }
        return null;
    }

    private String firstString(JSONObject j, String... keys) {
        for (String k : keys) {
            if (!j.has(k) || j.isNull(k)) continue;
            String s = j.optString(k, null);
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return null;
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private void syncAlertService() {
        boolean enabled = prefs().getBoolean(KEY_ALERT_ENABLED, true);
        Intent svc = new Intent(this, Ma60AlertService.class);
        if (enabled) {
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } catch (Throwable e) {
                Toast.makeText(this, "MA60通知服務啟動失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            stopService(svc);
        }
    }

    private void showServerSettings() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(serverUrl);
        input.setHint("http://100.x.x.x:19092");
        input.setSelectAllOnFocus(true);

        final CheckBox maAlert = new CheckBox(this);
        maAlert.setText("啟用台指 1分K MA60 突破 / 跌破原生通知");
        maAlert.setChecked(prefs().getBoolean(KEY_ALERT_ENABLED, true));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(6), pad, 0);
        box.addView(input);
        box.addView(maAlert);

        new AlertDialog.Builder(this)
                .setTitle("OptionRadar Native 連線設定")
                .setMessage("輸入電腦端 OptionRadar 的 Tailscale IP 與 Port。\n主畫面讀 /api/mobile/snapshot；原生K線讀 /api/mobile/klines；加權估量讀 /api/mobile/turnover。")
                .setView(box)
                .setPositiveButton("儲存並連線", (dialog, which) -> {
                    String v = input.getText().toString().trim();
                    if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
                    while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
                    serverUrl = v;
                    prefs().edit()
                            .putString(KEY_URL, serverUrl)
                            .putBoolean(KEY_ALERT_ENABLED, maAlert.isChecked())
                            .apply();
                    if (maAlert.isChecked()) requestNotificationPermissionIfNeeded();
                    syncAlertService();
                    setIfChanged(serverInfo, serverUrl);
                    consecutiveFailures = 0;
                    resetKlineViewport = true;
                    kickPollersNow();
                    Toast.makeText(this, "已儲存，開始原生 Dashboard + K線 + 加權估量連線", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY &&
                (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "未允許通知權限，原生畫面仍可使用，但 MA60 不會跳通知。", Toast.LENGTH_LONG).show();
        }
    }
}
