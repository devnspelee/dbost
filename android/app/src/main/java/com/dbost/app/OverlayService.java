package com.dbost.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import java.io.*;

public class OverlayService extends Service {
    private WindowManager wm;
    private LinearLayout overlayView;
    private TextView tvCpu, tvRam, tvFps, tvBat, tvNet;
    private View cpuBar, ramBar, fpsBar;
    private Handler handler;
    private long[] prevCpuTimes = null;
    private int currentFps = 0, fpsCount = 0;
    private long fpsLastTime = 0;
    private Choreographer.FrameCallback frameCallback;
    private WindowManager.LayoutParams overlayParams;
    private boolean touchSmooth = false;
    private View btnSmooth;
    static final String CHANNEL_ID = "dbost_overlay";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("dBost Monitor")
            .setContentText("devnsepele monitor active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        startForeground(1, notif);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlay();
        startFpsCounter();
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(statsUpdater, 800);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildOverlay() {
        // Root
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setPadding(dp(12), dp(10), dp(12), dp(10));
        overlayView.setBackground(makeRoundRect(Color.argb(230, 8, 8, 14), dp(10)));

        // ── Header: dBost  [SMOOTH OFF]  [X]
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText("dBost");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(13);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setLetterSpacing(0.05f);

        // Dot
        TextView tvDot = new TextView(this);
        tvDot.setText("  *");
        tvDot.setTextColor(Color.argb(255, 0, 255, 136));
        tvDot.setTextSize(9);

        // Spacer
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
        View spacer = new View(this);
        spacer.setLayoutParams(sp);

        // Smooth button
        btnSmooth = makeChip("SMOOTH OFF", Color.argb(255, 42, 42, 62), Color.argb(180, 150, 150, 180));
        btnSmooth.setOnClickListener(v -> toggleSmooth());

        // Resize handle
        TextView tvResize = new TextView(this);
        tvResize.setText("  [=]");
        tvResize.setTextColor(Color.argb(100, 200, 200, 220));
        tvResize.setTextSize(11);
        tvResize.setOnTouchListener(new ResizeTouchListener());

        header.addView(tvTitle);
        header.addView(tvDot);
        header.addView(spacer);
        header.addView(btnSmooth);
        header.addView(tvResize);

        // ── Divider
        View div = new View(this);
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dvp.bottomMargin = dp(8);
        div.setBackgroundColor(Color.argb(40, 255, 255, 255));
        div.setLayoutParams(dvp);

        // ── Stats grid: CPU | RAM | FPS | BAT
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);

        tvCpu = makeStatLabel("CPU", "0%", Color.argb(255, 0, 212, 255));
        tvRam = makeStatLabel("RAM", "0%", Color.argb(255, 91, 74, 255));
        tvFps = makeStatLabel("FPS", "0", Color.argb(255, 0, 255, 136));
        tvBat = makeStatLabel("BAT", "0%", Color.argb(255, 255, 170, 0));

        statsRow.addView(tvCpu);
        statsRow.addView(makeSep());
        statsRow.addView(tvRam);
        statsRow.addView(makeSep());
        statsRow.addView(tvFps);
        statsRow.addView(makeSep());
        statsRow.addView(tvBat);

        // ── Mini bar row
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        brp.topMargin = dp(7);
        barRow.setLayoutParams(brp);

        cpuBar = makeBar(Color.argb(255, 0, 212, 255));
        ramBar = makeBar(Color.argb(255, 91, 74, 255));
        fpsBar = makeBar(Color.argb(255, 0, 255, 136));

        barRow.addView(wrapBar(cpuBar));
        barRow.addView(wrapBar(ramBar));
        barRow.addView(wrapBar(fpsBar));

        // ── Credit
        TextView tvCredit = new TextView(this);
        tvCredit.setText("devnsepele monitor");
        tvCredit.setTextColor(Color.argb(70, 160, 160, 200));
        tvCredit.setTextSize(8);
        tvCredit.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        crp.topMargin = dp(7);
        tvCredit.setLayoutParams(crp);

        overlayView.addView(header);
        overlayView.addView(div, dvp);
        overlayView.addView(statsRow);
        overlayView.addView(barRow);
        overlayView.addView(tvCredit);

        // ── Window params
        overlayParams = new WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(12);
        overlayParams.y = dp(120);

        overlayView.setOnTouchListener(new DragTouchListener(wm, overlayParams, overlayView));
        wm.addView(overlayView, overlayParams);
    }

