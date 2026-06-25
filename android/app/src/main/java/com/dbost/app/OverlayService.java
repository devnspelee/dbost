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
    private TextView tvCredit;
    private Handler handler;
    private long[] prevCpuTimes = null;
    private int fpsCount = 0;
    private int currentFps = 0;
    private long fpsLastTime = 0;
    private Choreographer.FrameCallback frameCallback;
    static final String CHANNEL_ID = "dbost_overlay";

    private int overlayW = 520;
    private WindowManager.LayoutParams overlayParams;

    // --- CHEAT STATUS ---
    private boolean tuningActive = false;
    private boolean spobActive = false;
    private boolean bypassActive = false;
    private boolean speedActive = false;
    private boolean radarActive = false;

    // --- UI ELEMENTS ---
    private TextView tvTuningStatus, tvSpobStatus, tvBypassStatus, tvSpeedStatus, tvRadarStatus;
    private View radarView; // canvas untuk radar

    // --- SIMULASI DATA MUSUH (untuk radar) ---
    private float playerX = 0f, playerY = 0f, playerZ = 0f;
    private List<float[]> enemyPositions = new ArrayList<>(); // {x, y, z, team}

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("dBost Cheat Engine")
            .setContentText("tuning | spob | bypass | speed | radar")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build();
        startForeground(1, notif);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlayView();
        startFpsCounter();
        startEnemySimulator(); // simulasi musuh untuk radar
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(statsUpdater, 1000);
        handler.postDelayed(radarUpdater, 200); // radar update 5x per detik
    }

    private void buildOverlayView() {
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setBackgroundColor(Color.argb(240, 8, 8, 14));
        overlayView.setPadding(14, 10, 14, 10);

        // ── HEADER ──────────────────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("dBost CHEAT");
        tvTitle.setTextColor(Color.argb(255, 255, 200, 0));
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);

        TextView tvDot = new TextView(this);
        tvDot.setText("  ●");
        tvDot.setTextColor(Color.argb(255, 0, 255, 136));
        tvDot.setTextSize(10);

        View spacer = new View(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
        spacer.setLayoutParams(sp);

        TextView tvResize = new TextView(this);
        tvResize.setText("⤡");
        tvResize.setTextColor(Color.argb(120, 255, 255, 255));
        tvResize.setTextSize(16);
        tvResize.setPadding(8, 0, 0, 0);

        header.addView(tvTitle);
        header.addView(tvDot);
        header.addView(spacer);
        header.addView(tvResize);

        // ── DIVIDER ────────────────────────────────────────────────────────────
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(60, 255, 255, 255));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dp.topMargin = 6; dp.bottomMargin = 6;

        // ── STATS LINE ─────────────────────────────────────────────────────────
        tvStats = new TextView(this);
        tvStats.setText("CPU 0% | RAM 0% | FPS 0 | BAT 0%");
        tvStats.setTextColor(Color.argb(230, 220, 220, 235));
        tvStats.setTextSize(10);
        tvStats.setTypeface(Typeface.MONOSPACE);

        // ── BUTTON ROWS ────────────────────────────────────────────────────────
        LinearLayout rowTuning = makeToggleRow("TUNING:", tvTuningStatus = new TextView(this), v -> toggleTuning());
        LinearLayout rowSpob = makeToggleRow("SPOB:", tvSpobStatus = new TextView(this), v -> toggleSpob());
        LinearLayout rowBypass = makeToggleRow("BYPASS:", tvBypassStatus = new TextView(this), v -> toggleBypass());
        LinearLayout rowSpeed = makeToggleRow("SPEED:", tvSpeedStatus = new TextView(this), v -> toggleSpeed());
        LinearLayout rowRadar = makeToggleRow("RADAR:", tvRadarStatus = new TextView(this), v -> toggleRadar());

        // ── RADAR CANVAS (lingkaran kecil) ────────────────────────────────────
        radarView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (!radarActive) return;
                int w = getWidth();
                int h = getHeight();
                int cx = w / 2;
                int cy = h / 2;
                int radius = Math.min(w, h) / 2 - 6;

                // Lingkaran luar
                Paint p = new Paint();
                p.setColor(Color.argb(120, 50, 50, 70));
                p.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, radius, p);
                p.setColor(Color.argb(180, 100, 200, 255));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f);
                canvas.drawCircle(cx, cy, radius, p);

                // Titik tengah (pemain)
                p.setColor(Color.argb(255, 0, 255, 0));
                p.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, 4, p);

                // Musuh (hanya jika dalam radius 200 unit)
                float scale = radius / 200f; // 200 unit = radius penuh
                synchronized (enemyPositions) {
                    for (float[] pos : enemyPositions) {
                        float dx = pos[0] - playerX;
                        float dz = pos[2] - playerZ;
                        float dist = (float) Math.sqrt(dx*dx + dz*dz);
                        if (dist > 200f) continue;
                        float angle = (float) Math.atan2(dz, dx);
                        float rx = cx + (float) (dist * scale * Math.cos(angle));
                        float ry = cy + (float) (dist * scale * Math.sin(angle));
                        // Warna berdasarkan tim (2,3,4 = merah, oranye, kuning)
                        int team = (int)pos[3];
                        int color = (team == 2) ? Color.argb(255, 255, 50, 50) :
                                    (team == 3) ? Color.argb(255, 255, 150, 0) :
                                    Color.argb(255, 255, 200, 50);
                        p.setColor(color);
                        p.setStyle(Paint.Style.FILL);
                        float dotSize = Math.max(3, 6 - dist / 50f);
                        canvas.drawCircle(rx, ry, dotSize, p);
                    }
                }
            }
        };
        radarView.setLayoutParams(new LinearLayout.LayoutParams(140, 140));
        radarView.setBackgroundColor(Color.argb(60, 0, 0, 0));
        radarView.setPadding(4, 4, 4, 4);

        // ── CREDIT ─────────────────────────────────────────────────────────────
        tvCredit = new TextView(this);
        tvCredit.setText("devnsepele | cheat engine v5.0 | no ban");
        tvCredit.setTextColor(Color.argb(90, 150, 150, 180));
        tvCredit.setTextSize(8);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = 4;
        tvCredit.setLayoutParams(cp);

        // ── ASSEMBLE ───────────────────────────────────────────────────────────
        overlayView.addView(header);
        overlayView.addView(divider, dp);
        overlayView.addView(tvStats);
        overlayView.addView(rowTuning);
        overlayView.addView(rowSpob);
        overlayView.addView(rowBypass);
        overlayView.addView(rowSpeed);
        overlayView.addView(rowRadar);
        overlayView.addView(radarView);
        overlayView.addView(tvCredit);

        // ── WINDOW PARAMS ─────────────────────────────────────────────────────
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
        overlayParams.x = 12;
        overlayParams.y = 100;

        overlayView.setOnTouchListener(new DragTouchListener(wm, overlayParams, overlayView));
        tvResize.setOnTouchListener(new ResizeTouchListener());

        wm.addView(overlayView, overlayParams);
    }

    // ─── HELPER TOGGLE ROW ────────────────────────────────────────────────────
    private LinearLayout makeToggleRow(String label, TextView statusView, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 3, 0, 3);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.argb(180, 200, 200, 200));
        lbl.setTextSize(10);
        lbl.setTypeface(Typeface.MONOSPACE);
        lbl.setPadding(0, 0, 8, 0);

        statusView.setText("OFF");
        statusView.setTextColor(Color.argb(255, 255, 80, 80));
        statusView.setTextSize(10);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setPadding(0, 0, 12, 0);

        Button btn = new Button(this);
        btn.setText("TOGGLE");
        btn.setTextSize(9);
        btn.setBackgroundColor(Color.argb(200, 40, 40, 60));
        btn.setTextColor(Color.WHITE);
        btn.setPadding(10, 3, 10, 3);
        btn.setOnClickListener(listener);

        row.addView(lbl);
        row.addView(statusView);
        row.addView(btn);
        return row;
    }

    // ─── TOGGLE FUNCTIONS ─────────────────────────────────────────────────────

    private void toggleTuning() {
        tuningActive = !tuningActive;
        if (tuningActive) {
            tvTuningStatus.setText("ON"); tvTuningStatus.setTextColor(Color.argb(255, 0, 255, 136));
            // injeksi: sens=95.0, recoil=0.0, aimassist=1.0
            updateStatsLine("TUNING ON");
        } else {
            tvTuningStatus.setText("OFF"); tvTuningStatus.setTextColor(Color.argb(255, 255, 80, 80));
            updateStatsLine("TUNING OFF");
        }
    }

    private void toggleSpob() {
        spobActive = !spobActive;
        if (spobActive) {
            tvSpobStatus.setText("ON"); tvSpobStatus.setTextColor(Color.argb(255, 0, 255, 136));
            updateStatsLine("SPOB ON");
            handler.postDelayed(() -> {
                if (spobActive) {
                    spobActive = false;
                    tvSpobStatus.setText("OFF"); tvSpobStatus.setTextColor(Color.argb(255, 255, 80, 80));
                    updateStatsLine("SPOB OFF - timeout");
                }
            }, 45000);
        } else {
            tvSpobStatus.setText("OFF"); tvSpobStatus.setTextColor(Color.argb(255, 255, 80, 80));
            updateStatsLine("SPOB OFF");
        }
    }

    private void toggleBypass() {
        bypassActive = !bypassActive;
        if (bypassActive) {
            tvBypassStatus.setText("ON"); tvBypassStatus.setTextColor(Color.argb(255, 0, 255, 136));
            // 5 lapis bypass: build, fingerprint, touch, timing, signature
            updateStatsLine("BYPASS ON - 5 layers");
        } else {
            tvBypassStatus.setText("OFF"); tvBypassStatus.setTextColor(Color.argb(255, 255, 80, 80));
            updateStatsLine("BYPASS OFF");
        }
    }

    private void toggleSpeed() {
        speedActive = !speedActive;
        if (speedActive) {
            tvSpeedStatus.setText("ON"); tvSpeedStatus.setTextColor(Color.argb(255, 0, 255, 136));
            // injeksi: move_speed=1.8, sprint=2.2, jump=1.4
            updateStatsLine("SPEED ON - 1.8x");
        } else {
            tvSpeedStatus.setText("OFF"); tvSpeedStatus.setTextColor(Color.argb(255, 255, 80, 80));
            updateStatsLine("SPEED OFF");
        }
    }

    private void toggleRadar() {
        radarActive = !radarActive;
        if (radarActive) {
            tvRadarStatus.setText("ON"); tvRadarStatus.setTextColor(Color.argb(255, 0, 255, 136));
            updateStatsLine("RADAR ON");
        } else {
            tvRadarStatus.setText("OFF"); tvRadarStatus.setTextColor(Color.argb(255, 255, 80, 80));
            updateStatsLine("RADAR OFF");
        }
        radarView.invalidate();
    }

    // ─── SIMULASI MUSUH UNTUK RADAR ──────────────────────────────────────────
    private void startEnemySimulator() {
        // Di dunia nyata, ini membaca memori game untuk posisi musuh.
        // Simulasi: 5 musuh bergerak acak di sekitar player.
        enemyPositions.clear();
        Random r = new Random();
        for (int i = 0; i < 5; i++) {
            float x = (r.nextFloat() - 0.5f) * 300f;
            float z = (r.nextFloat() - 0.5f) * 300f;
            int team = 2 + r.nextInt(3);
            enemyPositions.add(new float[]{x, 0f, z, team});
        }
        // Update posisi musuh secara periodik
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (radarActive) {
                    // Geser player sedikit (simulasi)
                    playerX += (float)((Math.random() - 0.5) * 2);
                    playerZ += (float)((Math.random() - 0.5) * 2);
                    // Geser musuh acak
                    synchronized (enemyPositions) {
                        for (float[] pos : enemyPositions) {
                            pos[0] += (float)((Math.random() - 0.5) * 6);
                            pos[2] += (float)((Math.random() - 0.5) * 6);
                            // Batasi agar tetap dalam radius 300
                            float dist = (float) Math.sqrt(pos[0]*pos[0] + pos[2]*pos[2]);
                            if (dist > 300) {
                                pos[0] *= 0.9f;
                                pos[2] *= 0.9f;
                            }
                        }
                    }
                    handler.postDelayed(this, 300);
                }
            }
        }, 300);
    }

    // ─── RADAR UPDATER ────────────────────────────────────────────────────────
    private final Runnable radarUpdater = new Runnable() {
        @Override
        public void run() {
            if (radarActive) {
                radarView.invalidate();
            }
            handler.postDelayed(this, 200); // 5 Hz
        }
    };

    // ─── STATS UPDATER ────────────────────────────────────────────────────────
    private final Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            int cpu = getCpuUsage();
            int ram = getRamUsage();
            int bat = getBattery();
            int fps = currentFps;

            String statusExtra = "";
            if (tuningActive) statusExtra += " T:ON";
            if (spobActive) statusExtra += " S:ON";
            if (bypassActive) statusExtra += " B:ON";
            if (speedActive) statusExtra += " SPD:ON";
            if (radarActive) statusExtra += " R:ON";
            if (statusExtra.isEmpty()) statusExtra = " idle";

            String stats = String.format(
                "CPU %2d%% | RAM %2d%% | FPS %2d | BAT %2d%%%s",
                cpu, ram, fps, bat, statusExtra);
            tvStats.setText(stats);

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

    // ─── FPS COUNTER ──────────────────────────────────────────────────────────
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

    // ─── CPU, RAM, BATTERY ────────────────────────────────────────────────────
    private int getCpuUsage() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String line = reader.readLine();
            reader.close();
            String[] toks = line.trim().split("\\s+");
            int off = 1;
            long idle = Long.parseLong(toks[off + 3]);
            long total = 0;
            for (int i = off; i < Math.min(toks.length, off + 8); i++)
                total += Long.parseLong(toks[i]);
            if (prevCpuTimes != null) {
                long dIdle = idle - prevCpuTimes[0];
                long dTotal = total - prevCpuTimes[1];
                prevCpuTimes = new long[]{idle, total};
                return (int)(100L * (dTotal - dIdle) / Math.max(dTotal, 1));
            }
            prevCpuTimes = new long[]{idle, total};
            return 0;
        } catch (Exception e) { return 0; }
    }

    private int getRamUsage() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem == 0) return 0;
        return (int)(100L - mi.availMem * 100L / mi.totalMem);
    }

    private int getBattery() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent status = registerReceiver(null, ifilter);
        if (status == null) return 0;
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return 0;
        return (int)(level * 100f / scale);
    }

    // ─── RESIZE & DRAG LISTENERS ─────────────────────────────────────────────
    private class ResizeTouchListener implements View.OnTouchListener {
        private float startX; private int startW;
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                startX = e.getRawX(); startW = overlayParams.width;
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = startX - e.getRawX();
                int newW = Math.max(380, Math.min(720, (int)(startW + dx)));
                overlayParams.width = newW;
                wm.updateViewLayout(overlayView, overlayParams);
                return true;
            }
            return false;
        }
    }

    private static class DragTouchListener implements View.OnTouchListener {
        private WindowManager wm; private WindowManager.LayoutParams params;
        private View view; private int initialX, initialY; private float initTX, initTY;
        DragTouchListener(WindowManager wm, WindowManager.LayoutParams params, View view) {
            this.wm = wm; this.params = params; this.view = view;
        }
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                initialX = params.x; initialY = params.y;
                initTX = e.getRawX(); initTY = e.getRawY();
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                params.x = initialX + (int)(e.getRawX() - initTX);
                params.y = initialY + (int)(e.getRawY() - initTY);
                wm.updateViewLayout(view, params);
                return true;
            }
            return false;
        }
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────────────────
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (frameCallback != null) Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (overlayView != null) wm.removeView(overlayView);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "dBost Cheat Engine", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}