package com.dbost.app;

import android.view.*;

public class DragTouchListener implements View.OnTouchListener {
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final View view;
    private float startX, startY, initX, initY;

    public DragTouchListener(WindowManager wm, WindowManager.LayoutParams p, View v) {
        this.wm = wm; this.params = p; this.view = v;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = e.getRawX(); startY = e.getRawY();
                initX = params.x; initY = params.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                params.x = (int)(initX + e.getRawX() - startX);
                params.y = (int)(initY + e.getRawY() - startY);
                wm.updateViewLayout(view, params);
                return true;
        }
        return false;
    }
}
