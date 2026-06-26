package com.dbost.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import java.io.*;

public class OverlayService extends Service {
    private WindowManager wm;
    private LinearLayout overlayView;
    private Handler handler;
    private long[] prevCpuTimes = null;
    private int currentFps = 0, fpsCount = 0;
    private long fpsLastTime = 0;
    private Choreographer.FrameCallback frameCallback;
    private WindowManager.LayoutParams overlayParams;
    private boolean touchSmooth = false;
    private TextView btnSmooth;

    // Stat value TextViews
    private TextView tvCpuVal, tvRamVal, tvFpsVal, tvBatVal;
    private View cpuBar, ramBar, fpsBar;

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
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setPadding(dp(12), dp(10), dp(12), dp(10));
        overlayView.setBackground(makeRoundRect(Color.argb(230, 8, 8, 14), dp(10)));

        // ── Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("dBost");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(13);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);

        TextView tvDot = new TextView(this);
        tvDot.setText("  *");
        tvDot.setTextColor(Color.argb(255, 0, 255, 136));
        tvDot.setTextSize(9);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        btnSmooth = new TextView(this);
        btnSmooth.setText("SMOOTH OFF");
        btnSmooth.setTextColor(Color.argb(180, 150, 150, 180));
        btnSmooth.setTextSize(8.5f);
        btnSmooth.setTypeface(Typeface.DEFAULT_BOLD);
        btnSmooth.setPadding(dp(7), dp(3), dp(7), dp(3));
        btnSmooth.setBackground(makeRoundRect(Color.argb(255, 42, 42, 62), dp(6)));
        btnSmooth.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) toggleSmooth();
            return true;
        });

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

        // ── Stats row
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout colCpu = makeStatCol("CPU", Color.argb(255, 0, 212, 255));
        LinearLayout colRam = makeStatCol("RAM", Color.argb(255, 91, 74, 255));
        LinearLayout colFps = makeStatCol("FPS", Color.argb(255, 0, 255, 136));
        LinearLayout colBat = makeStatCol("BAT", Color.argb(255, 255, 170, 0));

        tvCpuVal = (TextView) colCpu.getChildAt(1);
        tvRamVal = (TextView) colRam.getChildAt(1);
        tvFpsVal = (TextView) colFps.getChildAt(1);
        tvBatVal = (TextView) colBat.getChildAt(1);

        statsRow.addView(colCpu);
        statsRow.addView(makeSep());
        statsRow.addView(colRam);
        statsRow.addView(makeSep());
        statsRow.addView(colFps);
        statsRow.addView(makeSep());
        statsRow.addView(colBat);

        // ── Mini bars
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        brp.topMargin = dp(7);
        barRow.setLayoutParams(brp);

        cpuBar = new View(this);
        cpuBar.setBackgroundColor(Color.argb(255, 0, 212, 255));
        ramBar = new View(this);
        ramBar.setBackgroundColor(Color.argb(255, 91, 74, 255));
        fpsBar = new View(this);
        fpsBar.setBackgroundColor(Color.argb(255, 0, 255, 136));

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
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(12);
        overlayParams.y = dp(120);

        // Drag listener — tidak block child tap
        DragTouchListener dragListener = new DragTouchListener(wm, overlayParams, overlayView);
        overlayView.setOnTouchListener((v, e) -> {
            dragListener.onTouch(v, e);
            return dragListener.isDragging;
        });

        wm.addView(overlayView, overlayParams);
    }

    // ── Toggle smooth (window animation speed) ───────────────────────────────
    private void toggleSmooth() {
        touchSmooth = !touchSmooth;
        if (touchSmooth) {
            // Percepat animasi window = terasa lebih responsif
            android.provider.Settings.Global.putFloat(
                getContentResolver(),
                android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, 0.5f);
            android.provider.Settings.Global.putFloat(
                getContentResolver(),
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 0.5f);
            btnSmooth.setText("SMOOTH ON");
            btnSmooth.setTextColor(Color.argb(255, 0, 255, 136));
            btnSmooth.setBackground(makeRoundRect(Color.argb(40, 0, 255, 136), dp(6)));
        } else {
            android.provider.Settings.Global.putFloat(
                getContentResolver(),
                android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f);
            android.provider.Settings.Global.putFloat(
                getContentResolver(),
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f);
            btnSmooth.setText("SMOOTH OFF");
            btnSmooth.setTextColor(Color.argb(180, 150, 150, 180));
            btnSmooth.setBackground(makeRoundRect(Color.argb(255, 42, 42, 62), dp(6)));
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

            int cpuColor = cpu > 85 ? Color.argb(255, 255, 68, 102)
                : cpu > 65 ? Color.argb(255, 255, 170, 0)
                : Color.argb(255, 0, 212, 255);
            int ramColor = ram > 88 ? Color.argb(255, 255, 68, 102)
                : ram > 72 ? Color.argb(255, 255, 170, 0)
                : Color.argb(255, 91, 74, 255);
            int fpsColor = fps < 30 ? Color.argb(255, 255, 68, 102)
                : fps < 50 ? Color.argb(255, 255, 170, 0)
                : Color.argb(255, 0, 255, 136);
            int batColor = bat < 20 ? Color.argb(255, 255, 68, 102)
                : Color.argb(255, 255, 170, 0);

            tvCpuVal.setText(cpu + "%"); tvCpuVal.setTextColor(cpuColor);
            tvRamVal.setText(ram + "%"); tvRamVal.setTextColor(ramColor);
            tvFpsVal.setText(String.valueOf(fps)); tvFpsVal.setTextColor(fpsColor);
            tvBatVal.setText(bat + "%"); tvBatVal.setTextColor(batColor);

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
    private LinearLayout makeStatCol(String label, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.argb(120, 200, 200, 220));
        lbl.setTextSize(8);
        lbl.setGravity(Gravity.CENTER);
        lbl.setLetterSpacing(0.1f);

        TextView val = new TextView(this);
        val.setText("0");
        val.setTextColor(color);
        val.setTextSize(13);
        val.setTypeface(Typeface.MONOSPACE);
        val.setGravity(Gravity.CENTER);

        col.addView(lbl);
        col.addView(val);
        return col;
    }

    private View makeSep() {
        View sep = new View(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(1, dp(24));
        p.leftMargin = dp(4); p.rightMargin = dp(4);
        sep.setBackgroundColor(Color.argb(30, 255, 255, 255));
        sep.setLayoutParams(p);
        return sep;
    }

    private LinearLayout wrapBar(View bar) {
        LinearLayout wrap = new LinearLayout(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, dp(3), 1f);
        wp.rightMargin = dp(3);
        wrap.setLayoutParams(wp);
        wrap.setBackground(makeRoundRect(Color.argb(40, 255, 255, 255), dp(2)));
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(3)));
        wrap.addView(bar);
        return wrap;
    }

    private void updateBar(View bar, int pct) {
        View parent = (View) bar.getParent();
        if (parent != null && parent.getWidth() > 0) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.width = (int)(parent.getWidth() * Math.min(pct, 100) / 100f);
            bar.setLayoutParams(lp);
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
                overlayParams.width = Math.max(dp(220), Math.min(dp(480), (int)(startW + dx)));
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
