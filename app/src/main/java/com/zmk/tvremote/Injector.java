package com.zmk.tvremote;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * The part that actually presses keys. Not run inside the app: Adb starts it on the box as
 * {@code CLASSPATH=<this apk> app_process / com.zmk.tvremote.Injector}, through the box's debugging
 * service, so it runs as the shell user, which may inject input into any app (the same way scrcpy
 * works). It stays running and reads one command per line from stdin, so a key press costs
 * milliseconds instead of starting {@code input keyevent} every time.
 *
 * Commands: {@code key <code>}, {@code long <code>}, {@code text <chars>}, {@code start <component> <category>},
 * {@code view <channel uri>}, {@code pin}. Anything else is ignored.
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
            case "pin":
                run("am", "start", "-n", "com.zmk.tvremote/.PinActivity");
                break;
            default:
                break;
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

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().close();
        p.waitFor();
    }
}
