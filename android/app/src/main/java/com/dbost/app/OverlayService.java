package com.dbost.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.util.*;

public class OverlayService extends Service {
    private WindowManager wm;
    private LinearLayout overlayView;
    private TextView tvStats;
    private Handler handler;
    private long[] prevCpu = null;
    static final String CHANNEL_ID = "dbost_overlay";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("dBost Active")
            .setContentText("Floating overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build();
        startForeground(1, notif);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Layout
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setBackgroundColor(Color.argb(210, 10, 10, 15));
        overlayView.setPadding(20, 10, 20, 10);

        // Header: dBost ●
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("dBost");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(13);
        tvTitle.setTypeface(null, Typeface.BOLD);

        TextView tvDot = new TextView(this);
        tvDot.setText("  ●");
        tvDot.setTextColor(Color.argb(255, 0, 255, 136));
        tvDot.setTextSize(10);

        header.addView(tvTitle);
        header.addView(tvDot);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(80, 255, 255, 255));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dp.topMargin = 6; dp.bottomMargin = 6;

        // Stats line
        tvStats = new TextView(this);
        tvStats.setText("CPU 0% | RAM 0% | FPS 60");
        tvStats.setTextColor(Color.argb(220, 232, 232, 240));
        tvStats.setTextSize(11);

        overlayView.addView(header);
        overlayView.addView(divider, dp);
        overlayView.addView(tvStats);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 16;
        params.y = 120;

        overlayView.setOnTouchListener(new DragTouchListener(wm, params, overlayView));
        wm.addView(overlayView, params);

        // Start updating stats
        handler = new Handler(Looper.getMainLooper());
        handler.post(statsUpdater);
    }

    private int fpsEst = 60;
    private long lastFrame = System.currentTimeMillis();
    private int frameCount = 0;

    private final Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            int cpu = getCpuUsage();
            int ram = getRamUsage();

            // FPS estimate based on update frequency
            frameCount++;
            long now = System.currentTimeMillis();
            if (now - lastFrame >= 1000) {
                fpsEst = Math.min(60, frameCount * 2);
                frameCount = 0;
                lastFrame = now;
            }

            String cpuColor = cpu > 80 ? "high" : "ok";
            tvStats.setText("CPU " + cpu + "% | RAM " + ram + "% | FPS " + fpsEst);

            // Color based on load
            if (cpu > 80 || ram > 85) {
                tvStats.setTextColor(Color.argb(255, 255, 68, 102));
            } else if (cpu > 60 || ram > 70) {
                tvStats.setTextColor(Color.argb(255, 255, 170, 0));
            } else {
                tvStats.setTextColor(Color.argb(220, 232, 232, 240));
            }

            handler.postDelayed(this, 1500);
        }
    };

    private int getCpuUsage() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String load = reader.readLine();
            reader.close();
            String[] toks = load.split(" ");
            int offset = toks[1].isEmpty() ? 2 : 1;
            long idle1 = Long.parseLong(toks[offset + 3]);
            long total1 = 0;
            for (int i = offset; i < offset + 7; i++) total1 += Long.parseLong(toks[i]);
            if (prevCpu != null) {
                long diffIdle = idle1 - prevCpu[0];
                long diffTotal = total1 - prevCpu[1];
                prevCpu = new long[]{idle1, total1};
                return (int)(100 * (diffTotal - diffIdle) / Math.max(diffTotal, 1));
            }
            prevCpu = new long[]{idle1, total1};
            return 0;
        } catch (Exception e) { return 0; }
    }

    private int getRamUsage() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return (int)(100 - (mi.availMem * 100 / mi.totalMem));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacks(statsUpdater);
        if (overlayView != null) wm.removeView(overlayView);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "dBost Overlay", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
