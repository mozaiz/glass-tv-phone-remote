package com.zmk.tvremote;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * Mouse mode's fallback pointer: an arrow Phone Remote draws over everything, for TVs that do not let
 * the helper create a virtual mouse. The server keeps the position and moves this arrow; clicks and
 * scrolls are sent to that spot as taps and wheel events. The window takes no touches or focus, so taps
 * land on whatever is under it. Like Android's own pointer it fades after a few seconds still.
 */
final class CursorOverlay {
    private static final long FADE_MS = 6_000;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private ImageView arrow;
    private WindowManager.LayoutParams lp;
    private int x = -1, y, width, height;
    private final Runnable fade = () -> {
        if (arrow != null) arrow.setVisibility(android.view.View.GONE);
    };

    CursorOverlay(Context ctx) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
    }

    /** Whether the app may draw over other apps (granted by the helper on first fallback use). */
    boolean allowed() {
        return Settings.canDrawOverlays(ctx);
    }

    /** Moves the pointer by a relative step, keeping it on screen, and returns where it is now. */
    synchronized int[] move(int dx, int dy) {
        size();
        if (x < 0) {
            x = width / 2;
            y = height / 2;
        }
        x = Math.max(0, Math.min(width - 1, x + dx));
        y = Math.max(0, Math.min(height - 1, y + dy));
        final int px = x, py = y;
        main.post(() -> show(px, py));
        return new int[]{x, y};
    }

    synchronized int[] position() {
        size();
        if (x < 0) {
            x = width / 2;
            y = height / 2;
        }
        return new int[]{x, y};
    }

    void remove() {
        main.post(() -> {
            main.removeCallbacks(fade);
            if (arrow != null) {
                try {
                    wm.removeView(arrow);
                } catch (Exception ignored) {
                }
                arrow = null;
            }
        });
    }

    private void size() {
        if (width > 0) return;
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        width = p.x;
        height = p.y;
    }

    private void show(int px, int py) {
        if (!allowed()) return;
        try {
            if (arrow == null) {
                arrow = new ImageView(ctx);
                arrow.setImageResource(R.drawable.cursor);
                int s = Math.max(24, height / 27);
                lp = new WindowManager.LayoutParams(s, s,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = px;
                lp.y = py;
                wm.addView(arrow, lp);
            } else {
                arrow.setVisibility(android.view.View.VISIBLE);
                lp.x = px;
                lp.y = py;
                wm.updateViewLayout(arrow, lp);
            }
            main.removeCallbacks(fade);
            main.postDelayed(fade, FADE_MS);
        } catch (Exception e) {
            arrow = null;
        }
    }
}
