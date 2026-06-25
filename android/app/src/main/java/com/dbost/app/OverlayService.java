package com.dbost.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.hardware.display.DisplayManager;
import android.view.Display;
import androidx.core.app.NotificationCompat;
import java.io.*;

public class OverlayService extends Service {
    private WindowManager wm;
    private LinearLayout overlayView;
    private TextView tvStats;
    private TextView tvCredit;
    private Handler handler;
    private long[] prevCpuTimes = null;
    private int fpsCount = 0;
    private int currentFps = 0;
    private long fpsLastTime = 0;
    private Choreographer.FrameCallback frameCallback;
    static final String CHANNEL_ID = "dbost_overlay";

    // Overlay size (resizable)
    private int overlayW = 420;
    private int overlayH = WindowManager.LayoutParams.WRAP_CONTENT;
    private WindowManager.LayoutParams overlayParams;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("dBost Monitor Active")
            .setContentText("devnsepele monitor running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build();
        startForeground(1, notif);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlayView();
        startFpsCounter();
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(statsUpdater, 1000);
    }

    private void buildOverlayView() {
        // Root container
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setBackgroundColor(Color.argb(225, 8, 8, 14));
        overlayView.setPadding(22, 12, 22, 12);

        // ── Header row: dBost ●  [resize]
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("dBost");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);

        TextView tvDot = new TextView(this);
        tvDot.setText("  ●");
        tvDot.setTextColor(Color.argb(255, 0, 255, 136));
        tvDot.setTextSize(10);

        // Spacer
        View spacer = new View(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
        spacer.setLayoutParams(sp);

        // Resize handle text
        TextView tvResize = new TextView(this);
        tvResize.setText("⤡");
        tvResize.setTextColor(Color.argb(120, 255, 255, 255));
        tvResize.setTextSize(16);
        tvResize.setPadding(8, 0, 0, 0);

        header.addView(tvTitle);
        header.addView(tvDot);
        header.addView(spacer);
        header.addView(tvResize);

        // ── Thin divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(60, 255, 255, 255));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dp.topMargin = 8; dp.bottomMargin = 8;

        // ── Stats line
        tvStats = new TextView(this);
        tvStats.setText("CPU 0% | RAM 0% | FPS 0 | BAT 0%");
        tvStats.setTextColor(Color.argb(230, 220, 220, 235));
        tvStats.setTextSize(12);
        tvStats.setTypeface(Typeface.MONOSPACE);

        // ── Credit line
        tvCredit = new TextView(this);
        tvCredit.setText("devnsepele monitor");
        tvCredit.setTextColor(Color.argb(90, 150, 150, 180));
        tvCredit.setTextSize(9);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = 6;
        tvCredit.setLayoutParams(cp);

        overlayView.addView(header);
        overlayView.addView(divider, dp);
        overlayView.addView(tvStats);
        overlayView.addView(tvCredit);

        // ── Window params
        overlayParams = new WindowManager.LayoutParams(
            overlayW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = 16;
        overlayParams.y = 120;

        // Drag to move
        overlayView.setOnTouchListener(new DragTouchListener(wm, overlayParams, overlayView));

        // Resize on tvResize drag
        tvResize.setOnTouchListener(new ResizeTouchListener());

        wm.addView(overlayView, overlayParams);
    }

    // ── Real FPS via Choreographer ────────────────────────────────────────────
    private void startFpsCounter() {
        fpsLastTime = System.nanoTime();
        frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                fpsCount++;
                long now = System.nanoTime();
                long diff = now - fpsLastTime;
                if (diff >= 1_000_000_000L) {
                    currentFps = (int)(fpsCount * 1_000_000_000L / diff);
                    fpsCount = 0;
                    fpsLastTime = now;
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    // ── Stats updater ─────────────────────────────────────────────────────────
    private final Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            int cpu = getCpuUsage();
            int ram = getRamUsage();
            int bat = getBattery();
            int fps = currentFps;

            String stats = String.format(
                "CPU %2d%% | RAM %2d%% | FPS %2d | BAT %2d%%",
                cpu, ram, fps, bat);
            tvStats.setText(stats);

            // Color coding
            if (cpu > 85 || ram > 88) {
                tvStats.setTextColor(Color.argb(255, 255, 68, 102));
            } else if (cpu > 65 || ram > 72) {
                tvStats.setTextColor(Color.argb(255, 255, 170, 0));
            } else {
                tvStats.setTextColor(Color.argb(230, 220, 220, 235));
            }

            handler.postDelayed(this, 1000);
        }
    };

    // ── Real CPU from /proc/stat ──────────────────────────────────────────────
    private int getCpuUsage() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String line = reader.readLine();
            reader.close();
            String[] toks = line.trim().split("\\s+");
            int off = 1;
            long idle  = Long.parseLong(toks[off + 3]);
            long total = 0;
            for (int i = off; i < Math.min(toks.length, off + 8); i++)
                total += Long.parseLong(toks[i]);
            if (prevCpuTimes != null) {
                long dIdle  = idle  - prevCpuTimes[0];
                long dTotal = total - prevCpuTimes[1];
                prevCpuTimes = new long[]{idle, total};
                return (int)(100L * (dTotal - dIdle) / Math.max(dTotal, 1));
            }
            prevCpuTimes = new long[]{idle, total};
            return 0;
        } catch (Exception e) { return 0; }
    }

    // ── Real RAM from ActivityManager ─────────────────────────────────────────
    private int getRamUsage() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem == 0) return 0;
        return (int)(100L - mi.availMem * 100L / mi.totalMem);
    }

    // ── Real Battery from BatteryManager ─────────────────────────────────────
    private int getBattery() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent status = registerReceiver(null, ifilter);
        if (status == null) return 0;
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return 0;
        return (int)(level * 100f / scale);
    }

    // ── Resize touch listener ─────────────────────────────────────────────────
    private class ResizeTouchListener implements View.OnTouchListener {
        private float startX, startY;
        private int startW;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startY = e.getRawY();
                    startW = overlayParams.width > 0 ? overlayParams.width : overlayW;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = startX - e.getRawX(); // drag left = wider (from right edge)
                    int newW = Math.max(280, Math.min(600, (int)(startW + dx)));
                    overlayParams.width = newW;
                    wm.updateViewLayout(overlayView, overlayParams);
                    return true;
            }
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacks(statsUpdater);
        if (frameCallback != null) Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (overlayView != null) wm.removeView(overlayView);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "dBost Monitor", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
