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
    private boolean smoothOn = false;
    private TextView btnSmooth;
    private TextView tvCpuVal, tvRamVal, tvFpsVal, tvBatVal;
    private View cpuBar, ramBar, fpsBar, batBar;
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

    private android.graphics.drawable.GradientDrawable roundRect(int color, int radius) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private android.graphics.drawable.GradientDrawable roundRectStroke(int color, int radius, int strokeW, int strokeColor) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        gd.setStroke(strokeW, strokeColor);
        return gd;
    }

    private void buildOverlay() {
        // ── Root
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setPadding(dp(14), dp(11), dp(14), dp(12));
        overlayView.setBackground(roundRectStroke(
            Color.argb(240, 7, 7, 13), dp(14),
            1, Color.argb(60, 108, 92, 231)));

        // ── Header row
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(9));

        // Logo box
        TextView tvLogo = new TextView(this);
        tvLogo.setText("d");
        tvLogo.setTextColor(Color.WHITE);
        tvLogo.setTextSize(13);
        tvLogo.setTypeface(Typeface.DEFAULT_BOLD);
        tvLogo.setGravity(Gravity.CENTER);
        tvLogo.setPadding(dp(6), dp(3), dp(6), dp(3));
        tvLogo.setBackground(roundRect(Color.argb(255, 108, 92, 231), dp(6)));

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText("  dBost");
        tvTitle.setTextColor(Color.argb(240, 220, 220, 235));
        tvTitle.setTextSize(12);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setLetterSpacing(0.02f);

        // Spacer
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        // Smooth button
        btnSmooth = new TextView(this);
        btnSmooth.setText("SMOOTH");
        btnSmooth.setTextColor(Color.argb(160, 150, 150, 180));
        btnSmooth.setTextSize(8f);
        btnSmooth.setTypeface(Typeface.DEFAULT_BOLD);
        btnSmooth.setLetterSpacing(0.06f);
        btnSmooth.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnSmooth.setBackground(roundRectStroke(
            Color.argb(255, 30, 30, 46), dp(6),
            1, Color.argb(80, 150, 150, 180)));
        // Use setOnClickListener on a wrapper to avoid drag conflict
        btnSmooth.setClickable(true);
        btnSmooth.setFocusable(true);
        btnSmooth.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                toggleSmooth();
            }
            return true; // consume — prevent drag propagation
        });

        // Resize handle
        TextView tvResize = new TextView(this);
        tvResize.setText("  [=]");
        tvResize.setTextColor(Color.argb(80, 200, 200, 220));
        tvResize.setTextSize(10);
        tvResize.setOnTouchListener(new ResizeTouchListener());

        header.addView(tvLogo);
        header.addView(tvTitle);
        header.addView(spacer);
        header.addView(btnSmooth);
        header.addView(tvResize);

        // ── Divider
        View div = new View(this);
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dvp.bottomMargin = dp(10);
        div.setBackgroundColor(Color.argb(35, 255, 255, 255));

        // ── Stats grid
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout colCpu = makeStatCol("CPU", Color.argb(255, 0, 206, 201));
        LinearLayout colRam = makeStatCol("RAM", Color.argb(255, 108, 92, 231));
        LinearLayout colFps = makeStatCol("FPS", Color.argb(255, 0, 184, 148));
        LinearLayout colBat = makeStatCol("BAT", Color.argb(255, 253, 203, 110));

        tvCpuVal = (TextView) colCpu.getChildAt(1);
        tvRamVal = (TextView) colRam.getChildAt(1);
        tvFpsVal = (TextView) colFps.getChildAt(1);
        tvBatVal = (TextView) colBat.getChildAt(1);

        statsRow.addView(colCpu);
        statsRow.addView(makeVDiv());
        statsRow.addView(colRam);
        statsRow.addView(makeVDiv());
        statsRow.addView(colFps);
        statsRow.addView(makeVDiv());
        statsRow.addView(colBat);

        // ── Progress bars
        LinearLayout barSection = new LinearLayout(this);
        barSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bsp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        bsp.topMargin = dp(10);
        barSection.setLayoutParams(bsp);

        cpuBar = new View(this); cpuBar.setBackgroundColor(Color.argb(255, 0, 206, 201));
        ramBar = new View(this); ramBar.setBackgroundColor(Color.argb(255, 108, 92, 231));
        fpsBar = new View(this); fpsBar.setBackgroundColor(Color.argb(255, 0, 184, 148));
        batBar = new View(this); batBar.setBackgroundColor(Color.argb(255, 253, 203, 110));

        barSection.addView(makeBarRow(cpuBar, "CPU"));
        barSection.addView(makeBarRow(ramBar, "RAM"));
        barSection.addView(makeBarRow(fpsBar, "FPS"));
        barSection.addView(makeBarRow(batBar, "BAT"));

        // ── Credit
        TextView tvCredit = new TextView(this);
        tvCredit.setText("devnsepele monitor");
        tvCredit.setTextColor(Color.argb(55, 162, 155, 254));
        tvCredit.setTextSize(7.5f);
        tvCredit.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        crp.topMargin = dp(9);
        tvCredit.setLayoutParams(crp);

        overlayView.addView(header);
        overlayView.addView(div, dvp);
        overlayView.addView(statsRow);
        overlayView.addView(barSection);
        overlayView.addView(tvCredit);

        // ── Window params
        overlayParams = new WindowManager.LayoutParams(
            dp(290), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(12);
        overlayParams.y = dp(100);

        // Drag — tidak block child tap
        DragTouchListener dragListener = new DragTouchListener(wm, overlayParams, overlayView);
        overlayView.setOnTouchListener((v, e) -> {
            dragListener.onTouch(v, e);
            return dragListener.isDragging;
        });

        wm.addView(overlayView, overlayParams);
    }

    // ── Toggle smooth ─────────────────────────────────────────────────────────
    private void toggleSmooth() {
        smoothOn = !smoothOn;
        try {
            android.provider.Settings.Global.putFloat(getContentResolver(),
                android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, smoothOn ? 0.5f : 1.0f);
            android.provider.Settings.Global.putFloat(getContentResolver(),
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, smoothOn ? 0.5f : 1.0f);
        } catch (Exception ignored) {}

        if (smoothOn) {
            btnSmooth.setText("SMOOTH ON");
            btnSmooth.setTextColor(Color.argb(255, 0, 184, 148));
            btnSmooth.setBackground(roundRectStroke(
                Color.argb(40, 0, 184, 148), dp(6),
                1, Color.argb(120, 0, 184, 148)));
        } else {
            btnSmooth.setText("SMOOTH");
            btnSmooth.setTextColor(Color.argb(160, 150, 150, 180));
            btnSmooth.setBackground(roundRectStroke(
                Color.argb(255, 30, 30, 46), dp(6),
                1, Color.argb(80, 150, 150, 180)));
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

            int cpuC = cpu > 85 ? Color.argb(255, 232, 67, 147)
                : cpu > 65 ? Color.argb(255, 253, 203, 110)
                : Color.argb(255, 0, 206, 201);
            int ramC = ram > 88 ? Color.argb(255, 232, 67, 147)
                : ram > 72 ? Color.argb(255, 253, 203, 110)
                : Color.argb(255, 108, 92, 231);
            int fpsC = fps < 30 ? Color.argb(255, 232, 67, 147)
                : fps < 50 ? Color.argb(255, 253, 203, 110)
                : Color.argb(255, 0, 184, 148);
            int batC = bat < 20 ? Color.argb(255, 232, 67, 147)
                : Color.argb(255, 253, 203, 110);

            tvCpuVal.setText(cpu + "%"); tvCpuVal.setTextColor(cpuC);
            tvRamVal.setText(ram + "%"); tvRamVal.setTextColor(ramC);
            tvFpsVal.setText(String.valueOf(fps)); tvFpsVal.setTextColor(fpsC);
            tvBatVal.setText(bat + "%"); tvBatVal.setTextColor(batC);

            updateBar(cpuBar, cpu);
            updateBar(ramBar, ram);
            updateBar(fpsBar, (int)(fps / 1.2f));
            updateBar(batBar, bat);

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
        lbl.setTextColor(Color.argb(100, 200, 200, 220));
        lbl.setTextSize(7.5f);
        lbl.setGravity(Gravity.CENTER);
        lbl.setLetterSpacing(0.12f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);

        TextView val = new TextView(this);
        val.setText("--");
        val.setTextColor(color);
        val.setTextSize(14);
        val.setTypeface(Typeface.MONOSPACE);
        val.setGravity(Gravity.CENTER);

        col.addView(lbl);
        col.addView(val);
        return col;
    }

    private View makeVDiv() {
        View sep = new View(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(1, dp(28));
        p.leftMargin = dp(3); p.rightMargin = dp(3);
        sep.setBackgroundColor(Color.argb(25, 255, 255, 255));
        sep.setLayoutParams(p);
        return sep;
    }

    private LinearLayout makeBarRow(View bar, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        rp.bottomMargin = dp(4);
        row.setLayoutParams(rp);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.argb(70, 200, 200, 220));
        lbl.setTextSize(7f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT);
        lbl.setLayoutParams(lp);

        LinearLayout track = new LinearLayout(this);
        track.setLayoutParams(new LinearLayout.LayoutParams(0, dp(4), 1f));
        track.setBackground(roundRect(Color.argb(40, 255, 255, 255), dp(2)));
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(4)));
        track.addView(bar);

        row.addView(lbl);
        row.addView(track);
        return row;
    }

    private void updateBar(View bar, int pct) {
        View parent = (View) bar.getParent();
        if (parent != null && parent.getWidth() > 0) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.width = (int)(parent.getWidth() * Math.min(pct, 100) / 100f);
            bar.setLayoutParams(lp);
        }
    }

    // ── Resize ────────────────────────────────────────────────────────────────
    private class ResizeTouchListener implements View.OnTouchListener {
        private float startX; private int startW;
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                startX = e.getRawX();
                startW = overlayParams.width > 0 ? overlayParams.width : dp(290);
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = startX - e.getRawX();
                overlayParams.width = Math.max(dp(220), Math.min(dp(500), (int)(startW + dx)));
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
