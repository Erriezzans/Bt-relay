package com.erriezzans.btrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

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

    static final String NO_TEXT =
            "(No readable text on this page. It may need JavaScript or a login.)";

    // Runs inside the hidden WebView after the page has loaded: numbers the links
    // like the static converter does and returns the visible text as JSON.
    static final String EXTRACT_JS =
            "(function(){var L=[],els=document.querySelectorAll('a[href],img');"
            + "function alt(x){var s=(x.getAttribute('alt')||x.getAttribute('title')||'')"
            + ".replace(/\\s+/g,' ').trim();return s.length>30?s.substring(0,30):s;}"
            + "for(var i=0;i<els.length&&L.length<" + MAX_LINKS + ";i++){var e=els[i];"
            + "if(e.tagName==='IMG'){"
            + "if(e.closest('a[href]')||e.offsetParent===null)continue;"
            + "var aw=parseInt(e.getAttribute('width')),ah=parseInt(e.getAttribute('height'));"
            + "if((aw>0&&aw<40)||(ah>0&&ah<40))continue;"
            + "var src=e.currentSrc||e.getAttribute('data-src')||e.src||'';"
            + "if(!src||/^data:/i.test(src))continue;"
            + "var a1=alt(e),lab=a1?'(Image: '+a1+')':'(Image)';"
            + "L.push([lab,'IMG:'+new URL(src,document.baseURI).href]);"
            + "e.parentNode.insertBefore(document.createTextNode(' '+lab+'['+L.length+'] '),e.nextSibling);"
            + "}else{"
            + "var t=(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim(),"
            + "r=e.getAttribute('href')||'',h=e.href||'',pre='';"
            + "if(!h||r.charAt(0)==='#'||/^(javascript:|mailto:|tel:|data:)/i.test(h))continue;"
            + "if(!t){var im=e.querySelector('img');if(!im)continue;"
            + "var a2=alt(im);t=a2?'(Link: '+a2+')':'(Image link)';pre=t;}"
            + "L.push([t,h]);e.appendChild(document.createTextNode(pre+'['+L.length+']'));}}"
            + "return JSON.stringify({title:document.title||'',url:location.href,"
            + "text:document.body?document.body.innerText:'',links:L});})()";

    private TextView logView;
    private WebView web;
    private final Object webLock = new Object();
    private volatile boolean alwaysRender = false;
    private RemoteBrowser remoteBrowser;
    private ScrollView scroll;
    private BluetoothServerSocket server;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Keep cookies between requests (consent banners, sessions, redirects).
        CookieHandler.setDefault(new java.net.CookieManager());

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

        CheckBox render = new CheckBox(this);
        render.setText("Render pages with JavaScript (slower)");
        render.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { alwaysRender = on; }
        });

        // Hidden 1x1 WebView used to render JavaScript pages for the Nokia.
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(UA);
        ws.setBlockNetworkImage(true);

        logView = new TextView(this);
        scroll = new ScrollView(this);
        scroll.addView(logView);

        root.addView(start);
        root.addView(stop);
        root.addView(render);
        root.addView(web, new LinearLayout.LayoutParams(1, 1));
        root.addView(scroll);
        // The remote-browser WebView lives behind the normal screen (an opaque background hides it).
        root.setBackgroundColor(0xFF000000);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        remoteBrowser = new RemoteBrowser(this, frame, new RemoteBrowser.Log() {
            public void log(String m) {
                MainActivity.this.log(m);
            }
        });

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
            // never show what was typed into web pages (passwords) in the log
            log("Request: " + (line != null && line.startsWith("RB\tTEXT") ? "RB TEXT (hidden)" : line));

            byte[] data;
            if (line != null && line.startsWith("RB\t")) {
                data = remoteBrowser.handle(line);
            } else if (line != null && line.startsWith("IMG\t")) {
                data = image(line);
            } else {
                data = answer(line).getBytes("UTF-8");
            }
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
        if (alwaysRender) {
            String w = renderWeb(url, max);
            if (w != null) return w;
        }
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
            String r = htmlToReply(s, cur, max, code);
            if (!alwaysRender && r.contains(NO_TEXT)) {
                // The plain converter found nothing: the page probably needs JavaScript.
                String w = renderWeb(cur, max);
                if (w != null) return w;
            }
            return r;
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

    // ---------------------------------------------------------------- WebView

    /** Loads the page in the hidden WebView, waits for scripts to settle, returns a reply or null. */
    private String renderWeb(final String url, final int max) {
        synchronized (webLock) {
            log("Rendering with WebView: " + url);
            final CountDownLatch done = new CountDownLatch(1);
            final String[] raw = new String[1];
            final int[] seq = new int[1];

            runOnUiThread(new Runnable() {
                public void run() {
                    web.stopLoading();
                    web.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(final WebView v, String u) {
                            // Redirects fire this several times: only the last one extracts.
                            final int my = ++seq[0];
                            v.postDelayed(new Runnable() {
                                public void run() {
                                    if (my != seq[0] || done.getCount() == 0) return;
                                    v.evaluateJavascript(EXTRACT_JS, new ValueCallback<String>() {
                                        public void onReceiveValue(String value) {
                                            raw[0] = value;
                                            done.countDown();
                                        }
                                    });
                                }
                            }, 1500);
                        }

                        @Override
                        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                            if (r.isForMainFrame()) done.countDown(); // give up, fall back to plain fetch
                        }
                    });
                    web.loadUrl(url);
                }
            });

            boolean ok = false;
            try { ok = done.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

            runOnUiThread(new Runnable() {
                public void run() {
                    web.setWebViewClient(new WebViewClient());
                    web.loadUrl("about:blank");
                }
            });
            if (!ok || raw[0] == null) return null;

            try {
                Object o = new JSONTokener(raw[0]).nextValue();
                if (!(o instanceof String)) return null;
                JSONObject j = new JSONObject((String) o);
                String title = j.optString("title", "").trim();
                String finalUrl = j.optString("url", url);
                String text = j.optString("text", "")
                        .replace("\r", "").replace('\u00a0', ' ').replace("\u0001", " ")
                        .replaceAll("[ \\t]+", " ")
                        .replaceAll("(?m)^ +| +$", "")
                        .replaceAll("\\n{3,}", "\n\n")
                        .trim();
                if (text.length() == 0) return null;

                List<String[]> links = new ArrayList<String[]>();
                JSONArray la = j.optJSONArray("links");
                if (la != null) {
                    for (int i = 0; i < la.length(); i++) {
                        JSONArray l = la.getJSONArray(i);
                        links.add(new String[]{l.getString(0), l.getString(1)});
                    }
                }
                text = cut(text, max);
                int keep = links.size();
                while (keep > 0 && !text.contains("[" + keep + "]")) keep--;
                links = new ArrayList<String[]>(links.subList(0, keep));

                if (title.length() == 0) title = finalUrl;
                return reply(title, finalUrl, text, links);
            } catch (Exception e) {
                log("WebView parse error: " + e);
                return null;
            }
        }
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
        Matcher m = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>|<img\\b([^>]*)>").matcher(h);
        StringBuffer sb = new StringBuffer();
        Pattern hrefP = Pattern.compile("(?is)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
        while (m.find()) {
            if (m.group(3) != null) {   // <img>
                m.appendReplacement(sb, Matcher.quoteReplacement(imageItem(m.group(3), base, links)));
                continue;
            }
            String inner = decode(stripTags(m.group(2))).replaceAll("\\s+", " ").trim();
            boolean imgOnly = false;
            if (inner.length() == 0) {   // a link that only holds a picture
                Matcher im = Pattern.compile("(?is)<img\\b([^>]*)>").matcher(m.group(2));
                if (im.find()) {
                    String alt = altText(im.group(1));
                    inner = alt.length() > 0 ? "(Link: " + alt + ")" : "(Image link)";
                    imgOnly = true;
                }
            }
            String rep = imgOnly ? "" : inner;
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

        if (h.length() == 0) h = NO_TEXT;
        h = cut(h, max);

        // drop links that were cut off
        int keep = links.size();
        while (keep > 0 && !h.contains("[" + keep + "]")) keep--;
        links = new ArrayList<String[]>(links.subList(0, keep));

        if (title.length() == 0) title = base;
        if (code >= 400) title = "HTTP " + code + " - " + title;
        return reply(title, base, h, links);
    }

    private String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?is)(?:^|\\s)" + name
                + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(tag);
        if (!m.find()) return "";
        String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        return decode(v).trim();
    }

    private String altText(String tag) {
        String a = attr(tag, "alt");
        if (a.length() == 0) a = attr(tag, "title");
        a = a.replaceAll("\\s+", " ").trim();
        return a.length() > 30 ? a.substring(0, 30) : a;
    }

    private int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    /** Turns an <img> into a selectable "(Image: alt)[n]" item; the link target is IMG:<url>. */
    private String imageItem(String tag, String base, List<String[]> links) {
        if (links.size() >= MAX_LINKS) return "";
        String src = attr(tag, "data-src");
        if (src.length() == 0) src = attr(tag, "src");
        if (src.length() == 0 || src.toLowerCase().startsWith("data:")) return "";
        int w = num(attr(tag, "width")), hh = num(attr(tag, "height"));
        if ((w > 0 && w < 40) || (hh > 0 && hh < 40)) return "";   // icons, tracking pixels
        String abs;
        try {
            abs = new URL(new URL(base), src).toString();
        } catch (Exception e) {
            return "";
        }
        String alt = altText(tag);
        String label = alt.length() > 0 ? "(Image: " + alt + ")" : "(Image)";
        links.add(new String[]{label, "IMG:" + abs});
        return " " + label + "[" + links.size() + "] ";
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

    // ----------------------------------------------------------------- images

    /** Request: IMG\t<url>\t<maxW>\t<maxH>. Reply bytes: a JPEG that fits the box, or empty on failure. */
    private byte[] image(String line) {
        try {
            String[] p = line.split("\t");
            String url = p[1].trim();
            int mw = p.length > 2 ? Integer.parseInt(p[2].trim()) : 240;
            int mh = p.length > 3 ? Integer.parseInt(p[3].trim()) : 320;
            mw = Math.max(32, Math.min(800, mw));
            mh = Math.max(32, Math.min(800, mh));
            log("Image: " + url);

            byte[] raw = download(url, 6000000);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return new byte[0];
            int ss = 1;
            while (o.outWidth / (ss * 2) >= mw && o.outHeight / (ss * 2) >= mh) ss *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = ss;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (bmp == null) return new byte[0];

            float sc = Math.min(1f, Math.min(mw / (float) bmp.getWidth(), mh / (float) bmp.getHeight()));
            if (sc < 1f) {
                bmp = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bmp.getWidth() * sc)),
                        Math.max(1, Math.round(bmp.getHeight() * sc)), true);
            }
            // JPEG has no transparency: draw on white first.
            Bitmap flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.RGB_565);
            Canvas cv = new Canvas(flat);
            cv.drawColor(Color.WHITE);
            cv.drawBitmap(bmp, 0, 0, null);

            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            flat.compress(Bitmap.CompressFormat.JPEG, 70, bo);
            byte[] out = bo.toByteArray();
            log("Image sent: " + flat.getWidth() + "x" + flat.getHeight() + ", " + out.length + " bytes");
            return out.length > 390000 ? new byte[0] : out;
        } catch (Throwable t) {
            log("Image error: " + t);
            return new byte[0];
        }
    }

    private byte[] download(String url, int maxBytes) throws Exception {
        String cur = url;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 8; hop++) {
            URL u = new URL(cur);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "image/*,*/*;q=0.5");
            c.setRequestProperty("Referer", u.getProtocol() + "://" + u.getHost() + "/");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                if (loc == null) break;
                cur = new URL(u, loc).toString();
                c.disconnect();
                continue;
            }
            break;
        }
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        InputStream is = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1 && bo.size() < maxBytes) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
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
        if (remoteBrowser != null) remoteBrowser.destroy();
    }
}
