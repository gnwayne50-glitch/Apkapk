package com.optionradar.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lightweight native candlestick chart for OptionRadar. No WebView and no external chart library. */
public class KlineChartView extends View {

    private static class Bar {
        String time;
        double open;
        double high;
        double low;
        double close;
        long volume;
    }

    private final List<Bar> bars = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int visibleCount = 80;
    private int offsetBars = 0;
    private boolean ma5 = true;
    private boolean ma10 = true;
    private boolean ma20 = true;
    private boolean ma60 = true;

    private float downX;
    private float downY;
    private int downOffset;
    private boolean horizontalDrag;

    private final int bg = Color.rgb(10, 15, 22);
    private final int grid = Color.rgb(36, 45, 58);
    private final int txt = Color.rgb(139, 151, 168);
    private final int up = Color.rgb(255, 93, 103);
    private final int down = Color.rgb(52, 211, 153);
    private final int ma5Color = Color.rgb(241, 200, 75);
    private final int ma10Color = Color.rgb(85, 167, 255);
    private final int ma20Color = Color.rgb(223, 136, 255);
    private final int ma60Color = Color.rgb(255, 152, 82);

    public KlineChartView(Context context) {
        super(context);
        setBackgroundColor(bg);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        textPaint.setTextSize(sp(10));
        textPaint.setColor(txt);
        setFocusable(false);
    }

    public void setVisibleCount(int value) {
        visibleCount = Math.max(20, Math.min(160, value));
        clampOffset();
        invalidate();
    }

    public void setMaEnabled(int period, boolean enabled) {
        if (period == 5) ma5 = enabled;
        else if (period == 10) ma10 = enabled;
        else if (period == 20) ma20 = enabled;
        else if (period == 60) ma60 = enabled;
        invalidate();
    }

    public boolean isMaEnabled(int period) {
        if (period == 5) return ma5;
        if (period == 10) return ma10;
        if (period == 20) return ma20;
        if (period == 60) return ma60;
        return false;
    }

