package com.optionradar.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FuturesVolumeChartView extends View {
    private static class P {
        final float minute, actual, estimate;
        P(float m, float a, float e) { minute=m; actual=a; estimate=e; }
    }
    private final List<P> pts = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bg = Color.rgb(10,15,22);
    private final int grid = Color.rgb(36,48,63);
    private final int muted = Color.rgb(132,145,163);
    private final int green = Color.rgb(82,210,115);
    private final int blue = Color.rgb(87,167,255);

    public FuturesVolumeChartView(Context c) { super(c); init(); }
    public FuturesVolumeChartView(Context c, AttributeSet a) { super(c,a); init(); }
    private void init(){
        setBackgroundColor(bg);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void clearData(){ synchronized(pts){ pts.clear(); } invalidate(); }

    public void setData(JSONArray arr){
        List<P> n = new ArrayList<>();
        if(arr!=null){
            for(int i=0;i<arr.length();i++){
                JSONObject o=arr.optJSONObject(i); if(o==null) continue;
                float m=(float)o.optDouble("minute",0);
                float a=(float)o.optDouble("actual",0);
                float e=(float)o.optDouble("estimated_total",0);
                if(m>=0 && m<=1365) n.add(new P(m,a,e));
            }
        }
        synchronized(pts){ pts.clear(); pts.addAll(n); }
        invalidate();
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        int w=getWidth(), h=getHeight();
        if(w<80||h<80) return;
        float l=62, r=12, t=18, b=38;
        float pw=w-l-r, ph=h-t-b;
        // break 05:00~08:45
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(20,26,36));
        c.drawRect(x(840,l,pw),t,x(1065,l,pw),h-b,paint);
        textPaint.setColor(Color.rgb(101,112,134)); textPaint.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);
        c.drawText("休市", (x(840,l,pw)+x(1065,l,pw))/2-12, t+14, textPaint);

        float max=1;
        List<P> data;
        synchronized(pts){ data=new ArrayList<>(pts); }
        for(P p:data) max=Math.max(max,Math.max(p.actual,p.estimate));
        max*=1.08f;
        paint.setStrokeWidth(1); paint.setColor(grid); textPaint.setColor(muted); textPaint.setTextSize(9*getResources().getDisplayMetrics().scaledDensity);
        for(int i=0;i<5;i++){
            float y=t+ph*i/4f; c.drawLine(l,y,w-r,y,paint);
            String s=String.format(Locale.TAIWAN,"%,.0f",max*(1-i/4f));
            c.drawText(s,4,y+4,textPaint);
        }
        int[] mins={0,180,390,540,720,840,1065,1140,1260,1365};
        String[] labs={"15:00","18:00","21:30","00:00","03:00","05:00","08:45","10:00","12:00","13:45"};
        textPaint.setTextSize(8*getResources().getDisplayMetrics().scaledDensity);
        for(int i=0;i<mins.length;i++){
            float x=x(mins[i],l,pw); paint.setColor(Color.rgb(27,38,53)); c.drawLine(x,t,x,h-b,paint);
            float tw=textPaint.measureText(labs[i]); float tx=x-tw/2;
            if(i==0) tx=x; else if(i==mins.length-1) tx=x-tw;
            c.drawText(labs[i],tx,h-b+18,textPaint);
        }
        if(data.isEmpty()){
            textPaint.setColor(Color.rgb(145,160,184)); textPaint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
            c.drawText("等待台指期量能資料",18,30,textPaint); return;
        }
        drawLine(c,data,l,t,pw,ph,max,false,green,2f);
        drawLine(c,data,l,t,pw,ph,max,true,blue,3f);
    }

    private void drawLine(Canvas c,List<P> data,float l,float t,float pw,float ph,float max,boolean est,int color,float sw){
        Path path=new Path(); boolean started=false;
        for(P p:data){ float v=est?p.estimate:p.actual; if(v<=0) continue; float xx=x(p.minute,l,pw); float yy=t+ph*(1-v/max); if(!started){path.moveTo(xx,yy);started=true;} else path.lineTo(xx,yy); }
        if(started){ paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(sw*getResources().getDisplayMetrics().density); paint.setColor(color); c.drawPath(path,paint); }
    }
    private float x(float m,float l,float pw){ return l+pw*Math.max(0,Math.min(1365,m))/1365f; }
}
