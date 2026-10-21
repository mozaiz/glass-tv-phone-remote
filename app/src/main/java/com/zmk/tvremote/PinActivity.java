package com.zmk.tvremote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shows the pairing code over whatever is on screen, and closes once a phone uses it or it expires. */
public final class PinActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView code;
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            RemoteService s = RemoteService.instance;
            String pin = s == null ? null : s.pairing.currentPin();
            if (pin == null) {
                finish();
                return;
            }
            code.setText(pin.charAt(0) + " " + pin.charAt(1) + " " + pin.charAt(2) + " " + pin.charAt(3));
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF0111116);

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
        hint.setText("Type this code on your phone. Press Back to cancel.");
        hint.setTextSize(20);
        hint.setTextColor(0xFFB0B0BC);
        hint.setGravity(Gravity.CENTER);

        root.addView(title);
        root.addView(code);
        root.addView(hint);
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
