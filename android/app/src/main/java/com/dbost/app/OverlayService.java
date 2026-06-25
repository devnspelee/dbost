package com.dbost.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;

public class OverlayService extends Service {
    private WindowManager wm;
    private View overlayView;
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

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.argb(220, 10, 10, 15));
        layout.setPadding(16, 12, 16, 12);

        TextView title = new TextView(this);
        title.setText("dBost");
        title.setTextColor(Color.WHITE);
        title.setTextSize(12);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(title);

        TextView status = new TextView(this);
        status.setText("Boosting...");
        status.setTextColor(Color.argb(255, 0, 255, 136));
        status.setTextSize(10);
        layout.addView(status);

        overlayView = layout;

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
        params.y = 100;

        overlayView.setOnTouchListener(new DragTouchListener(wm, params, overlayView));
        wm.addView(overlayView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
