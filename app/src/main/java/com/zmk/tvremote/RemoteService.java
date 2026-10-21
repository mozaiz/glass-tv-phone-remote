package com.zmk.tvremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Keeps the web server and the key-press helper running, from boot on. */
public final class RemoteService extends Service {
    static final int PORT = 8080;
    private static final String TAG = "TvRemote";
    private static final String CHANNEL = "remote";
    static volatile RemoteService instance;

    Adb adb;
    Pairing pairing;
    private HttpServer http;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, RemoteService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle("Phone remote")
                .setContentText("Open " + url() + " on your phone")
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        adb = new Adb(this);
        pairing = new Pairing(this);
        http = new HttpServer(this, PORT);
        try {
            http.start();
        } catch (Exception e) {
            Log.e(TAG, "web server did not start: " + e);
        }
        instance = this;
        // Connect now, so the first button press on the phone is quick.
        new Thread(adb::ensure, "adb-connect").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (adb != null && !adb.alive()) new Thread(adb::ensure, "adb-connect").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (http != null) http.stop();
        if (adb != null) adb.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** The address a phone on the same network opens. */
    static String url() {
        String ip = "<box address>";
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        ip = a.getHostAddress();
                        if (nif.getName().startsWith("wlan") || nif.getName().startsWith("eth")) return "http://" + ip + ":" + PORT;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "http://" + ip + ":" + PORT;
    }
}
