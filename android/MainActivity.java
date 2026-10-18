package com.erriezzans.btrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Bt-relay server: waits for a Bluetooth serial connection from a Nokia phone,
 * reads one URL line, fetches it over the internet (HTTP or HTTPS),
 * converts the page to plain text and sends it back.
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {

    // Must match SVC_UUID in the Nokia MIDlet.
    static final UUID APP_UUID = UUID.fromString("a1b2c3d4-e5f6-0718-293a-4b5c6d7e8f90");
    static final int MAX_CHARS = 8000;

    private TextView logView;
    private ScrollView scroll;
    private BluetoothServerSocket server;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);

        Button start = new Button(this);
        start.setText("Start relay");
        start.setGravity(Gravity.CENTER);
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startRelay(); }
        });

        Button stop = new Button(this);
        stop.setText("Stop relay");
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopRelay(); }
        });

        logView = new TextView(this);
        scroll = new ScrollView(this);
        scroll.addView(logView);

        root.addView(start);
        root.addView(stop);
        root.addView(scroll);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN}, 1);
        }
    }

    private void startRelay() {
        if (running) { log("Already running"); return; }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("Turn Bluetooth on first");
            return;
        }
        // Make this phone visible so the Nokia can find it (5 minutes).
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(i);

        running = true;
        new Thread(new Runnable() {
            public void run() { serve(); }
        }).start();
    }

    private void stopRelay() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        log("Stopped");
    }

    private void serve() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            // Secure socket: the Nokia must pair with this phone.
            server = adapter.listenUsingRfcommWithServiceRecord("BtRelay", APP_UUID);
            log("Relay running. Waiting for Nokia...");
            while (running) {
                final BluetoothSocket s = server.accept();
                log("Connected: " + s.getRemoteDevice().getName());
                new Thread(new Runnable() {
                    public void run() { handle(s); }
                }).start();
            }
        } catch (Exception e) {
            if (running) log("Server error: " + e);
        } finally {
            running = false;
        }
    }

    private void handle(BluetoothSocket s) {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            String url = in.readLine();
            log("Request: " + url);
            String result = fetch(url);
            OutputStream os = s.getOutputStream();
            os.write(result.getBytes("UTF-8"));
            os.flush();
            Thread.sleep(500); // let the data drain before closing
        } catch (Exception e) {
            log("Handle error: " + e);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private String fetch(String url) {
        if (url == null) return "Error: empty request";
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (BtRelay)");
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1 && bo.size() < 200000) {
                bo.write(buf, 0, n);
            }
            is.close();
            return toText(bo.toString("UTF-8"));
        } catch (Exception e) {
            return "Error: " + e;
        }
    }

    private String toText(String html) {
        String t = html
                .replaceAll("(?is)<(script|style).*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>", "\n")
                .replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
        if (t.length() > MAX_CHARS) t = t.substring(0, MAX_CHARS) + "\n[...cut]";
        return t;
    }

    private void log(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                logView.append(msg + "\n");
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRelay();
    }
}
