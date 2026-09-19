package com.optionradar.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREF = "optionradar_mobile";
    private static final String KEY_URL = "server_url";
    private static final String KEY_ALERT_ENABLED = "ma60_alert_enabled";
    private static final String DEFAULT_URL = "http://100.100.100.100:19092";
    private static final int REQ_NOTIFY = 601;

    private WebView webView;
    private String serverUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(16, 21, 28));
        getWindow().setNavigationBarColor(Color.rgb(16, 21, 28));

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showLocalStatus("主機目前無法連線", "請確認 Tailscale 已連線，而且 OptionRadar 電腦端手機服務已啟動。");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        serverUrl = getPreferencesStore().getString(KEY_URL, DEFAULT_URL);
        requestNotificationPermissionIfNeeded();
        syncAlertService();
        loadDashboard();

        // Long press anywhere -> server + MA60 notification settings.
        webView.setOnLongClickListener(v -> {
            showServerSettings();
            return true;
        });
    }

    private SharedPreferences getPreferencesStore() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private void syncAlertService() {
        boolean enabled = getPreferencesStore().getBoolean(KEY_ALERT_ENABLED, true);
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

    private void loadDashboard() {
        if (!hasNetwork()) {
            showLocalStatus("沒有網路", "請確認手機網路與 Tailscale 已開啟。");
            return;
        }
        webView.loadUrl(serverUrl);
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null;
    }

    private void showLocalStatus(String title, String message) {
        String url = "file:///android_asset/status.html?title=" +
                android.net.Uri.encode(title) + "&msg=" +
                android.net.Uri.encode(message) + "&server=" +
                android.net.Uri.encode(serverUrl);
        webView.loadUrl(url);
    }

    private void showServerSettings() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(serverUrl);
        input.setHint("http://100.x.x.x:19092");
        input.setSelectAllOnFocus(true);

        final CheckBox maAlert = new CheckBox(this);
        maAlert.setText("啟用台指 1分K MA60 突破 / 跌破原生通知");
        maAlert.setChecked(getPreferencesStore().getBoolean(KEY_ALERT_ENABLED, true));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad/2, pad, 0);
        box.addView(input);
        box.addView(maAlert);

        new AlertDialog.Builder(this)
                .setTitle("OptionRadar 主機 / MA60通知")
                .setMessage("輸入電腦的 Tailscale IP 與 Port。\n例如：http://100.80.20.15:19092\n\nMA60通知採『1分K收盤確認穿越』，避免盤中來回觸碰重複通知。")
                .setView(box)
                .setPositiveButton("儲存並連線", (dialog, which) -> {
                    String v = input.getText().toString().trim();
                    if (!v.startsWith("http://") && !v.startsWith("https://")) {
                        v = "http://" + v;
                    }
                    serverUrl = v;
                    getPreferencesStore().edit()
                            .putString(KEY_URL, serverUrl)
                            .putBoolean(KEY_ALERT_ENABLED, maAlert.isChecked())
                            .apply();
                    if (maAlert.isChecked()) requestNotificationPermissionIfNeeded();
                    syncAlertService();
                    Toast.makeText(this, maAlert.isChecked() ? "已儲存，MA60通知監看已啟用" : "已儲存，MA60通知已關閉", Toast.LENGTH_SHORT).show();
                    loadDashboard();
                })
                .setNeutralButton("顯示預覽", (dialog, which) ->
                        webView.loadUrl("file:///android_asset/demo.html"))
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "未允許通知權限，MA60監看仍可運作，但手機不會跳出通知。", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
