package com.dbost.app;

import android.view.*;

public class DragTouchListener implements View.OnTouchListener {
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final View view;
    private float startX, startY, initX, initY;
    private boolean isDragging = false;
    private static final float THRESHOLD = 12f;

    public DragTouchListener(WindowManager wm, WindowManager.LayoutParams p, View v) {
        this.wm = wm; this.params = p; this.view = v;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = e.getRawX(); startY = e.getRawY();
                initX = params.x; initY = params.y;
                isDragging = false;
                return false; // pass ke child dulu

            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - startX;
                float dy = e.getRawY() - startY;
                if (Math.abs(dx) > THRESHOLD || Math.abs(dy) > THRESHOLD) {
                    isDragging = true;
                }
                if (isDragging) {
                    params.x = (int)(initX + dx);
                    params.y = (int)(initY + dy);
                    wm.updateViewLayout(view, params);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
                isDragging = false;
                return false; // pass ke child supaya onClick jalan
        }
        return false;
    }
}