    // ── Toggle smooth touch ───────────────────────────────────────────────────
    private void toggleSmooth() {
        touchSmooth = !touchSmooth;
        if (touchSmooth) {
            // Enable smooth touch: lower touch latency via pointer speed
            Settings.System.putInt(getContentResolver(),
                "pointer_speed", -7);
            updateChipState(btnSmooth, "SMOOTH ON",
                Color.argb(40, 0, 255, 136), Color.argb(255, 0, 255, 136));
        } else {
            Settings.System.putInt(getContentResolver(),
                "pointer_speed", 0);
            updateChipState(btnSmooth, "SMOOTH OFF",
                Color.argb(255, 42, 42, 62), Color.argb(180, 150, 150, 180));
        }
    }

    // ── FPS via Choreographer ─────────────────────────────────────────────────
    private void startFpsCounter() {
        fpsLastTime = System.nanoTime();
        frameCallback = frameTimeNanos -> {
            fpsCount++;
            long now = System.nanoTime();
            long diff = now - fpsLastTime;
            if (diff >= 1_000_000_000L) {
                currentFps = (int)(fpsCount * 1_000_000_000L / diff);
                fpsCount = 0;
                fpsLastTime = now;
            }
            Choreographer.getInstance().postFrameCallback(frameCallback);
        };
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    // ── Stats updater ─────────────────────────────────────────────────────────
    private final Runnable statsUpdater = new Runnable() {
        @Override public void run() {
            int cpu = getCpuUsage();
            int ram = getRamUsage();
            int fps = Math.min(currentFps, 120);
            int bat = getBattery();

            // Update labels
            setStatValue(tvCpu, "CPU", cpu + "%",
                cpu > 85 ? Color.argb(255, 255, 68, 102)
                : cpu > 65 ? Color.argb(255, 255, 170, 0)
                : Color.argb(255, 0, 212, 255));

            setStatValue(tvRam, "RAM", ram + "%",
                ram > 88 ? Color.argb(255, 255, 68, 102)
                : ram > 72 ? Color.argb(255, 255, 170, 0)
                : Color.argb(255, 91, 74, 255));

            setStatValue(tvFps, "FPS", String.valueOf(fps),
                fps < 30 ? Color.argb(255, 255, 68, 102)
                : fps < 50 ? Color.argb(255, 255, 170, 0)
                : Color.argb(255, 0, 255, 136));

            setStatValue(tvBat, "BAT", bat + "%",
                bat < 20 ? Color.argb(255, 255, 68, 102)
                : Color.argb(255, 255, 170, 0));

            // Update bars
            updateBar(cpuBar, cpu);
            updateBar(ramBar, ram);
            updateBar(fpsBar, (int)(fps / 1.2f));

            handler.postDelayed(this, 1000);
        }
    };

    // ── Real CPU ──────────────────────────────────────────────────────────────
    private int getCpuUsage() {
        try {
            RandomAccessFile r = new RandomAccessFile("/proc/stat", "r");
            String line = r.readLine(); r.close();
            String[] t = line.trim().split("\\s+");
            long idle = Long.parseLong(t[4]);
            long total = 0;
            for (int i = 1; i < Math.min(t.length, 9); i++) total += Long.parseLong(t[i]);
            if (prevCpuTimes != null) {
                long dI = idle - prevCpuTimes[0], dT = total - prevCpuTimes[1];
                prevCpuTimes = new long[]{idle, total};
                return (int)(100L * (dT - dI) / Math.max(dT, 1));
            }
            prevCpuTimes = new long[]{idle, total};
        } catch (Exception ignored) {}
        return 0;
    }

    // ── Real RAM ──────────────────────────────────────────────────────────────
    private int getRamUsage() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem == 0) return 0;
        return (int)(100L - mi.availMem * 100L / mi.totalMem);
    }

    // ── Real Battery ──────────────────────────────────────────────────────────
    private int getBattery() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return 0;
        int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return (sc > 0 && lv >= 0) ? (int)(lv * 100f / sc) : 0;
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private TextView makeStatLabel(String label, String val, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.argb(120, 200, 200, 220));
        lbl.setTextSize(8);
        lbl.setGravity(Gravity.CENTER);
        lbl.setLetterSpacing(0.1f);

        TextView value = new TextView(this);
        value.setText(val);
        value.setTextColor(color);
        value.setTextSize(13);
        value.setTypeface(Typeface.MONOSPACE);
        value.setGravity(Gravity.CENTER);

        col.addView(lbl);
        col.addView(value);
        col.setTag(value); // store ref to value TextView
        return (TextView)(Object) col; // return as TextView but cast via tag
    }

    private void setStatValue(TextView container, String label, String val, int color) {
        LinearLayout col = (LinearLayout)(Object) container;
        TextView valView = (TextView) col.getChildAt(1);
        if (valView != null) {
            valView.setText(val);
            valView.setTextColor(color);
        }
    }

    private View makeSep() {
        View sep = new View(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(1, dp(24));
        p.leftMargin = dp(4); p.rightMargin = dp(4);
        sep.setBackgroundColor(Color.argb(30, 255, 255, 255));
        sep.setLayoutParams(p);
        return sep;
    }

    private View makeBar(int color) {
        View bar = new View(this);
        bar.setBackgroundColor(color);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(3)));
        return bar;
    }

    private LinearLayout wrapBar(View bar) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, dp(3), 1f);
        wp.rightMargin = dp(3);
        wrap.setLayoutParams(wp);
        wrap.setBackground(makeRoundRect(Color.argb(40, 255, 255, 255), dp(2)));
        wrap.addView(bar);
        return wrap;
    }

    private void updateBar(View bar, int pct) {
        View parent = (View) bar.getParent();
        if (parent instanceof LinearLayout) {
            int totalW = parent.getWidth();
            if (totalW > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                lp.width = (int)(totalW * Math.min(pct, 100) / 100f);
                bar.setLayoutParams(lp);
            }
        }
    }

    private View makeChip(String text, int bg, int textColor) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(8.5f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setLetterSpacing(0.05f);
        chip.setPadding(dp(7), dp(3), dp(7), dp(3));
        chip.setBackground(makeRoundRect(bg, dp(6)));
        return chip;
    }

    private void updateChipState(View chip, String text, int bg, int textColor) {
        if (chip instanceof TextView) {
            ((TextView) chip).setText(text);
            ((TextView) chip).setTextColor(textColor);
            chip.setBackground(makeRoundRect(bg, dp(6)));
        }
    }

    private android.graphics.drawable.GradientDrawable makeRoundRect(int color, int radius) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    // ── Resize ────────────────────────────────────────────────────────────────
    private class ResizeTouchListener implements View.OnTouchListener {
        private float startX; private int startW;
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                startX = e.getRawX();
                startW = overlayParams.width > 0 ? overlayParams.width : dp(300);
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = startX - e.getRawX();
                overlayParams.width = Math.max(dp(240), Math.min(dp(480), (int)(startW + dx)));
                wm.updateViewLayout(overlayView, overlayParams);
            }
            return true;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacks(statsUpdater);
        if (frameCallback != null) Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (overlayView != null) wm.removeView(overlayView);
        // Reset pointer speed on destroy
        if (touchSmooth) {
            Settings.System.putInt(getContentResolver(), "pointer_speed", 0);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "dBost Monitor", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
