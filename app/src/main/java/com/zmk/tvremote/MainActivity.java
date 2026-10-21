package com.zmk.tvremote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The TV screen: where to point the phone, whether the link works, and "forget all phones". */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView url, status, phones;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            RemoteService s = RemoteService.instance;
            url.setText(RemoteService.url());
            status.setText(s == null ? "Starting…" : s.adb.state());
            status.setTextColor(s != null && s.adb.alive() ? 0xFF7CE38B : 0xFFFFC857);
            int n = s == null ? 0 : s.pairing.phones();
            phones.setText(n == 0 ? "No phones paired yet" : n == 1 ? "1 phone paired" : n + " phones paired");
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        RemoteService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF111116);
        root.setPadding(64, 48, 64, 48);

        TextView title = text("Phone Remote", 34, Color.WHITE);
        TextView hint = text("On your phone, connected to the same Wi-Fi, open:", 20, 0xFFB0B0BC);
        url = text("", 44, Color.WHITE);
        url.setPadding(0, 12, 0, 24);
        status = text("", 20, 0xFFFFC857);
        phones = text("", 18, 0xFFB0B0BC);
        phones.setPadding(0, 8, 0, 32);

        Button forget = new Button(this);
        forget.setText("Forget all phones");
        forget.setOnClickListener(v -> {
            RemoteService s = RemoteService.instance;
            if (s != null) s.pairing.forgetAll();
        });

        root.addView(title);
        root.addView(hint);
        root.addView(url);
        root.addView(status);
        root.addView(phones);
        root.addView(forget, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        forget.requestFocus();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        RemoteService.start(this);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
