package com.zmk.tvremote;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small HTTP/1.1 server for the phone page and its API. One request per connection.
 *
 * Every API call but the pairing ones needs the phone's token cookie. POSTs also need an
 * {@code X-Remote} header, which a page on another site cannot send without a preflight this server
 * never answers, so other websites open on the phone cannot press keys.
 */
final class HttpServer {
    private static final String TAG = "TvRemote.Http";
    private static final String LEANBACK = Intent.CATEGORY_LEANBACK_LAUNCHER, LAUNCHER = Intent.CATEGORY_LAUNCHER;

    private final RemoteService svc;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Map<String, byte[]> icons = new ConcurrentHashMap<>();
    private ServerSocket server;
    private volatile long lastPinRequest;

    HttpServer(RemoteService svc, int port) {
        this.svc = svc;
        this.port = port;
    }

    void start() throws IOException {
        server = new ServerSocket(port);
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (!server.isClosed()) Log.w(TAG, "accept: " + e);
                }
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private static final class Request {
        String method, path;
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> params = new HashMap<>();
    }

    private void serve(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Request r = parse(in);
            if (r == null) return;
            route(r, out);
        } catch (Exception e) {
            Log.i(TAG, "request failed: " + e);
        }
    }

    private static Request parse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0, c;
        while (state < 4 && (c = in.read()) >= 0) {
            head.write(c);
            if (head.size() > 16_384) return null;
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request r = new Request();
        r.method = first[0];
        String target = first[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) form(target.substring(q + 1), r.params);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
        }
        int length = 0;
        try {
            length = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        if (length > 0 && length <= 8192) {
            byte[] body = new byte[length];
            int n = 0, k;
            while (n < length && (k = in.read(body, n, length - n)) > 0) n += k;
            form(new String(body, 0, n, StandardCharsets.UTF_8), r.params);
        }
        return r;
    }

    private static void form(String s, Map<String, String> into) {
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                into.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    private static String cookie(Request r, String name) {
        String all = r.headers.get("cookie");
        if (all == null) return null;
        for (String part : all.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    private void route(Request r, OutputStream out) throws Exception {
        boolean post = "POST".equals(r.method);
        if (post && r.headers.get("x-remote") == null) {
            send(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        if ("GET".equals(r.method) && ("/".equals(r.path) || "/index.html".equals(r.path))) {
            send(out, 200, "text/html; charset=utf-8", asset("index.html"), null);
            return;
        }
        boolean paired = svc.pairing.allowed(cookie(r, "rt"));
        switch (r.path) {
            case "/api/status": {
                JSONObject o = new JSONObject().put("paired", paired).put("tv", svc.adb.alive()).put("state", svc.adb.state());
                json(out, 200, o.toString(), null);
                return;
            }
            case "/api/pair/start": {
                if (!post) break;
                long now = System.currentTimeMillis();
                if (now - lastPinRequest < 3000) {
                    json(out, 429, "{\"error\":\"wait\"}", null);
                    return;
                }
                lastPinRequest = now;
                svc.pairing.newPin();
                try {
                    svc.adb.command("pin");
                } catch (IOException e) {
                    json(out, 503, new JSONObject().put("error", e.getMessage()).toString(), null);
                    return;
                }
                json(out, 200, "{\"ok\":true}", null);
                return;
            }
            case "/api/pair/confirm": {
                if (!post) break;
                String token = svc.pairing.confirm(r.params.get("pin"));
                if (token == null) {
                    json(out, 401, "{\"error\":\"wrong code\"}", null);
                    return;
                }
                json(out, 200, "{\"ok\":true}",
                        "Set-Cookie: rt=" + token + "; Max-Age=315360000; Path=/; HttpOnly; SameSite=Strict\r\n");
                return;
            }
            default:
                break;
        }
        if (!paired) {
            json(out, 401, "{\"error\":\"not paired\"}", null);
            return;
        }
        switch (r.path) {
            case "/api/key": {
                if (!post) break;
                int code = Integer.parseInt(r.params.getOrDefault("code", "0"));
                if (code <= 0 || code > 400) break;
                command(out, ("1".equals(r.params.get("long")) ? "long " : "key ") + code);
                return;
            }
            case "/api/text": {
                if (!post) break;
                String t = r.params.getOrDefault("t", "").replace('\n', ' ').replace('\r', ' ');
                if (t.isEmpty() || t.length() > 500) break;
                command(out, "text " + t);
                return;
            }
            case "/api/launch": {
                if (!post) break;
                String[] target = launchTarget(r.params.get("pkg"));
                if (target == null) {
                    json(out, 404, "{\"error\":\"no such app\"}", null);
                    return;
                }
                command(out, "start " + target[0] + " " + target[1]);
                return;
            }
            case "/api/inputs": {
                JSONArray list = new JSONArray();
                for (TvInputs.Input in : TvInputs.list(svc)) {
                    list.put(new JSONObject().put("id", in.id).put("label", in.label).put("kind", in.kind)
                            .put("state", in.state));
                }
                String sw = TvInputs.switcher(svc);
                json(out, 200, new JSONObject().put("inputs", list).put("canSwitch", sw != null).toString(), null);
                return;
            }
            case "/api/input": {
                if (!post) break;
                TvInputs.Input in = TvInputs.find(svc, r.params.get("id"));
                if (in == null) {
                    json(out, 404, "{\"error\":\"This TV has no such input\"}", null);
                    return;
                }
                if (TvInputs.switcher(svc) == null) {
                    json(out, 409, "{\"error\":\"No app on this TV switches inputs. Use the Input button instead.\"}", null);
                    return;
                }
                command(out, "view " + TvInputs.uri(in.id));
                return;
            }
            case "/api/unpair": {
                if (!post) break;
                svc.pairing.forget(cookie(r, "rt"));
                json(out, 200, "{\"ok\":true}", "Set-Cookie: rt=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict\r\n");
                return;
            }
            case "/api/tvinfo": {
                json(out, 200, TvInputs.info(svc).toString(), null);
                return;
            }
            case "/api/apps": {
                JSONArray list = new JSONArray();
                for (App a : apps()) list.put(new JSONObject().put("pkg", a.pkg).put("label", a.label));
                json(out, 200, list.toString(), null);
                return;
            }
            case "/api/icon": {
                byte[] png = icon(r.params.get("pkg"));
                if (png == null) break;
                send(out, 200, "image/png", png, "Cache-Control: max-age=86400\r\n");
                return;
            }
            default:
                break;
        }
        json(out, 404, "{\"error\":\"not found\"}", null);
    }

    private void command(OutputStream out, String line) throws Exception {
        try {
            svc.adb.command(line);
            json(out, 200, "{\"ok\":true}", null);
        } catch (IOException e) {
            json(out, 503, new JSONObject().put("error", e.getMessage()).toString(), null);
        }
    }

    private static final class App {
        String pkg, label, component, category;
    }

    /** Every app the TV can open: its TV entry when it has one, else its phone entry. */
    private List<App> apps() {
        PackageManager pm = svc.getPackageManager();
        Map<String, App> byPkg = new HashMap<>();
        for (String category : new String[]{LEANBACK, LAUNCHER}) {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (byPkg.containsKey(pkg) || pkg.equals(svc.getPackageName())) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.component = pkg + "/" + ri.activityInfo.name;
                a.category = category;
                byPkg.put(pkg, a);
            }
        }
        List<App> list = new ArrayList<>(byPkg.values());
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return list;
    }

    private String[] launchTarget(String pkg) {
        if (pkg == null) return null;
        for (App a : apps()) if (a.pkg.equals(pkg)) return new String[]{a.component, a.category};
        return null;
    }

    private byte[] icon(String pkg) {
        if (pkg == null) return null;
        byte[] cached = icons.get(pkg);
        if (cached != null) return cached;
        Set<String> known = new HashSet<>();
        for (App a : apps()) known.add(a.pkg);
        if (!known.contains(pkg)) return null;
        try {
            Drawable d = svc.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 128, 128);
            d.draw(c);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, png);
            b.recycle();
            icons.put(pkg, png.toByteArray());
            return png.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = svc.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void json(OutputStream out, int status, String body, String extra) throws IOException {
        send(out, status, "application/json", body.getBytes(StandardCharsets.UTF_8), extra);
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String extra) throws IOException {
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found" : status == 409 ? "Conflict" : status == 429 ? "Too Many Requests" : "Service Unavailable";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + (extra != null && extra.startsWith("Cache-Control") ? "" : "Cache-Control: no-store\r\n")
                + (extra != null ? extra : "")
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
