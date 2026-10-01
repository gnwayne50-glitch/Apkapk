package com.optionradar.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FuturesVolumeActivity extends Activity {
    private static final String PREF="optionradar_mobile", KEY_URL="server_url";
    private static final String DEFAULT_URL="http://100.100.100.100:19092";
    private static final int BG=Color.rgb(10,13,18), PANEL=Color.rgb(18,23,32), LINE=Color.rgb(38,46,58), TEXT=Color.rgb(244,246,248), MUTED=Color.rgb(137,147,163), BLUE=Color.rgb(96,165,250), GREEN=Color.rgb(52,211,153), AMBER=Color.rgb(245,185,66);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService net=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean(false);
    private boolean polling=false;
    private TextView actual,nightEst,dayEst,totalEst,avgSame,ratio,speed,detail,meta,status;
    private FuturesVolumeChartView chart;

    @Override protected void onCreate(Bundle b){ super.onCreate(b); getWindow().setStatusBarColor(Color.rgb(16,21,28)); setContentView(buildUi()); }
    @Override protected void onResume(){ super.onResume(); polling=true; ui.post(poll); }
    @Override protected void onPause(){ polling=false; ui.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy(){ polling=false; net.shutdownNow(); super.onDestroy(); }

    private final Runnable poll=new Runnable(){ public void run(){ if(!polling)return; if(busy.compareAndSet(false,true)) net.execute(()->{ try{fetch();}finally{busy.set(false);} }); ui.postDelayed(this,5000); }};

    private void fetch(){
        HttpURLConnection c=null;
        try{
            String base=getSharedPreferences(PREF,MODE_PRIVATE).getString(KEY_URL,DEFAULT_URL).replaceAll("/+$","");
            c=(HttpURLConnection)new URL(base+"/api/mobile/futures-volume?ts="+System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(4000); c.setUseCaches(false);
            int code=c.getResponseCode(); if(code!=200) throw new Exception("HTTP "+code);
            BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream())); StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null) sb.append(line); br.close();
            JSONObject j=new JSONObject(sb.toString()); ui.post(()->apply(j));
        }catch(Exception e){ String m=e.getMessage(); ui.post(()->{status.setText("● 暫時無法更新"); status.setTextColor(AMBER); meta.setText(m==null?"連線失敗":m);}); }
        finally{ if(c!=null)c.disconnect(); }
    }

    private void apply(JSONObject j){
        JSONObject s=j.optJSONObject("summary"); JSONArray series=j.optJSONArray("series");
        if(s==null||!s.optBoolean("ok",false)){ status.setText("● 等待資料"); status.setTextColor(AMBER); meta.setText(s==null?"PC尚未提供台指期量能":s.optString("error","等待資料")); chart.clearData(); return; }
        status.setText("● 已連線"); status.setTextColor(GREEN);
        set(actual,fmt(s.optLong("actual_total",0))); set(nightEst,fmt(s.optLong("night_estimate",0))); set(dayEst,fmt(s.optLong("day_estimate",0))); set(totalEst,fmt(s.optLong("total_estimate",0)));
        set(avgSame,fmt(s.optLong("avg_same_time",0))); double r=s.optDouble("same_time_ratio",0); set(ratio,r>0?String.format(Locale.TAIWAN,"%.2fx",r):"暖機中"); double sp=s.optDouble("speed_ratio",0); set(speed,sp>0?String.format(Locale.TAIWAN,"%.2fx",sp):"--");
        String phase=s.optString("session_phase","WAIT"); String ph="NIGHT".equals(phase)?"夜盤進行中":"BREAK".equals(phase)?"夜日盤休市":"DAY".equals(phase)?"日盤進行中":"CLOSED".equals(phase)?"本交易日已收盤":"等待";
        detail.setText(ph+"｜夜盤實際 "+String.format(Locale.TAIWAN,"%,d",s.optLong("actual_night",0))+"｜日盤實際 "+String.format(Locale.TAIWAN,"%,d",s.optLong("actual_day",0))+"\n最近10分 "+String.format(Locale.TAIWAN,"%,d",s.optLong("last10_volume",0))+"口｜20日同期10分 "+String.format(Locale.TAIWAN,"%,d",s.optLong("avg_last10_volume",0))+"口");
        meta.setText(s.optString("future_code","--")+"｜"+s.optString("session_start","--")+" → 13:45｜"+s.optString("model","--")+"｜信心 "+s.optString("confidence","--")+"｜更新 "+s.optString("updated_at","--"));
        chart.setData(series);
    }
    private void set(TextView v,String s){ if(!s.contentEquals(v.getText()))v.setText(s); }
    private String fmt(long v){ return v>0?String.format(Locale.TAIWAN,"%,d 口",v):"--"; }

    private android.view.View buildUi(){
        ScrollView sc=new ScrollView(this); sc.setFillViewport(true); sc.setBackgroundColor(BG);
        LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(12),dp(10),dp(12),dp(24)); sc.addView(p,new ScrollView.LayoutParams(-1,-2));
        TextView title=t("台指期量能研究",22,TEXT,true); p.addView(title);
        TextView sub=t("交易日 15:00 → 隔日 13:45｜午夜與08:45不歸零",12,MUTED,false); sub.setPadding(0,dp(3),0,dp(8)); p.addView(sub);
        status=t("● 連線中",13,AMBER,true); p.addView(status);
        LinearLayout r1=row(); actual=addMetric(r1,"目前累積量"); totalEst=addMetric(r1,"整日預估量"); p.addView(r1);
        LinearLayout r2=row(); nightEst=addMetric(r2,"夜盤預估量"); dayEst=addMetric(r2,"日盤預估量"); p.addView(r2);
        LinearLayout r3=row(); avgSame=addMetric(r3,"近20日同期量"); ratio=addMetric(r3,"同期量比"); p.addView(r3);
        LinearLayout r4=row(); speed=addMetric(r4,"最近10分鐘量速"); TextView dummy=addMetric(r4,"模型"); dummy.setText("20日同期"); p.addView(r4);
        chart=new FuturesVolumeChartView(this); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(260)); cp.topMargin=dp(10); p.addView(chart,cp);
        detail=t("等待量能資料",12,TEXT,false); detail.setPadding(dp(2),dp(10),dp(2),0); p.addView(detail);
        meta=t("等待 /api/mobile/futures-volume",10,MUTED,false); meta.setPadding(dp(2),dp(7),dp(2),0); p.addView(meta);
        TextView foot=t("藍線＝整日預估｜綠線＝實際累積｜05:00~08:45休市｜13:45收斂",10,MUTED,false); foot.setPadding(dp(2),dp(8),dp(2),0); p.addView(foot);
        return sc;
    }
    private LinearLayout row(){ LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0,dp(8),0,0); return r; }
    private TextView addMetric(LinearLayout r,String label){ LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(10),dp(7),dp(10),dp(6)); b.setBackground(round(PANEL,LINE,11)); TextView l=t(label,10,MUTED,false), v=t("--",17,TEXT,true); b.addView(l); b.addView(v); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(62),1f); if(r.getChildCount()>0)lp.leftMargin=dp(7); r.addView(b,lp); return v; }
    private TextView t(String s,int sp,int color,boolean bold){ TextView v=new TextView(this); v.setText(s);v.setTextSize(sp);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    private GradientDrawable round(int fill,int stroke,int radius){ GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d; }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
