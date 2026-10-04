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
        int minute;
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
                p.minute = o.has("minute") ? o.optInt("minute", parseMinute(p.time)) : parseMinute(p.time);
                p.minute = Math.max(0, Math.min(270, p.minute));
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

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpF(1));
        textPaint.setTextSize(sp(8));
        textPaint.setColor(txt);

        int[] axisMinutes = new int[]{0, 60, 120, 180, 240, 270};
        String[] axisText = new String[]{"09:00", "10:00", "11:00", "12:00", "13:00", "13:30"};

        if (points.isEmpty()) {
            for (int i = 0; i < axisMinutes.length; i++) {
                float x = left + pw * axisMinutes[i] / 270f;
                paint.setColor(Color.rgb(27, 38, 53));
                canvas.drawLine(x, top, x, bottom, paint);
                textPaint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == axisMinutes.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                canvas.drawText(axisText[i], x, bottom + dp(18), textPaint);
            }
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(11));
            canvas.drawText("日盤 09:00~13:30；夜盤不產生量能資料", left, top + dp(24), textPaint);
            return;
        }

        double hi = 0.0;
        for (Pt p : points) {
            hi = Math.max(hi, p.estimate);
            hi = Math.max(hi, p.actual);
        }
        hi = Math.max(1.0, hi * 1.08);
        double lo = 0.0;

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

        for (int i = 0; i < axisMinutes.length; i++) {
            float x = left + pw * axisMinutes[i] / 270f;
            paint.setColor(Color.rgb(27, 38, 53));
            canvas.drawLine(x, top, x, bottom, paint);
            textPaint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == axisMinutes.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(axisText[i], x, bottom + dp(18), textPaint);
        }

        Path est = new Path();
        Path actual = new Path();
        boolean estStarted = false;
        boolean actualStarted = false;
        Pt last = null;
        for (Pt p : points) {
            float x = left + pw * p.minute / 270f;
            if (p.estimate > 0) {
                float ey = y(p.estimate, lo, hi, top, ph);
                if (!estStarted) { est.moveTo(x, ey); estStarted = true; }
                else est.lineTo(x, ey);
            }
            if (p.actual > 0) {
                float ay = y(p.actual, lo, hi, top, ph);
                if (!actualStarted) { actual.moveTo(x, ay); actualStarted = true; }
                else actual.lineTo(x, ay);
            }
            last = p;
        }
        if (actualStarted) {
            paint.setColor(actualColor);
            paint.setStrokeWidth(dpF(2f));
            canvas.drawPath(actual, paint);
        }
        if (estStarted) {
            paint.setColor(estimateColor);
            paint.setStrokeWidth(dpF(2.6f));
            canvas.drawPath(est, paint);
        }

        if (last != null && last.minute >= 270 && last.actual > 0) {
            float x = right;
            float yy = y(last.actual, lo, hi, top, ph);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, yy, dpF(4), paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private int parseMinute(String value) {
        try {
            String[] p = value.split(":");
            int hh = Integer.parseInt(p[0]);
            int mm = Integer.parseInt(p[1]);
            return Math.max(0, Math.min(270, hh * 60 + mm - 9 * 60));
        } catch (Throwable ignored) {
            return 0;
        }
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
