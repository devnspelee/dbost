package com.dbost.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.webkit.*;
import androidx.core.app.NotificationCompat;

public class OverlayService extends Service {
    private WindowManager wm;
    private WebView webView;
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

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.loadUrl("file:///android_asset/public/index.html");

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = (int)(dm.widthPixels * 0.85f);
        int h = (int)(dm.heightPixels * 0.65f);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 80;

        webView.setOnTouchListener(new DragTouchListener(wm, params, webView));
        wm.addView(webView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
            wm.removeView(webView);
        }
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
