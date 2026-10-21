package com.zmk.tvremote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shows the pairing code over whatever is on screen, and closes once a phone uses it or it expires.
 * The QR code opens the page with the code filled in, so scanning it pairs in one step.
 */
public final class PinActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView code;
    private ImageView qr;
    private String shown;
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            RemoteService s = RemoteService.instance;
            String pin = s == null ? null : s.pairing.currentPin();
            if (pin == null) {
                finish();
                return;
            }
            if (!pin.equals(shown)) {
                code.setText(pin.charAt(0) + " " + pin.charAt(1) + " " + pin.charAt(2) + " " + pin.charAt(3));
                qr.setImageBitmap(Qr.make(RemoteService.url() + "/?pin=" + pin, 320));
                shown = pin;
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF0111116);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("Pair your phone");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        code = new TextView(this);
        code.setTextSize(96);
        code.setTextColor(Color.WHITE);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, 24, 0, 24);

        TextView hint = new TextView(this);
        hint.setText("Type this code on your phone, or scan the QR code.\nPress Back to cancel.");
        hint.setTextSize(20);
        hint.setTextColor(0xFFB0B0BC);
        hint.setGravity(Gravity.CENTER);

        text.addView(title);
        text.addView(code);
        text.addView(hint);

        qr = new ImageView(this);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(320, 320);
        qp.setMarginStart(80);

        root.setPadding(64, 48, 64, 48);
        root.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(qr, qp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(check);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(check);
        super.onPause();
        // Leaving the screen (Back, or an app opening) ends this pairing screen; a new request shows it again.
        finish();
    }
}
