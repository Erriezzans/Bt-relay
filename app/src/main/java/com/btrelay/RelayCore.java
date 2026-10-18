package com.btrelay;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Port of Pihatonttu's Server.java request logic (the PC half of Hiisi Suite).
 * Pure Java: reads ONE plain-HTTP request from the phone (Hiisi opens a new
 * Bluetooth connection per request), fetches it over the internet, and writes
 * ONE HTTP response back. http->https redirects are followed here, so the
 * Symbian phone never has to do TLS.
 */
public class RelayCore {
    public interface Log { void log(String msg); }

    static final int MAX_BUFFER = 32 * 1024 * 1024;
    static final int MAX_HOPS = 8;
    static final Set<String> SKIP_REQ = new HashSet<>(Arrays.asList(
            "host", "connection", "proxy-connection", "content-length",
            "keep-alive", "proxy-authorization", "transfer-encoding"));
    static final Set<String> SKIP_RESP = new HashSet<>(Arrays.asList(
            "transfer-encoding", "connection", "content-length",
            "keep-alive", "proxy-connection"));

    public static void handle(InputStream rawIn, OutputStream out, Log log) throws IOException {
        InputStream in = new BufferedInputStream(rawIn, 8192);
        String first = readLine(in);
        if (first == null || first.trim().isEmpty()) return; // Hiisi's "connection check" ping
        String[] p = first.trim().split("\\s+", 3);
        if (p.length < 2) return;
        String method = p[0].toUpperCase(Locale.ROOT);
        String uri = p[1];

        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int i = line.indexOf(':');
            if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
        }
        byte[] body = null;
        String cl = h.get("content-length");
        if (cl != null) {
            int n = Integer.parseInt(cl.trim());
            body = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(body, off, n - off);
                if (r < 0) break;
                off += r;
            }
        }

        String target;
        if (uri.startsWith("http://") || uri.startsWith("https://")) target = uri;
        else target = "http://" + h.get("host") + uri;

        boolean headersSent = false;
        try {
            URL url = new URL(target);
            String m = method;
            byte[] b = body;
            for (int hop = 0; ; hop++) {
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(30000);
                c.setReadTimeout(30000);
                c.setRequestMethod(m);
                for (Map.Entry<String, String> e : h.entrySet())
                    if (!SKIP_REQ.contains(e.getKey())) c.setRequestProperty(e.getKey(), e.getValue());
                if (b != null || m.equals("POST") || m.equals("PUT")) {
                    byte[] data = b == null ? new byte[0] : b;
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(data.length);
                    OutputStream os = c.getOutputStream();
                    os.write(data);
                    os.flush();
                }
                int code = c.getResponseCode();

                // Follow redirects that switch scheme (http -> https) ourselves.
                if (hop < MAX_HOPS && (code == 301 || code == 302 || code == 303 || code == 307 || code == 308)) {
                    String loc = c.getHeaderField("Location");
                    if (loc != null) {
                        URL next = new URL(url, loc);
                        if (!next.getProtocol().equals(url.getProtocol())) {
                            c.disconnect();
                            if (code <= 303 && !m.equals("GET") && !m.equals("HEAD")) { m = "GET"; b = null; }
                            url = next;
                            continue;
                        }
                    }
                }
                headersSent = true;
                respond(c, m, out, log, url.toString());
                break;
            }
        } catch (UnknownHostException | SocketTimeoutException e) {
            if (!headersSent) error(out, 504, "Gateway Timeout", "Can't reach the server: " + e.getMessage());
            log.log("ERR " + target + " " + e);
        } catch (IOException e) {
            if (!headersSent) error(out, 502, "Bad Gateway", "Relay error: " + e);
            log.log("ERR " + target + " " + e);
        }
        out.flush();
    }

    private static void respond(HttpURLConnection c, String method, OutputStream out, Log log, String url) throws IOException {
        int code = c.getResponseCode();
        String msg = c.getResponseMessage();
        if (msg == null) msg = "";
        InputStream is;
        try { is = c.getInputStream(); } catch (IOException e) { is = c.getErrorStream(); }

        boolean head = method.equals("HEAD");
        boolean noBody = head || code == 204 || code == 304 || code / 100 == 1;
        long len = c.getContentLengthLong();

        StringBuilder sb = new StringBuilder("HTTP/1.1 " + code + " " + msg + "\r\n");
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            String k = e.getKey();
            if (k == null || SKIP_RESP.contains(k.toLowerCase(Locale.ROOT))) continue;
            for (String v : e.getValue()) sb.append(k).append(": ").append(v).append("\r\n");
        }

        byte[] buffered = null;
        long outLen;
        if (noBody) {
            outLen = (head && len >= 0) ? len : 0;
        } else if (len >= 0) {
            outLen = len;
        } else {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    bo.write(buf, 0, r);
                    if (bo.size() > MAX_BUFFER) break;
                }
            }
            buffered = bo.toByteArray();
            outLen = buffered.length;
        }
        sb.append("Content-Length: ").append(outLen).append("\r\nConnection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));

        long sent = 0;
        if (!noBody) {
            if (buffered != null) {
                out.write(buffered);
                sent = buffered.length;
            } else if (is != null) {
                byte[] buf = new byte[4096];
                int r;
                while (sent < outLen && (r = is.read(buf, 0, (int) Math.min(buf.length, outLen - sent))) != -1) {
                    out.write(buf, 0, r);
                    sent += r;
                }
            }
        }
        out.flush();
        if (is != null) try { is.close(); } catch (IOException ignored) {}
        c.disconnect();
        log.log(method + " " + url + " -> " + code + " (" + sent + " B)");
    }

    private static void error(OutputStream out, int code, String text, String detail) throws IOException {
        String html = "<html><head><title>" + code + "</title></head><body><h1>" + code + " " + text
                + "</h1><p>" + detail.replace("<", "&lt;") + "</p></body></html>";
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + text + "\r\nContent-Type: text/html\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            sb.append((char) c);
        }
        if (!any) return null;
        int n = sb.length();
        if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
        return sb.toString();
    }
}
