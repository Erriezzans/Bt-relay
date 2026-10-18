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
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bt-relay server: waits for a Bluetooth serial connection from a Nokia phone,
 * reads one request line, fetches the page over the internet (HTTP/HTTPS),
 * converts it to text + a link list and sends it back.
 *
 * Request : GET\t<url>\t<maxChars>\n      (a bare URL line also works)
 * Reply   : 4-byte big-endian length, then UTF-8 text:
 *           title\n finalUrl\n body [\n\u0001\n label\turl\n ...]
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {

    // Must match SVC_UUID in the Nokia MIDlet.
    static final UUID APP_UUID = UUID.fromString("a1b2c3d4-e5f6-0718-293a-4b5c6d7e8f90");
    static final int DEFAULT_MAX = 20000;
    static final int MAX_BYTES = 1500000;
    static final int MAX_LINKS = 300;
    static final String UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/100 Mobile Safari/537.36";

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
            server = adapter.listenUsingRfcommWithServiceRecord("BtRelay", APP_UUID);
            log("Relay running. Waiting for Nokia...");
            while (running) {
                final BluetoothSocket s = server.accept();
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

    private void handle(final BluetoothSocket s) {
        Thread watchdog = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
                try { s.close(); } catch (Exception ignored) {}
            }
        });
        watchdog.start();
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = in.readLine();
            log("Request: " + line);

            byte[] data = answer(line).getBytes("UTF-8");
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            log("Sent " + data.length + " bytes");

            // Wait for the Nokia to close, so no data is lost.
            while (in.read() != -1) { /* drain */ }
        } catch (Exception e) {
            log("Handle error: " + e);
        } finally {
            watchdog.interrupt();
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- request

    private String answer(String line) {
        String url = line == null ? "" : line.trim();
        int max = DEFAULT_MAX;
        if (line != null && line.startsWith("GET\t")) {
            String[] p = line.split("\t");
            if (p.length > 1) url = p[1].trim();
            if (p.length > 2) {
                try { max = Integer.parseInt(p[2].trim()); } catch (NumberFormatException ignored) {}
            }
        }
        max = Math.max(2000, Math.min(200000, max));
        return fetch(url, max);
    }

    private String fetch(String url, int max) {
        if (url.length() == 0) return reply("Error", "", "Empty request", null);
        if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
        try {
            HttpURLConnection c = null;
            String cur = url;
            // Follow redirects ourselves (HttpURLConnection will not go http -> https).
            for (int hop = 0; hop < 8; hop++) {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept-Language", "en");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) break;
                    cur = new URL(new URL(cur), loc).toString();
                    c.disconnect();
                    continue;
                }
                break;
            }
            int code = c.getResponseCode();
            String ctype = c.getContentType();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return reply("Error", cur, "HTTP " + code, null);

            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1 && bo.size() < MAX_BYTES) bo.write(buf, 0, n);
            is.close();
            byte[] bytes = bo.toByteArray();

            String lower = ctype == null ? "" : ctype.toLowerCase();
            boolean html = lower.length() == 0 || lower.contains("html");
            boolean text = html || lower.startsWith("text/") || lower.contains("json")
                    || lower.contains("xml");
            if (!text) {
                return reply("Unsupported", cur, "Cannot show this type of file: " + ctype, null);
            }
            String s = new String(bytes, pickCharset(ctype, bytes));
            if (!html) {
                return reply(cur, cur, cut(s.replace("\r", ""), max), null);
            }
            return htmlToReply(s, cur, max, code);
        } catch (Exception e) {
            return reply("Error", url, "Error: " + e, null);
        }
    }

    private Charset pickCharset(String ctype, byte[] bytes) {
        try {
            if (ctype != null) {
                Matcher m = Pattern.compile("(?i)charset=([\\w-]+)").matcher(ctype);
                if (m.find()) return Charset.forName(m.group(1));
            }
            String head = new String(bytes, 0, Math.min(bytes.length, 2048), "ISO-8859-1");
            Matcher m = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w-]+)").matcher(head);
            if (m.find()) return Charset.forName(m.group(1));
        } catch (Exception ignored) {}
        return Charset.forName("UTF-8");
    }

    // ------------------------------------------------------------------- HTML

    private String htmlToReply(String html, String base, int max, int code) {
        Matcher tm = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        String title = tm.find() ? decode(stripTags(tm.group(1))).replaceAll("\\s+", " ").trim() : "";

        String h = html.replaceAll("(?s)<!--.*?-->", " ");
        h = h.replaceAll("(?is)<(script|style|noscript|svg|head|template|iframe|object)\\b[^>]*>.*?</\\1\\s*>", " ");
        // an unclosed script/style (page cut off) must not leak into the text
        h = h.replaceAll("(?is)<(script|style|noscript|svg|template)\\b.*$", " ");

        // links -> text[n]
        List<String[]> links = new ArrayList<String[]>();
        Matcher m = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>").matcher(h);
        StringBuffer sb = new StringBuffer();
        Pattern hrefP = Pattern.compile("(?is)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
        while (m.find()) {
            String inner = decode(stripTags(m.group(2))).replaceAll("\\s+", " ").trim();
            String rep = inner;
            if (inner.length() > 0 && links.size() < MAX_LINKS) {
                Matcher hm = hrefP.matcher(m.group(1));
                if (hm.find()) {
                    String href = hm.group(1) != null ? hm.group(1)
                            : hm.group(2) != null ? hm.group(2) : hm.group(3);
                    href = decode(href).trim();
                    String lh = href.toLowerCase();
                    if (href.length() > 0 && !lh.startsWith("#") && !lh.startsWith("javascript:")
                            && !lh.startsWith("mailto:") && !lh.startsWith("tel:")
                            && !lh.startsWith("data:")) {
                        try {
                            String abs = new URL(new URL(base), href).toString();
                            links.add(new String[]{inner, abs});
                            rep = inner + "[" + links.size() + "]";
                        } catch (Exception ignored) {}
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        h = sb.toString();

        h = h.replaceAll("(?i)<li\\b[^>]*>", "\n* ");
        h = h.replaceAll("(?i)<h[1-6]\\b[^>]*>", "\n\n");
        h = h.replaceAll("(?i)<br\\s*/?>|</?(p|div|tr|table|ul|ol|section|article|header|footer|nav|form|h[1-6]|li|blockquote|pre)\\b[^>]*>", "\n");
        h = stripTags(h);
        h = decode(h);
        h = h.replace("\r", "").replace('\u00a0', ' ').replace("\u0001", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^ +| +$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        if (h.length() == 0) h = "(No readable text on this page. It may need JavaScript or a login.)";
        h = cut(h, max);

        // drop links that were cut off
        int keep = links.size();
        while (keep > 0 && !h.contains("[" + keep + "]")) keep--;
        links = new ArrayList<String[]>(links.subList(0, keep));

        if (title.length() == 0) title = base;
        if (code >= 400) title = "HTTP " + code + " - " + title;
        return reply(title, base, h, links);
    }

    private String cut(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n\n[Page cut at " + max + " characters. Raise page size in Settings.]";
    }

    private String stripTags(String s) {
        return s.replaceAll("(?s)<[^>]*>", "");
    }

    private String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String r;
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).length() > 0 ? 16 : 10);
                r = new String(Character.toChars(cp));
            } catch (Exception e) {
                r = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private String clean(String s) {
        return s.replace('\n', ' ').replace('\t', ' ').replace('\u0001', ' ').trim();
    }

    private String reply(String title, String url, String body, List<String[]> links) {
        StringBuilder sb = new StringBuilder();
        sb.append(clean(title)).append('\n').append(clean(url)).append('\n').append(body);
        if (links != null && !links.isEmpty()) {
            sb.append("\n\u0001\n");
            for (String[] l : links) {
                sb.append(clean(l[0])).append('\t').append(clean(l[1])).append('\n');
            }
        }
        return sb.toString();
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