    public void setBars(JSONArray arr, boolean resetViewport) {
        List<Bar> incoming = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                double close = o.optDouble("close", 0.0);
                if (!(close > 0)) continue;
                Bar b = new Bar();
                b.time = o.optString("display_time", o.optString("ts", ""));
                b.open = o.optDouble("open", close);
                b.high = o.optDouble("high", Math.max(b.open, close));
                b.low = o.optDouble("low", Math.min(b.open, close));
                b.close = close;
                b.volume = o.optLong("volume", 0L);
                incoming.add(b);
            }
        }
        bars.clear();
        bars.addAll(incoming);
        if (resetViewport) offsetBars = 0;
        clampOffset();
        invalidate();
    }

    public void clearBars() {
        bars.clear();
        offsetBars = 0;
        invalidate();
    }

    private void clampOffset() {
        int window = Math.min(visibleCount, bars.size());
        int max = Math.max(0, bars.size() - window);
        if (offsetBars < 0) offsetBars = 0;
        if (offsetBars > max) offsetBars = max;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= dp(120) || h <= dp(120)) return;

        float left = dp(8);
        float right = w - dp(52);
        float top = dp(10);
        float bottom = h - dp(28);
        float plotW = right - left;
        float plotH = bottom - top;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpF(1));
        paint.setColor(grid);
        for (int i = 0; i < 4; i++) {
            float y = top + plotH * i / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }

        if (bars.isEmpty()) {
            textPaint.setColor(txt);
            textPaint.setTextSize(sp(13));
            canvas.drawText("沒有 K 線資料", left + dp(8), top + dp(24), textPaint);
            return;
        }

        clampOffset();
        int end = bars.size() - offsetBars;
        int start = Math.max(0, end - visibleCount);
        if (end <= start) return;
        int count = end - start;

        double[] m5 = maValues(5);
        double[] m10 = maValues(10);
        double[] m20 = maValues(20);
        double[] m60 = maValues(60);

        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) {
            Bar b = bars.get(i);
            lo = Math.min(lo, b.low);
            hi = Math.max(hi, b.high);
            if (ma5 && !Double.isNaN(m5[i])) { lo = Math.min(lo, m5[i]); hi = Math.max(hi, m5[i]); }
            if (ma10 && !Double.isNaN(m10[i])) { lo = Math.min(lo, m10[i]); hi = Math.max(hi, m10[i]); }
            if (ma20 && !Double.isNaN(m20[i])) { lo = Math.min(lo, m20[i]); hi = Math.max(hi, m20[i]); }
            if (ma60 && !Double.isNaN(m60[i])) { lo = Math.min(lo, m60[i]); hi = Math.max(hi, m60[i]); }
        }
        if (!Double.isFinite(lo) || !Double.isFinite(hi)) return;
        if (hi <= lo) hi = lo + 1.0;
        double extra = Math.max(1.0, (hi - lo) * 0.04);
        lo -= extra;
        hi += extra;

        float step = plotW / count;
        float candleW = Math.max(dpF(2), Math.min(dpF(8), step * 0.62f));
        for (int i = start; i < end; i++) {
            Bar b = bars.get(i);
            float x = left + step * (i - start + 0.5f);
            int color = b.close >= b.open ? up : down;
            paint.setColor(color);
            paint.setStrokeWidth(dpF(1));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(x, y(b.high, lo, hi, top, plotH), x, y(b.low, lo, hi, top, plotH), paint);

            float y1 = y(Math.max(b.open, b.close), lo, hi, top, plotH);
            float y2 = y(Math.min(b.open, b.close), lo, hi, top, plotH);
            float bodyH = Math.max(dpF(1), y2 - y1);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(x - candleW / 2f, y1, x + candleW / 2f, y1 + bodyH, paint);
        }

        if (ma5) drawMa(canvas, m5, start, end, left, top, plotW, plotH, lo, hi, ma5Color, dpF(1.4f));
        if (ma10) drawMa(canvas, m10, start, end, left, top, plotW, plotH, lo, hi, ma10Color, dpF(1.4f));
        if (ma20) drawMa(canvas, m20, start, end, left, top, plotW, plotH, lo, hi, ma20Color, dpF(1.5f));
        if (ma60) drawMa(canvas, m60, start, end, left, top, plotW, plotH, lo, hi, ma60Color, dpF(1.8f));

        textPaint.setColor(txt);
        textPaint.setTextSize(sp(9));
        for (int i = 0; i < 4; i++) {
            double p = hi - (hi - lo) * i / 3.0;
            float yy = top + plotH * i / 3f + sp(3);
            canvas.drawText(priceLabel(p, hi - lo), right + dp(5), yy, textPaint);
        }

        int mid = start + count / 2;
        drawTimeLabel(canvas, shortTime(bars.get(start).time), left, bottom + dp(18), Paint.Align.LEFT);
        drawTimeLabel(canvas, shortTime(bars.get(mid).time), left + plotW / 2f, bottom + dp(18), Paint.Align.CENTER);
        drawTimeLabel(canvas, shortTime(bars.get(end - 1).time), right, bottom + dp(18), Paint.Align.RIGHT);
    }

    private void drawMa(Canvas canvas, double[] values, int start, int end,
                        float left, float top, float plotW, float plotH,
                        double lo, double hi, int color, float width) {
        int count = end - start;
        if (count <= 0) return;
        float step = plotW / count;
        Path p = new Path();
        boolean started = false;
        for (int i = start; i < end; i++) {
            double v = values[i];
            if (Double.isNaN(v)) continue;
            float x = left + step * (i - start + 0.5f);
            float yy = y(v, lo, hi, top, plotH);
            if (!started) {
                p.moveTo(x, yy);
                started = true;
            } else {
                p.lineTo(x, yy);
            }
        }
        if (!started) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawPath(p, paint);
    }

    private double[] maValues(int n) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) out[i] = Double.NaN;
        double sum = 0.0;
        for (int i = 0; i < bars.size(); i++) {
            sum += bars.get(i).close;
            if (i >= n) sum -= bars.get(i - n).close;
            if (i >= n - 1) out[i] = sum / n;
        }
        return out;
    }

    private float y(double price, double lo, double hi, float top, float plotH) {
        return top + (float)((hi - price) / (hi - lo) * plotH);
    }

    private String priceLabel(double v, double span) {
        return span < 20 ? String.format(Locale.TAIWAN, "%.1f", v) : String.format(Locale.TAIWAN, "%.0f", v);
    }

    private String shortTime(String s) {
        if (s == null || s.isEmpty()) return "--";
        int space = s.lastIndexOf(' ');
        if (space >= 0 && space + 1 < s.length()) return s.substring(space + 1);
        if (s.length() >= 5) return s.substring(s.length() - 5);
        return s;
    }

    private void drawTimeLabel(Canvas canvas, String s, float x, float y, Paint.Align align) {
        textPaint.setTextAlign(align);
        textPaint.setTextSize(sp(9));
        textPaint.setColor(txt);
        canvas.drawText(s, x, y, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bars.size() <= visibleCount) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downOffset = offsetBars;
                horizontalDrag = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!horizontalDrag && Math.abs(dx) > dpF(8) && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    horizontalDrag = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (horizontalDrag) {
                    float plotW = Math.max(dpF(100), getWidth() - dpF(60));
                    float step = plotW / Math.max(1, Math.min(visibleCount, bars.size()));
                    offsetBars = downOffset + Math.round(dx / Math.max(dpF(2), step));
                    clampOffset();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                horizontalDrag = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(int v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
