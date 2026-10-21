package com.zmk.tvremote;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * One long-lived connection to the box's own debugging service (127.0.0.1:5555) running {@link Injector}.
 * The first connection makes the TV ask "Allow USB debugging?" once; after "Always allow" it connects
 * silently. The ADB wire protocol and key format are adapted from Glass TV's CacheCleaner
 * (github.com/cocombee/glass-tv, MIT, based on CleanYourTV by Gathin).
 */
final class Adb {
    private static final String TAG = "TvRemote.Adb";
    private static final int PORT = 5555;
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_PUBLIC_KEY = 3;
    /** PKCS#1 DigestInfo for SHA-1: the service signs its 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final int LOCAL_ID = 1;

    private final Context ctx;
    private Socket socket;
    private OutputStream out;
    private int remoteId;
    private volatile boolean alive;
    /** What the TV screen shows about the link. */
    private volatile String state = "Not connected yet";
    /** The service accepts one WRTE at a time: the next waits for its OKAY. */
    private final Object ackLock = new Object();
    private boolean acked = true;

    Adb(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    String state() {
        return state;
    }

    boolean alive() {
        return alive;
    }

    /** Connects if needed. The first time, the TV shows its prompt and this waits up to a minute. */
    synchronized boolean ensure() {
        if (alive) return true;
        // Once the prompt is answered the TV saves the key, but the connection that asked is not always
        // let through: a fresh one, with no second prompt, finds the key allowed.
        for (int attempt = 0; attempt < 2 && !alive; attempt++) {
            try {
                open();
            } catch (IOException e) {
                state = "Can't reach the box's debugging service. Is network debugging on?";
                Log.i(TAG, "connect failed: " + e);
                close();
                return false;
            } catch (Exception e) {
                Log.w(TAG, "connect failed: " + e);
                close();
            }
        }
        return alive;
    }

    /** Sends one command line to the Injector, reconnecting once if the link dropped. */
    synchronized void command(String line) throws IOException {
        if (!ensure()) throw new IOException(state);
        try {
            write(line);
        } catch (IOException e) {
            close();
            if (!ensure()) throw new IOException(state);
            write(line);
        }
    }

    synchronized void close() {
        alive = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        out = null;
        synchronized (ackLock) {
            acked = true;
            ackLock.notifyAll();
        }
    }

    private void open() throws Exception {
        close();
        KeyPair keys = keys();
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
        s.setSoTimeout(10_000);
        socket = s;
        DataInputStream in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        send(CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.US_ASCII));
        Message m = read(in);
        if (m.cmd == AUTH && m.arg0 == AUTH_TOKEN) {
            send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
            m = read(in);
            if (m.cmd == AUTH && m.arg0 == AUTH_TOKEN) {
                // Not trusted yet: the TV now asks "Allow USB debugging?". Wait for the answer.
                state = "On the TV: tick \"Always allow\" and press OK";
                send(AUTH, AUTH_PUBLIC_KEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                s.setSoTimeout(60_000);
                try {
                    m = read(in);
                } catch (SocketTimeoutException | EOFException e) {
                    state = "The debugging prompt wasn't accepted. Open Phone Remote to try again.";
                    throw new IllegalStateException("prompt not accepted");
                }
            }
        }
        if (m.cmd != CNXN) {
            state = "The debugging prompt wasn't accepted. Open Phone Remote to try again.";
            throw new IllegalStateException("not allowed");
        }
        String run = "shell:CLASSPATH=" + ctx.getPackageCodePath() + " app_process / " + Injector.class.getName();
        send(OPEN, LOCAL_ID, 0, (run + "\0").getBytes(StandardCharsets.US_ASCII));
        m = read(in);
        if (m.cmd != OKAY) throw new IllegalStateException("helper did not start");
        remoteId = m.arg0;
        s.setSoTimeout(0);
        alive = true;
        state = "Connected";
        Thread reader = new Thread(() -> readLoop(s, in), "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(Socket s, DataInputStream in) {
        try {
            while (true) {
                Message m = read(in);
                if (m.cmd == WRTE) {
                    Log.i(TAG, "helper: " + new String(m.data, StandardCharsets.UTF_8).trim());
                    send(OKAY, LOCAL_ID, remoteId, null);
                } else if (m.cmd == OKAY) {
                    synchronized (ackLock) {
                        acked = true;
                        ackLock.notifyAll();
                    }
                } else if (m.cmd == CLSE) {
                    break;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "link closed: " + e);
        }
        if (socket == s) {
            alive = false;
            state = "Reconnecting…";
            synchronized (ackLock) {
                acked = true;
                ackLock.notifyAll();
            }
        }
    }

    private void write(String line) throws IOException {
        synchronized (ackLock) {
            long end = System.currentTimeMillis() + 3000;
            while (!acked && alive) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new IOException("helper not answering");
                try {
                    ackLock.wait(left);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (!alive) throw new IOException("link closed");
            acked = false;
        }
        send(WRTE, LOCAL_ID, remoteId, (line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final class Message {
        int cmd, arg0;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("not connected");
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        if (len > 0) h.put(data);
        synchronized (o) {
            o.write(h.array());
            o.flush();
        }
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad message length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        s.update(SHA1_PREFIX);
        s.update(token);
        return s.sign();
    }

    /** The public key in the service's own format: a 2048-bit RSA key as little-endian words, base64. */
    private static byte[] publicKey(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Phone Remote\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** This app's own key, made once and kept in its private files. */
    private KeyPair keys() throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            try {
                return new KeyPair(f.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        f.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            } catch (Exception e) {
                Log.w(TAG, "stored key unreadable, making a new one: " + e);
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair k = g.generateKeyPair();
        writeAll(priv, k.getPrivate().getEncoded());
        writeAll(pub, k.getPublic().getEncoded());
        return k;
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] buf = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        }
        return buf;
    }

    private static void writeAll(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }
}
