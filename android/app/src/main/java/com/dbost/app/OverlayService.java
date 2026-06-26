package com.dbost.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.*;
import android.os.*;
import android.telephony.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * OverlayService — dBost v4.0
 * Fitur:
 *  - Tampilan baru: header gradient + glow border
 *  - Mode Compact (hanya nilai) / Expanded (bar + detail)
 *  - Tombol SIZE: kecil → sedang → besar (3 langkah)
 *  - Tombol ALPHA: transparan bertahap (5 level 30%→100%)
 *  - Tombol SMOOTH: kurangi animasi sistem (real Settings.Global)
 *  - Tombol LOCK: kunci posisi floating (nonaktif drag)
 *  - Ping real ke 8.8.8.8
 *  - Thermal: baca /sys/class/thermal/thermal_zone0/temp
 *  - CPU & RAM real dari /proc/stat + ActivityManager
 *  - FPS real via Choreographer
 *  - Battery real via BatteryManager
 *  - Warna dinamis (hijau→kuning→merah) per metrik
 */
public class OverlayService extends Service {

    // ── Window / view ─────────────────────────────────────────────────────────
    private WindowManager wm;
    private LinearLayout overlayView;
    private WindowManager.LayoutParams overlayParams;
    private DragTouchListener dragListener;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean smoothOn    = false;
    private boolean posLocked   = false;
    private boolean compactMode = false;

    // Size cycle: 0=small, 1=medium, 2=large
    private int sizeLevel = 1;
    private static final int[] SIZE_DP = { 200, 270, 340 };

    // Alpha cycle: 0..4 → 30%, 50%, 65%, 80%, 100%
    private int alphaLevel = 4;
    private static final int[] ALPHA_VAL = { 77, 128, 166, 204, 255 };

    // ── Metrics ───────────────────────────────────────────────────────────────
    private Handler handler;
    private long[] prevCpuTimes = null;
    private int currentFps = 0, fpsCount = 0;
    private long fpsLastTime = 0;
    private Choreographer.FrameCallback frameCallback;
    private int lastPing = -1;

    // ── Value TextViews ───────────────────────────────────────────────────────
    private TextView tvCpuVal, tvRamVal, tvFpsVal, tvBatVal, tvPingVal, tvTempVal;

    // ── Bar views ─────────────────────────────────────────────────────────────
    private View cpuBar, ramBar, fpsBar, batBar;

    // ── Button refs ───────────────────────────────────────────────────────────
    private TextView btnSmooth, btnSize, btnAlpha, btnLock, btnMode;

    // ── Expanded section ──────────────────────────────────────────────────────
    private LinearLayout expandedSection;

    // ── Accent color (purple) ─────────────────────────────────────────────────
    private static final int C_ACCENT  = Color.argb(255, 108,  92, 231);
    private static final int C_CYAN    = Color.argb(255,   0, 206, 201);
    private static final int C_GREEN   = Color.argb(255,   0, 184, 148);
    private static final int C_AMBER   = Color.argb(255, 253, 203, 110);
    private static final int C_RED     = Color.argb(255, 232,  67, 147);
    private static final int C_PURPLE  = Color.argb(255, 162, 155, 254);
    private static final int C_TEXT    = Color.argb(220, 220, 225, 235);
    private static final int C_MUTED   = Color.argb(100, 180, 180, 200);
    private static final int C_BG      = Color.argb(245,   7,   7,  13);
    private static final int C_CARD    = Color.argb(255,  19,  19,  31);
    private static final int C_BORDER  = Color.argb(70,  108,  92, 231);
    private static final int C_DIM     = Color.argb(45,  255, 255, 255);

    static final String CHANNEL_ID = "dbost_overlay";

