package com.dbost.app;

import android.view.*;

public class DragTouchListener implements View.OnTouchListener {
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final View view;
    private float startX, startY, initX, initY;
    private boolean isDragging = false;
    private static final float DRAG_THRESHOLD = 10f;

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
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - startX;
                float dy = e.getRawY() - startY;
                if (!isDragging) {
                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true;
                    } else {
                        return true; // belum drag, jangan gerak
                    }
                }
                params.x = (int)(initX + dx);
                params.y = (int)(initY + dy);
                wm.updateViewLayout(view, params);
                return true;

            case MotionEvent.ACTION_UP:
                if (!isDragging) {
                    // Ini tap biasa, pass ke child views
                    view.performClick();
                }
                isDragging = false;
                return true;
        }
        return false;
    }
}
