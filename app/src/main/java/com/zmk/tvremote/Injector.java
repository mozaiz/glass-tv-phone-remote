package com.zmk.tvremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The part that actually presses keys. Not run inside the app: Adb starts it on the box as
 * {@code CLASSPATH=<this apk> app_process / com.zmk.tvremote.Injector}, through the box's debugging
 * service, so it runs as the shell user, which may inject input into any app (the same way scrcpy
 * works). It stays running and reads one command per line from stdin, so a key press costs
 * milliseconds instead of starting {@code input keyevent} every time.
 *
 * Commands: {@code key <code>}, {@code long <code>}, {@code text <chars>}, {@code start <component> <category>},
 * {@code view <channel uri>}, {@code shot}, {@code pin}, {@code mouse <dx> <dy>}, {@code click <1|2>},
 * {@code wheel <steps>}. Anything else is ignored.
 */
public final class Injector {
    private static Object inputManager;
    private static Method inject;

    private Injector() {
    }

    public static void main(String[] args) throws Exception {
        inputManager = InputManager.class.getDeclaredMethod("getInstance").invoke(null);
        inject = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        System.out.println("ready");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            try {
                handle(line);
            } catch (Exception e) {
                System.out.println("error " + line + ": " + e);
                System.out.flush();
            }
        }
    }

    private static void handle(String line) throws Exception {
        int space = line.indexOf(' ');
        String cmd = space < 0 ? line : line.substring(0, space);
        String arg = space < 0 ? "" : line.substring(space + 1);
        switch (cmd) {
            case "key":
                press(Integer.parseInt(arg.trim()), false);
                break;
            case "long":
                press(Integer.parseInt(arg.trim()), true);
                break;
            case "text":
                type(arg);
                break;
            case "start": {
                String[] parts = arg.trim().split(" ");
                run("am", "start", "-a", "android.intent.action.MAIN", "-c", parts[1], "-n", parts[0]);
                break;
            }
            case "view":
                // An input's channel URI; the TV's Live TV app switches to it (Glass TV does the same).
                run("am", "start", "-a", "android.intent.action.VIEW", "-d", arg.trim(),
                        "-t", "vnd.android.cursor.item/channel");
                break;
            case "shot":
                shot();
                break;
            case "pin":
                run("am", "start", "-n", "com.zmk.tvremote/.PinActivity");
                break;
            case "mouse": {
                String[] d = arg.trim().split(" ");
                Mouse.move(Integer.parseInt(d[0]), Integer.parseInt(d[1]));
                break;
            }
            case "click":
                Mouse.click(Integer.parseInt(arg.trim()));
                break;
            case "wheel":
                Mouse.wheel(Integer.parseInt(arg.trim()));
                break;
            default:
                break;
        }
    }

    /**
     * A virtual USB mouse, made with Android's own {@code hid} tool (the shell user may create HID
     * devices), so the system draws its real pointer and apps get ordinary mouse events. Injected
     * mouse events alone would work but show no pointer. The device appears on first use and is
     * removed after two idle minutes, so the TV is not left thinking a mouse is plugged in.
     */
    static final class Mouse {
        /** A 3-button wheel mouse: report = buttons, dx, dy, wheel (signed bytes, relative). */
        private static final int[] DESCRIPTOR = {0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
                0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
                0x95, 0x01, 0x75, 0x05, 0x81, 0x03, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81,
                0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0};
        private static final long IDLE_MS = 120_000;
        private static Process hid;
        private static java.io.OutputStream out;
        private static volatile long lastUse;

        static synchronized void move(int dx, int dy) throws Exception {
            // A report carries at most ±127 per axis; split a fast swipe into several.
            while (dx != 0 || dy != 0) {
                int sx = Math.max(-127, Math.min(127, dx)), sy = Math.max(-127, Math.min(127, dy));
                report(0, sx, sy, 0);
                dx -= sx;
                dy -= sy;
            }
        }

        /** 1 = left (a tap), 2 = right (Android treats it as Back). */
        static synchronized void click(int button) throws Exception {
            int bits = button == 2 ? 2 : 1;
            report(bits, 0, 0, 0);
            Thread.sleep(30);
            report(0, 0, 0, 0);
        }

        /** Positive scrolls up, negative down, one notch per step. */
        static synchronized void wheel(int steps) throws Exception {
            int n = Math.max(-20, Math.min(20, steps));
            for (int i = 0; i < Math.abs(n); i++) report(0, 0, 0, n > 0 ? 1 : -1);
        }

        private static void report(int buttons, int dx, int dy, int wheel) throws Exception {
            ensure();
            write("{\"id\":1,\"command\":\"report\",\"report\":[" + buttons + "," + (dx & 0xff) + ","
                    + (dy & 0xff) + "," + (wheel & 0xff) + "]}");
            lastUse = SystemClock.uptimeMillis();
        }

        private static void ensure() throws Exception {
            if (hid != null && hid.isAlive()) return;
            hid = new ProcessBuilder("hid", "-").redirectErrorStream(true).start();
            out = hid.getOutputStream();
            Thread drain = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    while (hid.getInputStream().read(buf) > 0) { }
                } catch (Exception ignored) {
                }
            });
            drain.setDaemon(true);
            drain.start();
            StringBuilder d = new StringBuilder();
            for (int b : DESCRIPTOR) d.append(d.length() == 0 ? "" : ",").append(b);
            write("{\"id\":1,\"command\":\"register\",\"name\":\"Phone Remote mouse\",\"vid\":6353,\"pid\":20192,"
                    + "\"bus\":\"usb\",\"descriptor\":[" + d + "]}");
            // The input system needs a moment to add the device before the first report counts; at 300 ms
            // the first moves were lost on the test box. This only happens once per two idle minutes.
            Thread.sleep(800);
            lastUse = SystemClock.uptimeMillis();
            Thread idle = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (Mouse.class) {
                        if (hid == null) return;
                        if (SystemClock.uptimeMillis() - lastUse > IDLE_MS) {
                            stop();
                            return;
                        }
                    }
                }
            });
            idle.setDaemon(true);
            idle.start();
        }

        private static void write(String json) throws Exception {
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static void stop() {
            try {
                out.close();
            } catch (Exception ignored) {
            }
            if (hid != null) hid.destroy();
            hid = null;
            out = null;
        }
    }

    /** One press, or a held press that apps see as a long press (a repeat flagged FLAG_LONG_PRESS). */
    private static void press(int code, boolean hold) throws Exception {
        long down = SystemClock.uptimeMillis();
        send(key(down, down, KeyEvent.ACTION_DOWN, code, 0, 0));
        if (hold) {
            Thread.sleep(500);
            send(key(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, KeyEvent.FLAG_LONG_PRESS));
            Thread.sleep(100);
        }
        send(key(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0));
    }

    private static KeyEvent key(long down, long when, int action, int code, int repeat, int flags) {
        return new KeyEvent(down, when, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags | KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
    }

    /** Types text into whatever field has focus. Characters the virtual keyboard map lacks are skipped. */
    private static void type(String text) throws Exception {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i < text.length(); i++) {
            KeyEvent[] events = map.getEvents(new char[]{text.charAt(i)});
            if (events == null) continue;
            for (KeyEvent e : events) send(e);
        }
    }

    private static void send(InputEvent event) throws Exception {
        inject.invoke(inputManager, event, 0);   // 0: asynchronous, like `input keyevent`
    }

    /**
     * A small JPEG of the screen, printed as one line {@code shot <base64>} for the app to pick up.
     * Protected video (Netflix and the like) comes out black, as in any screenshot.
     */
    private static void shot() throws Exception {
        File png = new File("/data/local/tmp/tvremote-shot.png");
        run("screencap", "-p", png.getPath());
        Bitmap full = BitmapFactory.decodeFile(png.getPath());
        png.delete();
        if (full == null) {
            System.out.println("shot ");
            System.out.flush();
            return;
        }
        int w = 640, h = Math.max(1, full.getHeight() * w / full.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(full, w, h, true);
        full.recycle();
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 72, jpg);
        small.recycle();
        System.out.println("shot " + Base64.getEncoder().encodeToString(jpg.toByteArray()));
        System.out.flush();
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().close();
        p.waitFor();
    }
}