    // ═════════════════════════════════════════════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("dBost Monitor")
            .setContentText("devnsepele overlay active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        startForeground(1, notif);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlay();
        startFpsCounter();
        startPingWorker();

        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(statsUpdater, 600);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BUILD OVERLAY
    // ═════════════════════════════════════════════════════════════════════════
    private void buildOverlay() {

        // ── Root container ────────────────────────────────────────────────────
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setPadding(dp(12), dp(10), dp(12), dp(11));
        applyRootBackground();

        // ── HEADER ROW ────────────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(24));
        hp.bottomMargin = dp(8);
        header.setLayoutParams(hp);

        // Logo pill
        TextView tvLogo = new TextView(this);
        tvLogo.setText("dB");
        tvLogo.setTextColor(Color.WHITE);
        tvLogo.setTextSize(9f);
        tvLogo.setTypeface(Typeface.DEFAULT_BOLD);
        tvLogo.setGravity(Gravity.CENTER);
        tvLogo.setPadding(dp(7), dp(2), dp(7), dp(2));
        tvLogo.setBackground(makeGradientPill());

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText("  dBost");
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(11f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setLetterSpacing(0.03f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvTitle.setLayoutParams(tp);

        // Spacer
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        // ── Compact/Expanded mode toggle ──────────────────────────────────────
        btnMode = makeHeaderBtn("EXP");
        btnMode.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) toggleMode();
            return true;
        });

        // ── Lock position button ───────────────────────────────────────────────
        btnLock = makeHeaderBtn("FREE");
        btnLock.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) toggleLock();
            return true;
        });

        header.addView(tvLogo);
        header.addView(tvTitle);
        header.addView(sp);
        header.addView(btnMode);
        addSmallGap(header, 5);
        header.addView(btnLock);

        // ── DIVIDER ───────────────────────────────────────────────────────────
        View div = makeDivider();
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dvp.bottomMargin = dp(9);
        div.setLayoutParams(dvp);

        // ── STATS ROW (always visible) ────────────────────────────────────────
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);
        statsRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout colCpu  = makeStatCol("CPU",  C_CYAN);
        LinearLayout colRam  = makeStatCol("RAM",  C_ACCENT);
        LinearLayout colFps  = makeStatCol("FPS",  C_GREEN);
        LinearLayout colBat  = makeStatCol("BAT",  C_AMBER);

        tvCpuVal  = (TextView) colCpu.getChildAt(1);
        tvRamVal  = (TextView) colRam.getChildAt(1);
        tvFpsVal  = (TextView) colFps.getChildAt(1);
        tvBatVal  = (TextView) colBat.getChildAt(1);

        statsRow.addView(colCpu);
        statsRow.addView(makeVDiv());
        statsRow.addView(colRam);
        statsRow.addView(makeVDiv());
        statsRow.addView(colFps);
        statsRow.addView(makeVDiv());
        statsRow.addView(colBat);

        // ── EXPANDED SECTION ──────────────────────────────────────────────────
        expandedSection = new LinearLayout(this);
        expandedSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams exp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        exp.topMargin = dp(10);
        expandedSection.setLayoutParams(exp);

        // Progress bars
        cpuBar = new View(this); cpuBar.setBackgroundColor(C_CYAN);
        ramBar = new View(this); ramBar.setBackgroundColor(C_ACCENT);
        fpsBar = new View(this); fpsBar.setBackgroundColor(C_GREEN);
        batBar = new View(this); batBar.setBackgroundColor(C_AMBER);
        expandedSection.addView(makeBarRow(cpuBar, "CPU", C_CYAN));
        expandedSection.addView(makeBarRow(ramBar, "RAM", C_ACCENT));
        expandedSection.addView(makeBarRow(fpsBar, "FPS", C_GREEN));
        expandedSection.addView(makeBarRow(batBar, "BAT", C_AMBER));

        // Divider
        View div2 = makeDivider();
        LinearLayout.LayoutParams dv2p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dv2p.topMargin = dp(8); dv2p.bottomMargin = dp(8);
        div2.setLayoutParams(dv2p);
        expandedSection.addView(div2);

        // Extra row: PING + TEMP
        LinearLayout extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        extraRow.setGravity(Gravity.CENTER_VERTICAL);
        extraRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout colPing = makeStatCol("PING", C_PURPLE);
        LinearLayout colTemp = makeStatCol("TEMP", C_RED);
        tvPingVal = (TextView) colPing.getChildAt(1);
        tvTempVal = (TextView) colTemp.getChildAt(1);
        tvPingVal.setTextSize(12f);
        tvTempVal.setTextSize(12f);

        extraRow.addView(colPing);
        extraRow.addView(makeVDiv());
        extraRow.addView(colTemp);
        expandedSection.addView(extraRow);

        // Divider
        View div3 = makeDivider();
        LinearLayout.LayoutParams dv3p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dv3p.topMargin = dp(8); dv3p.bottomMargin = dp(8);
        div3.setLayoutParams(dv3p);
        expandedSection.addView(div3);

        // ── CONTROL BUTTONS ROW ───────────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnSmooth = makeCtrlBtn("SMOOTH");
        btnSmooth.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) toggleSmooth();
            return true;
        });

        btnSize = makeCtrlBtn("SIZE");
        btnSize.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) cycleSize();
            return true;
        });

        btnAlpha = makeCtrlBtn("ALPHA");
        btnAlpha.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) cycleAlpha();
            return true;
        });

        btnRow.addView(btnSmooth);
        addSmallGap(btnRow, 6);
        btnRow.addView(btnSize);
        addSmallGap(btnRow, 6);
        btnRow.addView(btnAlpha);
        expandedSection.addView(btnRow);

        // ── Credit ────────────────────────────────────────────────────────────
        TextView tvCredit = new TextView(this);
        tvCredit.setText("devnsepele monitor");
        tvCredit.setTextColor(Color.argb(50, 162, 155, 254));
        tvCredit.setTextSize(7f);
        tvCredit.setLetterSpacing(0.1f);
        tvCredit.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crp.topMargin = dp(8);
        tvCredit.setLayoutParams(crp);
        expandedSection.addView(tvCredit);

        // ── Assemble root ─────────────────────────────────────────────────────
        overlayView.addView(header);
        overlayView.addView(div, dvp);
        overlayView.addView(statsRow);
        overlayView.addView(expandedSection);

        // ── Window params ─────────────────────────────────────────────────────
        overlayParams = new WindowManager.LayoutParams(
            dp(SIZE_DP[sizeLevel]),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(12);
        overlayParams.y = dp(80);
        overlayParams.alpha = 1.0f;

        // ── Drag ──────────────────────────────────────────────────────────────
        dragListener = new DragTouchListener(wm, overlayParams, overlayView);
        overlayView.setOnTouchListener((v, e) -> {
            if (posLocked) return false;
            dragListener.onTouch(v, e);
            return dragListener.isDragging;
        });

        wm.addView(overlayView, overlayParams);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FEATURE TOGGLES
    // ═════════════════════════════════════════════════════════════════════════

    /** Toggle compact (nilai saja) vs expanded (bar + ping + temp + tombol) */
    private void toggleMode() {
        compactMode = !compactMode;
        expandedSection.setVisibility(compactMode ? View.GONE : View.VISIBLE);
        updateHeaderBtn(btnMode, compactMode ? "EXP" : "MIN", compactMode);
        wm.updateViewLayout(overlayView, overlayParams);
    }

    /** Lock / unlock posisi agar tidak tergeser saat main */
    private void toggleLock() {
        posLocked = !posLocked;
        updateHeaderBtn(btnLock, posLocked ? "LOCK" : "FREE", posLocked);
    }

    /** Kurangi animasi sistem secara real (butuh WRITE_SETTINGS) */
    private void toggleSmooth() {
        smoothOn = !smoothOn;
        try {
            float s = smoothOn ? 0.5f : 1.0f;
            android.provider.Settings.Global.putFloat(getContentResolver(),
                android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, s);
            android.provider.Settings.Global.putFloat(getContentResolver(),
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, s);
            android.provider.Settings.Global.putFloat(getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, s);
        } catch (Exception ignored) {}
        refreshCtrlBtn(btnSmooth, "SMOOTH", smoothOn);
    }

    /** Cycle ukuran overlay: kecil → sedang → besar → kecil */
    private void cycleSize() {
        sizeLevel = (sizeLevel + 1) % 3;
        overlayParams.width = dp(SIZE_DP[sizeLevel]);
        wm.updateViewLayout(overlayView, overlayParams);
        String[] labels = { "SM", "MD", "LG" };
        refreshCtrlBtn(btnSize, "SIZE:" + labels[sizeLevel], sizeLevel > 0);
    }

    /** Cycle transparansi: 100% → 80% → 65% → 50% → 30% → 100% */
    private void cycleAlpha() {
        alphaLevel = (alphaLevel + 1) % ALPHA_VAL.length;
        overlayParams.alpha = ALPHA_VAL[alphaLevel] / 255f;
        wm.updateViewLayout(overlayView, overlayParams);
        int pct = Math.round(ALPHA_VAL[alphaLevel] / 255f * 100);
        refreshCtrlBtn(btnAlpha, "α " + pct + "%", alphaLevel < 4);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STATS UPDATER
    // ═════════════════════════════════════════════════════════════════════════
    private final Runnable statsUpdater = new Runnable() {
        @Override public void run() {
            int cpu  = getCpuUsage();
            int ram  = getRamUsage();
            int fps  = Math.min(currentFps, 120);
            int bat  = getBattery();
            int temp = getThermal();

            // Dynamic colors
            int cpuC = cpu > 85 ? C_RED : cpu > 65 ? C_AMBER : C_CYAN;
            int ramC = ram > 88 ? C_RED : ram > 72 ? C_AMBER : C_ACCENT;
            int fpsC = fps < 30 ? C_RED : fps < 50 ? C_AMBER : C_GREEN;
            int batC = bat < 15 ? C_RED : bat < 30 ? C_AMBER : C_AMBER;
            int pingC = lastPing < 0 ? C_MUTED :
                        lastPing < 50 ? C_GREEN :
                        lastPing < 120 ? C_AMBER : C_RED;
            int tempC = temp > 50 ? C_RED : temp > 40 ? C_AMBER : C_GREEN;

            tvCpuVal.setText(cpu + "%");   tvCpuVal.setTextColor(cpuC);
            tvRamVal.setText(ram + "%");   tvRamVal.setTextColor(ramC);
            tvFpsVal.setText(String.valueOf(fps)); tvFpsVal.setTextColor(fpsC);
            tvBatVal.setText(bat + "%");   tvBatVal.setTextColor(batC);
            tvPingVal.setText(lastPing < 0 ? "--" : lastPing + "ms");
            tvPingVal.setTextColor(pingC);
            tvTempVal.setText(temp < 0 ? "--" : temp + "°");
            tvTempVal.setTextColor(tempC);

            // Bar colors update
            cpuBar.setBackgroundColor(cpuC);
            ramBar.setBackgroundColor(ramC);
            fpsBar.setBackgroundColor(fpsC);
            batBar.setBackgroundColor(batC);

            // Update bar widths
            updateBar(cpuBar, cpu);
            updateBar(ramBar, ram);
            updateBar(fpsBar, (int)(fps / 1.2f));
            updateBar(batBar, bat);

            handler.postDelayed(this, 1000);
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // REAL METRICS
    // ═════════════════════════════════════════════════════════════════════════

    private int getCpuUsage() {
        try {
            RandomAccessFile r = new RandomAccessFile("/proc/stat", "r");
            String line = r.readLine(); r.close();
            String[] t = line.trim().split("\\s+");
            long idle = Long.parseLong(t[4]), total = 0;
            for (int i = 1; i < Math.min(t.length, 9); i++) total += Long.parseLong(t[i]);
            if (prevCpuTimes != null) {
                long dI = idle - prevCpuTimes[0], dT = total - prevCpuTimes[1];
                prevCpuTimes = new long[]{ idle, total };
                return (int)(100L * (dT - dI) / Math.max(dT, 1));
            }
            prevCpuTimes = new long[]{ idle, total };
        } catch (Exception ignored) {}
        return 0;
    }

    private int getRamUsage() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem == 0 ? 0 : (int)(100L - mi.availMem * 100L / mi.totalMem);
    }

    private int getBattery() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return 0;
        int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return (sc > 0 && lv >= 0) ? (int)(lv * 100f / sc) : 0;
    }

    /** Baca suhu CPU dari thermal zone 0 (°C) */
    private int getThermal() {
        try {
            RandomAccessFile r = new RandomAccessFile("/sys/class/thermal/thermal_zone0/temp", "r");
            String s = r.readLine(); r.close();
            int raw = Integer.parseInt(s.trim());
            return raw > 1000 ? raw / 1000 : raw; // bisa milicelsius
        } catch (Exception ignored) {}
        return -1;
    }

    /** FPS real via Choreographer */
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

    /** Ping real ke 8.8.8.8 di background thread */
    private void startPingWorker() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long t0 = System.currentTimeMillis();
                    InetAddress.getByName("8.8.8.8").isReachable(800);
                    lastPing = (int)(System.currentTimeMillis() - t0);
                } catch (Exception e) { lastPing = -1; }
                try { Thread.sleep(4000); } catch (InterruptedException ex) { break; }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private void applyRootBackground() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(C_BG);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), C_BORDER);
        overlayView.setBackground(gd);
    }

    /** Gradient pill untuk logo */
    private GradientDrawable makeGradientPill() {
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{ C_ACCENT, Color.argb(255, 162, 155, 254) });
        gd.setCornerRadius(dp(8));
        return gd;
    }

    /** Tombol kecil di header (MODE, LOCK) */
    private TextView makeHeaderBtn(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_MUTED);
        tv.setTextSize(7.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.07f);
        tv.setPadding(dp(7), dp(3), dp(7), dp(3));
        tv.setBackground(roundRectStroke(C_CARD, dp(5), dp(1), Color.argb(50, 200, 200, 220)));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void updateHeaderBtn(TextView btn, String text, boolean active) {
        btn.setText(text);
        if (active) {
            btn.setTextColor(C_ACCENT);
            btn.setBackground(roundRectStroke(
                Color.argb(40, 108, 92, 231), dp(5), dp(1), Color.argb(120, 108, 92, 231)));
        } else {
            btn.setTextColor(C_MUTED);
            btn.setBackground(roundRectStroke(C_CARD, dp(5), dp(1), Color.argb(50, 200, 200, 220)));
        }
    }

    /** Tombol kontrol di baris bawah (SMOOTH, SIZE, ALPHA) */
    private TextView makeCtrlBtn(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_MUTED);
        tv.setTextSize(7.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.06f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(0), dp(5), dp(0), dp(5));
        tv.setBackground(roundRectStroke(C_CARD, dp(6), dp(1), Color.argb(45, 200, 200, 220)));
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void refreshCtrlBtn(TextView btn, String text, boolean active) {
        btn.setText(text);
        if (active) {
            btn.setTextColor(C_GREEN);
            btn.setBackground(roundRectStroke(
                Color.argb(35, 0, 184, 148), dp(6), dp(1), Color.argb(100, 0, 184, 148)));
        } else {
            btn.setTextColor(C_MUTED);
            btn.setBackground(roundRectStroke(C_CARD, dp(6), dp(1), Color.argb(45, 200, 200, 220)));
        }
    }

    private LinearLayout makeStatCol(String label, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(C_MUTED);
        lbl.setTextSize(7f);
        lbl.setGravity(Gravity.CENTER);
        lbl.setLetterSpacing(0.12f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);

        TextView val = new TextView(this);
        val.setText("--");
        val.setTextColor(color);
        val.setTextSize(15f);
        val.setTypeface(Typeface.MONOSPACE);
        val.setGravity(Gravity.CENTER);
        val.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        col.addView(lbl);
        col.addView(val);
        return col;
    }

    private View makeVDiv() {
        View sep = new View(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(1), dp(30));
        p.leftMargin = dp(2); p.rightMargin = dp(2);
        sep.setBackgroundColor(C_DIM);
        sep.setLayoutParams(p);
        return sep;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIM);
        return v;
    }

    private LinearLayout makeBarRow(View bar, String label, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(16));
        rp.bottomMargin = dp(3);
        row.setLayoutParams(rp);

        // Label
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(C_MUTED);
        lbl.setTextSize(7f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24),
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lbl.setLayoutParams(lp);

        // Track background
        LinearLayout track = new LinearLayout(this);
        track.setLayoutParams(new LinearLayout.LayoutParams(0, dp(5), 1f));
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(Color.argb(35, 255, 255, 255));
        trackBg.setCornerRadius(dp(3));
        track.setBackground(trackBg);

        // Bar fill with rounded ends
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(color);
        barBg.setCornerRadius(dp(3));
        bar.setBackground(barBg);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(5)));
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

    private void addSmallGap(LinearLayout parent, int sizeDp) {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), 1));
        parent.addView(gap);
    }

    private GradientDrawable roundRectStroke(int color, int radius, int strokeW, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        gd.setStroke(strokeW, strokeColor);
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ═════════════════════════════════════════════════════════════════════════
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacks(statsUpdater);
        if (frameCallback != null) Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (overlayView != null && overlayView.isAttachedToWindow())
            wm.removeView(overlayView);
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
