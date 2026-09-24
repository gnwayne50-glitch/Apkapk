package com.optionradar.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native estimated-turnover curve.  Pure Canvas; no WebView/chart dependency. */
public class TurnoverChartView extends View {
    private static class Pt {
        String time;
        double estimate;
        double actual;
    }

    private final List<Pt> points = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double avg20 = 0.0;

    private final int grid = Color.rgb(38, 46, 58);
    private final int txt = Color.rgb(137, 147, 163);
    private final int estimateColor = Color.rgb(87, 167, 255);
    private final int actualColor = Color.rgb(52, 211, 153);
    private final int avgColor = Color.rgb(126, 142, 166);

    public TurnoverChartView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(10, 15, 22));
        textPaint.setColor(txt);
        textPaint.setTextSize(sp(9));
    }

    public void setData(JSONArray arr, double avg20Value) {
        points.clear();
        avg20 = Math.max(0.0, avg20Value);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                double est = o.optDouble("estimated_final_yi", 0.0);
                if (!(est > 0)) continue;
                Pt p = new Pt();
                p.time = o.optString("time", "--");
                p.estimate = est;
                p.actual = o.optDouble("turnover_yi", 0.0);
                points.add(p);
            }
        }
        invalidate();
    }

    public void clearData() {
        points.clear();
        avg20 = 0.0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w < dp(120) || h < dp(120)) return;
        float left = dp(54);
        float right = w - dp(10);
        float top = dp(12);
        float bottom = h - dp(26);
        float pw = right - left;
        float ph = bottom - top;

        if (points.isEmpty()) {
            textPaint.setTextSize(sp(12));
            canvas.drawText("等待預估量資料", left, top + dp(24), textPaint);
            return;
        }

        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Pt p : points) {
            lo = Math.min(lo, p.estimate);
            hi = Math.max(hi, p.estimate);
        }
        if (avg20 > 0) {
            lo = Math.min(lo, avg20);
            hi = Math.max(hi, avg20);
        }
        if (!Double.isFinite(lo) || !Double.isFinite(hi)) return;
        double pad = Math.max(80.0, (hi - lo) * 0.12);
        lo = Math.max(0.0, lo - pad);
        hi = hi + pad;
        if (hi <= lo) hi = lo + 1.0;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpF(1));
        paint.setColor(grid);
        textPaint.setTextSize(sp(8));
        textPaint.setColor(txt);
        for (int i = 0; i < 4; i++) {
            float y = top + ph * i / 3f;
            canvas.drawLine(left, y, right, y, paint);
            double v = hi - (hi - lo) * i / 3.0;
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.TAIWAN, "%.0f", v), left - dp(5), y + sp(3), textPaint);
        }

        if (avg20 > 0) {
            float y = y(avg20, lo, hi, top, ph);
            paint.setColor(avgColor);
            paint.setStrokeWidth(dpF(1));
            canvas.drawLine(left, y, right, y, paint);
        }

        Path est = new Path();
        Path actual = new Path();
        boolean estStarted = false;
        boolean actualStarted = false;
        int n = points.size();
        for (int i = 0; i < n; i++) {
            Pt p = points.get(i);
            float x = left + pw * i / Math.max(1f, n - 1f);
            float ey = y(p.estimate, lo, hi, top, ph);
            if (!estStarted) { est.moveTo(x, ey); estStarted = true; }
            else est.lineTo(x, ey);
            if (p.actual >= lo * 0.75 && p.actual <= hi * 1.05) {
                float ay = y(p.actual, lo, hi, top, ph);
                if (!actualStarted) { actual.moveTo(x, ay); actualStarted = true; }
                else actual.lineTo(x, ay);
            }
        }
        if (actualStarted) {
            paint.setColor(actualColor);
            paint.setStrokeWidth(dpF(1.1f));
            canvas.drawPath(actual, paint);
        }
        if (estStarted) {
            paint.setColor(estimateColor);
            paint.setStrokeWidth(dpF(2.4f));
            canvas.drawPath(est, paint);
        }

        int[] labels = new int[]{0, n / 2, n - 1};
        for (int idx : labels) {
            if (idx < 0 || idx >= n) continue;
            float x = left + pw * idx / Math.max(1f, n - 1f);
            textPaint.setTextAlign(idx == 0 ? Paint.Align.LEFT : idx == n - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(points.get(idx).time, x, bottom + dp(18), textPaint);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private float y(double value, double lo, double hi, float top, float ph) {
        return top + (float)((hi - value) / (hi - lo) * ph);
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
