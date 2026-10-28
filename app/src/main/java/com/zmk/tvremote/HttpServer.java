package com.zmk.tvremote;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
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
 * A small HTTP/1.1 server for the phone page, its API and one WebSocket per open page.
 *
 * Every API call but pairing needs a token: the phone's cookie, or {@code Authorization: Bearer <key>}
 * from a Shortcuts key. POSTs also need an {@code X-Remote} header, which a page on another site cannot
 * send without a CORS preflight this server never answers, and the WebSocket only accepts its own
 * origin, so other websites open on the phone cannot press keys.
 */
final class HttpServer {
    private static final String TAG = "TvRemote.Http";
    private static final String LEANBACK = Intent.CATEGORY_LEANBACK_LAUNCHER, LAUNCHER = Intent.CATEGORY_LAUNCHER;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FAVOURITES = 6;

    private final RemoteService svc;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, byte[]> icons = new ConcurrentHashMap<>();
    private final SharedPreferences prefs;
    private ServerSocket server;
    private volatile long lastPinRequest;

    HttpServer(RemoteService svc, int port) {
        this.svc = svc;
        this.port = port;
        prefs = svc.getSharedPreferences("remote", Context.MODE_PRIVATE);
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
        // Mouse mode's virtual mouse goes away after two idle minutes, so the TV is not left thinking a
        // mouse is plugged in (some apps change how they look and behave while one is).
        Thread idle = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Thread.sleep(10_000);
                    if (mouseOn && System.currentTimeMillis() - lastMouse > MOUSE_IDLE_MS) {
                        mouseOn = false;
                        svc.adb.command("mouseoff");
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.i(TAG, "mouse idle: " + e);
                }
            }
        }, "mouse-idle");
        idle.setDaemon(true);
        idle.start();
    }

    private static final long MOUSE_IDLE_MS = 120_000;
    private volatile boolean mouseOn;
    private volatile long lastMouse;

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
            if ("/ws".equals(r.path) && "websocket".equalsIgnoreCase(r.headers.get("upgrade"))) {
                websocket(r, socket, in, out);
                return;
            }
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

    /** The phone's cookie, or a Shortcuts key sent as a bearer token. */
    private static String token(Request r) {
        String auth = r.headers.get("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
        return cookie(r, "rt");
    }

    private void route(Request r, OutputStream out) throws Exception {
        boolean post = "POST".equals(r.method);
        if (post && r.headers.get("x-remote") == null) {
            send(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        if ("GET".equals(r.method)) {
            switch (r.path) {
                case "/":
                case "/index.html":
                    send(out, 200, "text/html; charset=utf-8", asset("index.html"), null);
                    return;
                case "/manifest.webmanifest":
                    send(out, 200, "application/manifest+json", manifest(), null);
                    return;
                case "/icon.png": {
                    int size = 192;
                    try {
                        size = Math.max(48, Math.min(512, Integer.parseInt(r.params.getOrDefault("s", "192"))));
                    } catch (NumberFormatException ignored) {
                    }
                    send(out, 200, "image/png", appIcon(size), "Cache-Control: max-age=604800\r\n");
                    return;
                }
                default:
                    break;
            }
        }
        String token = token(r);
        boolean paired = svc.pairing.allowed(token);
        switch (r.path) {
            case "/api/status": {
                JSONObject o = new JSONObject().put("paired", paired).put("tv", svc.adb.alive())
                        .put("state", svc.adb.state()).put("awake", awake());
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
                String t = svc.pairing.confirm(r.params.get("pin"));
                if (t == null) {
                    json(out, 401, "{\"error\":\"wrong code\"}", null);
                    return;
                }
                json(out, 200, "{\"ok\":true}",
                        "Set-Cookie: rt=" + t + "; Max-Age=315360000; Path=/; HttpOnly; SameSite=Strict\r\n");
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
                String line = keyLine(r.params.get("code"), "1".equals(r.params.get("long")));
                if (line == null) break;
                command(out, line);
                return;
            }
            case "/api/text": {
                if (!post) break;
                String line = textLine(r.params.get("t"));
                if (line == null) break;
                command(out, line);
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
            case "/api/favorites": {
                if (post) {
                    List<String> want = new ArrayList<>();
                    Set<String> known = new HashSet<>();
                    for (App a : apps()) known.add(a.pkg);
                    for (String p : r.params.getOrDefault("list", "").split(",")) {
                        p = p.trim();
                        if (known.contains(p) && !want.contains(p) && want.size() < MAX_FAVOURITES) want.add(p);
                    }
                    prefs.edit().putString("favourites", android.text.TextUtils.join(",", want)).apply();
                }
                JSONArray list = new JSONArray();
                Map<String, App> byPkg = new HashMap<>();
                for (App a : apps()) byPkg.put(a.pkg, a);
                for (String p : favourites()) {
                    App a = byPkg.get(p);
                    if (a != null) list.put(new JSONObject().put("pkg", a.pkg).put("label", a.label));
                }
                json(out, 200, list.toString(), null);
                return;
            }
            case "/api/screen.jpg": {
                byte[] jpg;
                try {
                    jpg = svc.adb.screenshot();
                } catch (IOException e) {
                    jpg = null;
                }
                if (jpg == null) {
                    json(out, 503, "{\"error\":\"no screenshot\"}", null);
                    return;
                }
                send(out, 200, "image/jpeg", jpg, null);
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
            case "/api/shortcut-key": {
                if (!post) break;
                json(out, 200, new JSONObject().put("key", svc.pairing.newKey()).toString(), null);
                return;
            }
            case "/api/unpair": {
                if (!post) break;
                svc.pairing.forget(token);
                json(out, 200, "{\"ok\":true}", "Set-Cookie: rt=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict\r\n");
                return;
            }
            case "/api/tvinfo": {
                json(out, 200, TvInputs.info(svc).put("shortcutKeys", svc.pairing.keys()).toString(), null);
                return;
            }
            default:
                break;
        }
        json(out, 404, "{\"error\":\"not found\"}", null);
    }

    /** The helper command for a key press, or null when the code is not a key. */
    private String keyLine(String code, boolean hold) {
        int c;
        try {
            c = Integer.parseInt(code == null ? "0" : code.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (c <= 0 || c > 400) return null;
        // POWER puts some boxes into a standby that cuts Wi-Fi, and then no phone can wake them.
        // SLEEP keeps the network up (with the service's wake lock), and WAKEUP brings the screen back.
        if (c == android.view.KeyEvent.KEYCODE_POWER) {
            c = awake() ? android.view.KeyEvent.KEYCODE_SLEEP : android.view.KeyEvent.KEYCODE_WAKEUP;
        }
        return (hold ? "long " : "key ") + c;
    }

    /** Mouse mode: {@code m:<dx>,<dy>} moves, {@code c:1} clicks (2 = right, Back), {@code w:<n>} scrolls. */
    private static String mouseLine(String msg) {
        try {
            String v = msg.substring(2);
            switch (msg.charAt(0)) {
                case 'm': {
                    String[] d = v.split(",");
                    int dx = Math.max(-2000, Math.min(2000, Integer.parseInt(d[0].trim())));
                    int dy = Math.max(-2000, Math.min(2000, Integer.parseInt(d[1].trim())));
                    return "mouse " + dx + " " + dy;
                }
                case 'c':
                    return "click " + ("2".equals(v.trim()) ? 2 : 1);
                case 'w':
                    return "wheel " + Math.max(-20, Math.min(20, Integer.parseInt(v.trim())));
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String textLine(String t) {
        if (t == null) return null;
        t = t.replace('\n', ' ').replace('\r', ' ');
        if (t.isEmpty() || t.length() > 500) return null;
        return "text " + t;
    }

    private void command(OutputStream out, String line) throws Exception {
        try {
            svc.adb.command(line);
            json(out, 200, "{\"ok\":true}", null);
        } catch (IOException e) {
            json(out, 503, new JSONObject().put("error", e.getMessage()).toString(), null);
        }
    }

    private boolean awake() {
        PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private List<String> favourites() {
        String s = prefs.getString("favourites", "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    // ---- WebSocket: key presses and typing from the open page, without a new connection each ----

    /**
     * One page's socket. Each text message is {@code k:<code>}, {@code l:<code>} (held) or
     * {@code t:<text>}; the server answers {@code ok} or {@code err:<reason>}. Only the page's own
     * origin and a paired phone get in.
     */
    private void websocket(Request r, Socket socket, InputStream in, OutputStream out) throws Exception {
        String key = r.headers.get("sec-websocket-key");
        String origin = r.headers.get("origin"), host = r.headers.get("host");
        boolean sameOrigin = false;
        try {
            sameOrigin = origin != null && host != null && host.equalsIgnoreCase(new URI(origin).getRawAuthority());
        } catch (Exception ignored) {
        }
        if (key == null || !sameOrigin || !svc.pairing.allowed(token(r))) {
            send(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)), Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        socket.setSoTimeout(0);
        socket.setTcpNoDelay(true);
        String tok = token(r);
        DataInputStream din = new DataInputStream(in);
        while (true) {
            int b0 = din.readUnsignedByte(), b1 = din.readUnsignedByte();
            int op = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = din.readUnsignedShort();
            else if (len == 127) len = din.readLong();
            if (len > 4096) return;
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) din.readFully(mask);
            byte[] data = new byte[(int) len];
            din.readFully(data);
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if (op == 0x8) {
                frame(out, 0x8, new byte[0]);
                return;
            }
            if (op == 0x9) {
                frame(out, 0xA, data);
                continue;
            }
            if (op != 0x1) continue;
            // A phone forgotten from the TV while its page is open loses the socket too.
            if (!svc.pairing.allowed(tok)) {
                frame(out, 0x8, new byte[0]);
                return;
            }
            String msg = new String(data, StandardCharsets.UTF_8);
            String line = msg.startsWith("k:") ? keyLine(msg.substring(2), false)
                    : msg.startsWith("l:") ? keyLine(msg.substring(2), true)
                    : msg.startsWith("t:") ? textLine(msg.substring(2))
                    : msg.startsWith("m:") || msg.startsWith("c:") || msg.startsWith("w:") ? mouseLine(msg) : null;
            if (line != null && line.matches("^(mouse|click|wheel) .*")) {
                lastMouse = System.currentTimeMillis();
                mouseOn = true;
            }
            String reply;
            if (line == null) {
                reply = "err:bad message";
            } else {
                try {
                    svc.adb.command(line);
                    reply = "ok";
                } catch (IOException e) {
                    reply = "err:" + e.getMessage();
                }
            }
            frame(out, 0x1, reply.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void frame(OutputStream out, int op, byte[] data) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | op);
        if (data.length < 126) {
            f.write(data.length);
        } else {
            f.write(126);
            f.write(data.length >> 8);
            f.write(data.length & 0xff);
        }
        f.write(data);
        synchronized (out) {
            out.write(f.toByteArray());
            out.flush();
        }
    }

    // ---- apps ----

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
            byte[] png = png(svc.getPackageManager().getApplicationIcon(pkg), 128);
            icons.put(pkg, png);
            return png;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the page as an app on the phone's home screen ----

    private byte[] appIcon(int size) {
        String k = "#self" + size;
        byte[] cached = icons.get(k);
        if (cached != null) return cached;
        byte[] png = png(svc.getDrawable(R.drawable.ic_remote), size);
        icons.put(k, png);
        return png;
    }

    private static byte[] manifest() throws Exception {
        JSONArray icons = new JSONArray();
        for (int s : new int[]{192, 512}) {
            icons.put(new JSONObject().put("src", "/icon.png?s=" + s).put("sizes", s + "x" + s).put("type", "image/png"));
        }
        return new JSONObject()
                .put("name", "TV Remote")
                .put("short_name", "TV Remote")
                .put("start_url", "/")
                .put("display", "standalone")
                .put("background_color", "#0d0d12")
                .put("theme_color", "#0d0d12")
                .put("icons", icons)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, png);
        b.recycle();
        return png.toByteArray();
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
                : status == 404 ? "Not Found" : status == 409 ? "Conflict" : status == 429 ? "Too Many Requests"
                : "Service Unavailable";
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
