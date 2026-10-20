package com.erriezzans.btrelay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * "Remote browser": a real WebView on the Android phone that the Nokia drives.
 * The Nokia sends commands (open, click, scroll, type ...) and gets back a small
 * JPEG of the screen plus a few lines of page info.
 *
 * Request  : RB \t CMD \t args... \n
 * Reply    : [int metaLen][meta bytes, key=value lines][JPEG bytes]
 */
class RemoteBrowser {

    interface Log {
        void log(String msg);
    }

    static final String UA = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Page info after every command: title, url, scroll position and the focused text field. */
    static final String META_JS =
            "(function(){var d=document.documentElement,b=document.body,"
            + "sh=Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0),a=document.activeElement,ed=0,t='',v='',h='';"
            + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable)){"
            + "var ty=(a.type||'text').toLowerCase();"
            + "if(!/^(button|submit|reset|checkbox|radio|image|file|hidden|range|color)$/.test(ty)){"
            + "ed=1;t=ty;v=ty==='password'?'':(a.value!==undefined?a.value:(a.innerText||''));"
            + "h=a.getAttribute('placeholder')||a.getAttribute('aria-label')||a.name||'';}}"
            + "return JSON.stringify({t:document.title||'',u:location.href,y:window.pageYOffset||0,"
            + "h:sh,vh:window.innerHeight||0,ed:ed,et:t,ev:v,eh:h});})()";

    /** Types text into the focused field; called as TEXT_JS(text, enter). */
    static final String TEXT_JS =
            "(function(txt,enter){var a=document.activeElement;if(!a)return 'noactive';"
            + "if(a.tagName==='INPUT'||a.tagName==='TEXTAREA'){"
            + "var P=a.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;"
            + "Object.getOwnPropertyDescriptor(P,'value').set.call(a,txt);"
            + "a.dispatchEvent(new Event('input',{bubbles:true}));"
            + "a.dispatchEvent(new Event('change',{bubbles:true}));}"
            + "else if(a.isContentEditable){document.execCommand('selectAll',false,null);"
            + "document.execCommand('insertText',false,txt);}"
            + "if(enter){var f=a.form;"
            + "if(f){var s=f.querySelector('[type=submit],button:not([type])');"
            + "if(s){s.click();}else if(f.requestSubmit){f.requestSubmit();}else{f.submit();}}"
            + "else{var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
            + "a.dispatchEvent(new KeyboardEvent('keydown',o));"
            + "a.dispatchEvent(new KeyboardEvent('keypress',o));"
            + "a.dispatchEvent(new KeyboardEvent('keyup',o));}}"
            + "return 'ok';})";

    private final Activity act;
    private final FrameLayout frame;
    private final Log logger;
    private final Object lock = new Object();
    private final float density;

    private WebView web;
    private int imgW = 240;
    private int imgH = 300;
    private int vw = 320;          // page width in CSS pixels (zoom)
    private int quality = 60;
    private volatile boolean loading = false;
    private volatile long lastEvent = 0;
    private volatile String lastError = "";

    RemoteBrowser(Activity a, FrameLayout f, Log l) {
        act = a;
        frame = f;
        logger = l;
        density = a.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------ commands

    byte[] handle(String line) {
        synchronized (lock) {
            try {
                String[] p = line.split("\t", -1);
                String cmd = p.length > 1 ? p[1].trim() : "";
                logger.log("Remote: " + cmd);

                if (cmd.equals("OPEN")) {
                    String url = p[2].trim();
                    quality = clamp(Integer.parseInt(p[6].trim()), 20, 90);
                    ensureWeb(Integer.parseInt(p[3].trim()), Integer.parseInt(p[4].trim()),
                            Integer.parseInt(p[5].trim()));
                    String low = url.toLowerCase();
                    if (!low.startsWith("http://") && !low.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    final String u = url;
                    lastError = "";
                    loading = true;
                    lastEvent = System.currentTimeMillis();
                    onUi(new Runnable() {
                        public void run() {
                            web.loadUrl(u);
                        }
                    });
                    settle(600, 30000);
                    return snapshot("");
                }
                if (web == null) {
                    return reply("msg=Nothing open yet. Use Go to address.\n", null);
                }
                if (cmd.equals("SHOT")) {
                    settle(0, 3000);
                    return snapshot("");
                }
                if (cmd.equals("CLICK")) {
                    tap(Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                    settle(800, 15000);
                    return snapshot("");
                }
                if (cmd.equals("SCROLL")) {
                    scroll(Integer.parseInt(p[2].trim()));
                    settle(150, 3000);
                    return snapshot("");
                }
                if (cmd.equals("BACK") || cmd.equals("FWD") || cmd.equals("RELOAD")) {
                    if (!navigate(cmd)) {
                        return snapshot(cmd.equals("BACK") ? "No history" : "Nothing to go forward to");
                    }
                    settle(800, 20000);
                    return snapshot("");
                }
                if (cmd.equals("ZOOM")) {
                    ensureWeb(imgW, imgH, Integer.parseInt(p[2].trim()));
                    settle(300, 3000);
                    return snapshot("");
                }
                if (cmd.equals("TEXT")) {
                    boolean enter = p[2].trim().equals("1");
                    String text = p.length > 3 ? p[3] : "";
                    js(TEXT_JS + "(" + JSONObject.quote(text) + "," + (enter ? 1 : 0) + ")");
                    settle(enter ? 900 : 300, 15000);
                    return snapshot("");
                }
                return snapshot("Unknown command");
            } catch (Throwable t) {
                logger.log("Remote error: " + t);
                try {
                    return reply("msg=" + flat("Relay error: " + t) + "\n", null);
                } catch (IOException e) {
                    return new byte[0];
                }
            }
        }
    }

    void destroy() {
        try {
            onUi(new Runnable() {
                public void run() {
                    if (web != null) {
                        frame.removeView(web);
                        web.destroy();
                        web = null;
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- WebView

    private void ensureWeb(int w, int h, int z) throws Exception {
        imgW = Math.max(64, w);
        imgH = Math.max(64, h);
        vw = clamp(z, 200, 1000);
        final int pxW = Math.round(vw * density);
        final int pxH = Math.round(pxW * (float) imgH / (float) imgW);
        onUi(new Runnable() {
            public void run() {
                if (web == null) {
                    web = makeWeb();
                    frame.addView(web, 0, new FrameLayout.LayoutParams(pxW, pxH));
                } else {
                    ViewGroup.LayoutParams lp = web.getLayoutParams();
                    lp.width = pxW;
                    lp.height = pxH;
                    web.setLayoutParams(lp);
                }
            }
        });
        Thread.sleep(300);   // let it lay out at the new size
    }

    private WebView makeWeb() {
        WebView v = new WebView(act);
        v.setBackgroundColor(Color.WHITE);
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        v.setVerticalScrollBarEnabled(false);
        v.setHorizontalScrollBarEnabled(false);

        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(UA);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(v, true);

        v.setWebViewClient(new WebViewClient() {
            private boolean blocked(String url) {
                String u = url.toLowerCase();
                // keep http(s) inside the WebView; ignore app deep links (intent:, fb:, market: ...)
                return !(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:"));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                return blocked(r.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blocked(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loading = true;
                lastEvent = System.currentTimeMillis();
                lastError = "";
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loading = false;
                lastEvent = System.currentTimeMillis();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    lastError = "Page error: " + e.getDescription();
                }
            }
        });
        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                lastEvent = System.currentTimeMillis();
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String def,
                                      JsPromptResult result) {
                result.confirm(def == null ? "" : def);
                return true;
            }
        });
        return v;
    }

    /** Waits until the page has been quiet for a moment (or maxMs passed). */
    private void settle(long minMs, long maxMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        Thread.sleep(minMs);
        while (System.currentTimeMillis() - start < maxMs) {
            if (!loading && System.currentTimeMillis() - lastEvent > 500) {
                break;
            }
            Thread.sleep(100);
        }
    }

    private boolean navigate(final String what) throws Exception {
        final boolean[] ok = new boolean[1];
        onUi(new Runnable() {
            public void run() {
                if (what.equals("BACK")) {
                    if (web.canGoBack()) {
                        web.goBack();
                        ok[0] = true;
                    }
                } else if (what.equals("FWD")) {
                    if (web.canGoForward()) {
                        web.goForward();
                        ok[0] = true;
                    }
                } else {
                    web.reload();
                    ok[0] = true;
                }
            }
        });
        if (ok[0]) {
            lastEvent = System.currentTimeMillis();
        }
        return ok[0];
    }

    // --------------------------------------------------------------- input

    private void tap(float ix, float iy) throws Exception {
        float x = ix * web.getWidth() / imgW;
        float y = iy * web.getHeight() / imgH;
        long down = SystemClock.uptimeMillis();
        touch(down, down, MotionEvent.ACTION_DOWN, x, y);
        Thread.sleep(70);
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
        hideKeyboard();
    }

    private void touch(final long down, final long t, final int action, final float x, final float y)
            throws Exception {
        onUi(new Runnable() {
            public void run() {
                MotionEvent e = MotionEvent.obtain(down, t, action, x, y, 0);
                web.requestFocus();
                web.dispatchTouchEvent(e);
                e.recycle();
            }
        });
    }

    private void swipe(float x, float y0, float y1) throws Exception {
        long down = SystemClock.uptimeMillis();
        touch(down, down, MotionEvent.ACTION_DOWN, x, y0);
        int steps = 6;
        for (int i = 1; i <= steps; i++) {
            Thread.sleep(40);
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y0 + (y1 - y0) * i / steps);
        }
        Thread.sleep(120);   // pause before lifting so it does not fling
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y1);
    }

    private int scrollY() throws Exception {
        String r = js("Math.round(window.pageYOffset||0)");
        try {
            return Integer.parseInt(r.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** dyImg: pixels of the Nokia image to scroll (positive = down). */
    private void scroll(int dyImg) throws Exception {
        int before = scrollY();
        int css = Math.round(dyImg * (float) vw / imgW);
        js("window.scrollBy(0," + css + ")");
        Thread.sleep(150);
        if (scrollY() == before && (dyImg > 0 || before > 0)) {
            // the page scrolls inside an inner container: drag it like a finger would
            float x = web.getWidth() / 2f;
            float h = web.getHeight();
            if (dyImg > 0) {
                swipe(x, h * 0.75f, h * 0.25f);
            } else {
                swipe(x, h * 0.25f, h * 0.75f);
            }
        }
    }

    private void hideKeyboard() {
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    InputMethodManager im =
                            (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (im != null && web != null) {
                        im.hideSoftInputFromWindow(web.getWindowToken(), 0);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------ snapshot

    private byte[] snapshot(String err) throws Exception {
        StringBuilder m = new StringBuilder();
        String raw = js(META_JS);
        try {
            Object o = new JSONTokener(raw == null ? "null" : raw).nextValue();
            if (o instanceof String) {
                JSONObject j = new JSONObject((String) o);
                m.append("title=").append(flat(j.optString("t", ""))).append('\n');
                m.append("url=").append(flat(j.optString("u", ""))).append('\n');
                int y = j.optInt("y", 0);
                int h = j.optInt("h", 0);
                int vh = j.optInt("vh", 0);
                int pct = (h <= 0 || h <= vh) ? 100 : (int) Math.min(100L, (y + (long) vh) * 100L / h);
                m.append("pct=").append(pct).append('\n');
                if (j.optInt("ed", 0) == 1) {
                    m.append("edit=1\n");
                    m.append("etype=").append(flat(j.optString("et", "text"))).append('\n');
                    m.append("ehint=").append(cut(flat(j.optString("eh", "")), 40)).append('\n');
                    m.append("evalue=").append(cut(flat(j.optString("ev", "")), 400)).append('\n');
                }
            }
        } catch (Exception ignored) {
        }
        if (err.length() == 0 && lastError.length() > 0) {
            err = lastError;
        }
        if (err.length() > 0) {
            m.append("msg=").append(flat(err)).append('\n');
        }

        final Bitmap bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888);
        onUi(new Runnable() {
            public void run() {
                int ww = web.getWidth();
                int wh = web.getHeight();
                float sx = ww > 0 ? imgW / (float) ww : 1f;
                float sy = wh > 0 ? imgH / (float) wh : 1f;
                int scx = web.getScrollX();
                int scy = web.getScrollY();
                // WebView.draw() expects the canvas to be moved by the scroll offset already
                drawWeb(bmp, sx, sy, -scx, -scy);
                if ((scx != 0 || scy != 0) && isBlank(bmp)) {
                    logger.log("Remote: blank after scroll, retrying without offset");
                    drawWeb(bmp, sx, sy, 0, 0);
                }
            }
        });
        ByteArrayOutputStream jb = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, jb);
        bmp.recycle();
        byte[] jpeg = jb.toByteArray();
        logger.log("Remote screen: " + imgW + "x" + imgH + ", " + jpeg.length + " bytes");
        return reply(m.toString(), jpeg);
    }

    private void drawWeb(Bitmap bmp, float sx, float sy, float tx, float ty) {
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        c.scale(sx, sy);
        c.translate(tx, ty);
        web.draw(c);
    }

    /** True when (a sample of) the picture is plain white. */
    private static boolean isBlank(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w];
        int stepY = Math.max(1, h / 24);
        for (int y = 0; y < h; y += stepY) {
            b.getPixels(px, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += 3) {
                if ((px[x] & 0xFFFFFF) != 0xFFFFFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private byte[] reply(String meta, byte[] jpeg) throws IOException {
        byte[] mb = meta.getBytes("UTF-8");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeInt(mb.length);
        d.write(mb);
        if (jpeg != null) {
            d.write(jpeg);
        }
        d.flush();
        return bo.toByteArray();
    }

    // -------------------------------------------------------------- helpers

    private String js(final String script) throws InterruptedException {
        final CountDownLatch l = new CountDownLatch(1);
        final String[] out = new String[1];
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript(script, new ValueCallback<String>() {
                        public void onReceiveValue(String value) {
                            out[0] = value;
                            l.countDown();
                        }
                    });
                } catch (Throwable t) {
                    l.countDown();
                }
            }
        });
        l.await(8, TimeUnit.SECONDS);
        return out[0];
    }

    /** Runs r on the UI thread and waits for it to finish. */
    private void onUi(final Runnable r) throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        final Throwable[] err = new Throwable[1];
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    err[0] = t;
                } finally {
                    l.countDown();
                }
            }
        });
        if (!l.await(15, TimeUnit.SECONDS)) {
            throw new IOException("UI thread busy");
        }
        if (err[0] != null) {
            throw new Exception(err[0]);
        }
    }

    private static String flat(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
