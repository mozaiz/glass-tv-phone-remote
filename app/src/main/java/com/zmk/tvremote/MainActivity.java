package com.zmk.tvremote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The TV screen: a QR code and the address to point the phone at, the link's state, "forget all phones". */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView url, status, phones;
    private ImageView qr;
    private String qrFor;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            RemoteService s = RemoteService.instance;
            String u = RemoteService.url();
            url.setText(u);
            if (!u.equals(qrFor)) {
                qr.setImageBitmap(Qr.make(u, 360));
                qrFor = u;
            }
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
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF111116);
        root.setPadding(64, 48, 64, 48);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TextView title = text("Phone Remote", 34, Color.WHITE);
        TextView hint = text("Scan the code with your phone's camera,\nor open this on a phone on the same Wi-Fi:", 20, 0xFFB0B0BC);
        hint.setPadding(0, 8, 0, 0);
        url = text("", 36, Color.WHITE);
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

        text.addView(title);
        text.addView(hint);
        text.addView(url);
        text.addView(status);
        text.addView(phones);
        text.addView(forget, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        qr = new ImageView(this);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(360, 360);
        qp.setMarginStart(72);

        // The text takes whatever the QR code leaves; wrap_content here squeezed it to a sliver.
        root.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(qr, qp);
        setContentView(root);
        forget.requestFocus();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
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
