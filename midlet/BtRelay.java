import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * Bt-relay browser for Nokia (CLDC 1.1 / MIDP 2.0 / JSR-82).
 *
 * NOTE: CLDC 1.1 has no StringBuilder, so this file never uses '+' on Strings
 * (javac would turn it into StringBuilder). Use cat() / StringBuffer instead.
 */
public class BtRelay extends MIDlet implements CommandListener {

    // Must match APP_UUID in the Android app (without dashes).
    private static final String SVC_UUID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
    private static final int[] PAGE_CHARS = {8000, 20000, 50000, 100000};

    private static final String HELP =
            "Bt-relay browser\n\n"
            + "KEYS\n"
            + "Up/Down: scroll line\n"
            + "2 / 8: page up / down\n"
            + "4 / 6 or Left/Right: fast jump (10%)\n"
            + "1 / 7: top / bottom\n"
            + "5 or Select: list of links\n"
            + "3: go to address\n"
            + "9: search\n"
            + "*: back\n"
            + "#: reload\n"
            + "0: home / this help\n\n"
            + "Left softkey = Options menu.\n\n"
            + "Press 3 and type an address, for example: example.com\n\n"
            + "Tip: use light sites, for example mbasic.facebook.com or wikipedia.org";

    // settings (saved)
    private int fontSize = 1;   // 0 small, 1 medium, 2 large
    private int theme = 0;      // 0 dark, 1 light
    private int pageIdx = 1;    // index in PAGE_CHARS
    private String home = "";

    // state
    private Display display;
    private View view;
    private String relayUrl = null;
    private volatile boolean busy = false;
    private String curUrl = "";
    private Vector history = new Vector();
    private Vector links = new Vector();   // each element: String[]{label, url}

    // screens
    private TextBox inputBox;
    private int inputMode = 0;             // 0 url, 1 search
    private List linkList;
    private Form settingsForm;
    private ChoiceGroup fontCg;
    private ChoiceGroup themeCg;
    private ChoiceGroup pageCg;
    private TextField homeField;

    // commands
    private Command urlCmd = new Command("Go to address", Command.SCREEN, 1);
    private Command searchCmd = new Command("Search", Command.SCREEN, 2);
    private Command linksCmd = new Command("Links", Command.SCREEN, 3);
    private Command reloadCmd = new Command("Reload", Command.SCREEN, 4);
    private Command homeCmd = new Command("Home / Help", Command.SCREEN, 5);
    private Command settingsCmd = new Command("Settings", Command.SCREEN, 6);
    private Command exitCmd = new Command("Exit", Command.EXIT, 7);
    private Command backCmd = new Command("Back", Command.BACK, 1);
    private Command okCmd = new Command("OK", Command.OK, 1);
    private Command cancelCmd = new Command("Cancel", Command.CANCEL, 2);
    private Command saveCmd = new Command("Save", Command.OK, 1);

    // ------------------------------------------------------------ lifecycle

    public void startApp() {
        if (view == null) {
            display = Display.getDisplay(this);
            loadSettings();
            view = new View();
            view.addCommand(urlCmd);
            view.addCommand(searchCmd);
            view.addCommand(linksCmd);
            view.addCommand(reloadCmd);
            view.addCommand(homeCmd);
            view.addCommand(settingsCmd);
            view.addCommand(exitCmd);
            view.addCommand(backCmd);
            view.setCommandListener(this);
            view.setPage("Bt-relay", HELP);
            if (home.length() > 0) {
                load(home, false);
            }
        }
        display.setCurrent(view);
    }

    public void pauseApp() {
    }

    public void destroyApp(boolean unconditional) {
    }

    // ------------------------------------------------------------- commands

    public void commandAction(Command c, Displayable d) {
        if (d == view) {
            if (c == urlCmd) {
                askUrl();
            } else if (c == searchCmd) {
                askSearch();
            } else if (c == linksCmd) {
                showLinks();
            } else if (c == backCmd) {
                goBack();
            } else if (c == reloadCmd) {
                reload();
            } else if (c == homeCmd) {
                goHome();
            } else if (c == settingsCmd) {
                showSettings();
            } else if (c == exitCmd) {
                destroyApp(true);
                notifyDestroyed();
            }
        } else if (d == inputBox) {
            if (c == okCmd) {
                String t = inputBox.getString().trim();
                display.setCurrent(view);
                if (t.length() > 0) {
                    if (inputMode == 0) {
                        if (t.indexOf("://") < 0) {
                            t = cat("http://", t);
                        }
                        load(t, true);
                    } else {
                        load(cat("https://lite.duckduckgo.com/lite/?q=", urlEncode(t)), true);
                    }
                }
            } else {
                display.setCurrent(view);
            }
        } else if (d == linkList) {
            if (c == List.SELECT_COMMAND) {
                int idx = linkList.getSelectedIndex();
                display.setCurrent(view);
                if (idx >= 0 && idx < links.size()) {
                    String[] lk = (String[]) links.elementAt(idx);
                    load(lk[1], true);
                }
            } else {
                display.setCurrent(view);
            }
        } else if (d == settingsForm) {
            if (c == saveCmd) {
                fontSize = fontCg.getSelectedIndex();
                theme = themeCg.getSelectedIndex();
                pageIdx = pageCg.getSelectedIndex();
                home = homeField.getString().trim();
                saveSettings();
                view.applyStyle();
            }
            display.setCurrent(view);
        }
    }

    private void askUrl() {
        String start = curUrl.length() > 0 ? curUrl : "http://";
        inputBox = new TextBox("Go to address", start, 512, TextField.URL);
        inputBox.addCommand(okCmd);
        inputBox.addCommand(cancelCmd);
        inputBox.setCommandListener(this);
        inputMode = 0;
        display.setCurrent(inputBox);
    }

    private void askSearch() {
        inputBox = new TextBox("Search", "", 200, TextField.ANY);
        inputBox.addCommand(okCmd);
        inputBox.addCommand(cancelCmd);
        inputBox.setCommandListener(this);
        inputMode = 1;
        display.setCurrent(inputBox);
    }

    private void showLinks() {
        if (links.size() == 0) {
            Alert a = new Alert("Links", "No links on this page", null, AlertType.INFO);
            a.setTimeout(1500);
            display.setCurrent(a, view);
            return;
        }
        linkList = new List("Links", List.IMPLICIT);
        for (int i = 0; i < links.size(); i++) {
            String[] lk = (String[]) links.elementAt(i);
            linkList.append(cat(Integer.toString(i + 1), cat(". ", lk[0])), null);
        }
        linkList.addCommand(backCmd);
        linkList.setCommandListener(this);
        display.setCurrent(linkList);
    }

    private void showSettings() {
        settingsForm = new Form("Settings");
        fontCg = new ChoiceGroup("Text size", Choice.EXCLUSIVE,
                new String[]{"Small", "Medium", "Large"}, null);
        fontCg.setSelectedIndex(fontSize, true);
        themeCg = new ChoiceGroup("Theme", Choice.EXCLUSIVE,
                new String[]{"Dark", "Light"}, null);
        themeCg.setSelectedIndex(theme, true);
        pageCg = new ChoiceGroup("Page size (characters)", Choice.EXCLUSIVE,
                new String[]{"8,000 (fast)", "20,000", "50,000", "100,000 (slow)"}, null);
        pageCg.setSelectedIndex(pageIdx, true);
        homeField = new TextField("Home page (empty = help)", home, 256, TextField.URL);
        settingsForm.append(fontCg);
        settingsForm.append(themeCg);
        settingsForm.append(pageCg);
        settingsForm.append(homeField);
        settingsForm.addCommand(saveCmd);
        settingsForm.addCommand(cancelCmd);
        settingsForm.setCommandListener(this);
        display.setCurrent(settingsForm);
    }

    private void goBack() {
        if (history.isEmpty()) {
            view.setStatus("No history");
            return;
        }
        String u = (String) history.lastElement();
        history.removeElementAt(history.size() - 1);
        load(u, false);
    }

    private void reload() {
        if (curUrl.length() > 0) {
            load(curUrl, false);
        }
    }

    private void goHome() {
        if (home.length() > 0) {
            load(home, true);
        } else {
            curUrl = "";
            links.removeAllElements();
            view.setPage("Bt-relay", HELP);
        }
    }

    // -------------------------------------------------------------- loading

    private void load(final String url, final boolean push) {
        if (busy) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                doLoad(url, push);
            }
        }).start();
    }

    private void doLoad(String url, boolean push) {
        busy = true;
        try {
            view.setStatus("Connecting...");
            String reply = request(url);
            view.setStatus("Reading page...");

            String title = url;
            String finalUrl = url;
            String body = reply;
            Vector newLinks = new Vector();

            int p1 = reply.indexOf('\n');
            int p2 = p1 < 0 ? -1 : reply.indexOf('\n', p1 + 1);
            if (p1 >= 0 && p2 >= 0) {
                title = reply.substring(0, p1);
                finalUrl = reply.substring(p1 + 1, p2);
                body = reply.substring(p2 + 1);
                int d = body.indexOf('\u0001');
                if (d >= 0) {
                    String l = body.substring(d + 1);
                    body = body.substring(0, d);
                    int pos = 0;
                    int len = l.length();
                    while (pos < len) {
                        int e = l.indexOf('\n', pos);
                        if (e < 0) {
                            e = len;
                        }
                        String line = l.substring(pos, e);
                        int t = line.indexOf('\t');
                        if (t > 0) {
                            newLinks.addElement(new String[]{line.substring(0, t), line.substring(t + 1)});
                        }
                        pos = e + 1;
                    }
                }
            }
            if (finalUrl.length() == 0) {
                finalUrl = url;
            }

            if (push && curUrl.length() > 0) {
                history.addElement(curUrl);
                if (history.size() > 30) {
                    history.removeElementAt(0);
                }
            }
            curUrl = finalUrl;
            links = newLinks;
            view.setPage(title, body);
        } catch (Throwable t) {
            view.setPage("Error", String.valueOf(t));
        } finally {
            busy = false;
        }
    }

    private StreamConnection open() throws IOException {
        if (relayUrl == null) {
            view.setStatus("Searching for relay...");
            DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
            relayUrl = agent.selectService(
                    new UUID(SVC_UUID, false),
                    ServiceRecord.AUTHENTICATE_ENCRYPT,
                    false);
            if (relayUrl == null) {
                throw new IOException("Relay not found. Start the Android app, tap Start relay and make it discoverable.");
            }
        }
        return (StreamConnection) Connector.open(relayUrl);
    }

    private String request(String url) throws IOException {
        StreamConnection conn = null;
        OutputStream os = null;
        DataInputStream dis = null;
        try {
            try {
                conn = open();
            } catch (IOException e) {
                relayUrl = null;       // stale address: search again once
                conn = open();
            }
            os = conn.openOutputStream();
            StringBuffer req = new StringBuffer();
            req.append("GET\t").append(url).append('\t');
            req.append(Integer.toString(PAGE_CHARS[pageIdx])).append('\n');
            os.write(req.toString().getBytes("UTF-8"));
            os.flush();

            view.setStatus("Waiting for page...");
            dis = conn.openDataInputStream();
            int len = dis.readInt();
            if (len < 0 || len > 3000000) {
                throw new IOException("Bad reply from relay");
            }
            byte[] data = new byte[len];
            dis.readFully(data);
            return new String(data, "UTF-8");
        } catch (IOException e) {
            relayUrl = null;
            throw e;
        } finally {
            if (dis != null) {
                try {
                    dis.close();
                } catch (Exception ignored) {
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------- settings

    private void loadSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore("btrelay", true);
            if (rs.getNumRecords() > 0) {
                byte[] d = rs.getRecord(1);
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
                int f = in.readInt();
                int t = in.readInt();
                int p = in.readInt();
                String h = in.readUTF();
                if (f >= 0 && f <= 2) {
                    fontSize = f;
                }
                if (t >= 0 && t <= 1) {
                    theme = t;
                }
                if (p >= 0 && p < PAGE_CHARS.length) {
                    pageIdx = p;
                }
                home = h;
            }
        } catch (Exception e) {
            // keep defaults
        } finally {
            if (rs != null) {
                try {
                    rs.closeRecordStore();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void saveSettings() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(fontSize);
            out.writeInt(theme);
            out.writeInt(pageIdx);
            out.writeUTF(home);
            byte[] d = bo.toByteArray();
            rs = RecordStore.openRecordStore("btrelay", true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(d, 0, d.length);
            } else {
                rs.setRecord(1, d, 0, d.length);
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (rs != null) {
                try {
                    rs.closeRecordStore();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private static String cat(String a, String b) {
        return a.concat(b);
    }

    private static String urlEncode(String s) {
        StringBuffer sb = new StringBuffer();
        try {
            byte[] b = s.getBytes("UTF-8");
            String hex = "0123456789ABCDEF";
            for (int i = 0; i < b.length; i++) {
                int c = b[i] & 0xff;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.') {
                    sb.append((char) c);
                } else if (c == ' ') {
                    sb.append('+');
                } else {
                    sb.append('%');
                    sb.append(hex.charAt(c >> 4));
                    sb.append(hex.charAt(c & 15));
                }
            }
        } catch (Exception e) {
            return s;
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- view

    private class View extends Canvas {
        private static final int SB_W = 4;

        private Vector lines = new Vector();
        private int top = 0;
        private Font font;
        private int fg;
        private int bg;
        private int barBg;
        private int barFg;
        private String title = "";
        private String raw = "";
        private String status = null;
        private int lastW = -1;

        View() {
            applyStyle();
        }

        void applyStyle() {
            int size = fontSize == 0 ? Font.SIZE_SMALL
                    : (fontSize == 1 ? Font.SIZE_MEDIUM : Font.SIZE_LARGE);
            font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, size);
            if (theme == 0) {
                bg = 0x101018;
                fg = 0xE8E8E8;
                barBg = 0x2A3A66;
                barFg = 0xFFFFFF;
            } else {
                bg = 0xFFFFFF;
                fg = 0x101010;
                barBg = 0x3355AA;
                barFg = 0xFFFFFF;
            }
            lastW = -1;
            repaint();
        }

        void setPage(String t, String body) {
            title = t;
            raw = body;
            status = null;
            top = 0;
            lastW = -1;
            repaint();
        }

        void setStatus(String s) {
            status = s;
            repaint();
        }

        private int rows() {
            int lh = font.getHeight() + 1;
            int barH = font.getHeight() + 2;
            int r = (getHeight() - 2 * barH) / lh;
            return r < 1 ? 1 : r;
        }

        private void rewrap() {
            lines = wrap(raw, getWidth() - SB_W - 4);
        }

        private Vector wrap(String text, int maxW) {
            Vector out = new Vector();
            if (maxW < 20) {
                maxW = 20;
            }
            int spaceW = font.charWidth(' ');
            int len = text.length();
            int pos = 0;
            while (pos <= len) {
                int e = text.indexOf('\n', pos);
                if (e < 0) {
                    e = len;
                }
                wrapPara(text.substring(pos, e), maxW, spaceW, out);
                pos = e + 1;
            }
            return out;
        }

        private void wrapPara(String p, int maxW, int spaceW, Vector out) {
            if (p.length() == 0) {
                out.addElement("");
                return;
            }
            StringBuffer line = new StringBuffer();
            int lineW = 0;
            int i = 0;
            int n = p.length();
            while (i < n) {
                while (i < n && p.charAt(i) == ' ') {
                    i++;
                }
                if (i >= n) {
                    break;
                }
                int j = i;
                while (j < n && p.charAt(j) != ' ') {
                    j++;
                }
                String word = p.substring(i, j);
                i = j;
                int ww = font.stringWidth(word);
                if (ww > maxW) {
                    if (line.length() > 0) {
                        out.addElement(line.toString());
                        line.setLength(0);
                        lineW = 0;
                    }
                    StringBuffer part = new StringBuffer();
                    int pw = 0;
                    for (int k = 0; k < word.length(); k++) {
                        char ch = word.charAt(k);
                        int cw = font.charWidth(ch);
                        if (pw + cw > maxW && part.length() > 0) {
                            out.addElement(part.toString());
                            part.setLength(0);
                            pw = 0;
                        }
                        part.append(ch);
                        pw += cw;
                    }
                    line.append(part.toString());
                    lineW = pw;
                    continue;
                }
                if (line.length() == 0) {
                    line.append(word);
                    lineW = ww;
                } else if (lineW + spaceW + ww <= maxW) {
                    line.append(' ');
                    line.append(word);
                    lineW += spaceW + ww;
                } else {
                    out.addElement(line.toString());
                    line.setLength(0);
                    line.append(word);
                    lineW = ww;
                }
            }
            if (line.length() > 0) {
                out.addElement(line.toString());
            }
        }

        private String fit(String s, int maxW) {
            if (font.stringWidth(s) <= maxW) {
                return s;
            }
            int n = s.length();
            while (n > 1 && font.stringWidth(s.substring(0, n).concat("...")) > maxW) {
                n--;
            }
            return s.substring(0, n).concat("...");
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            if (w != lastW) {
                lastW = w;
                rewrap();
            }
            Vector ls = lines;
            int lh = font.getHeight() + 1;
            int barH = font.getHeight() + 2;
            int areaTop = barH;
            int areaH = h - 2 * barH;
            int rows = rows();
            int maxTop = Math.max(0, ls.size() - rows);
            if (top > maxTop) {
                top = maxTop;
            }
            if (top < 0) {
                top = 0;
            }

            g.setColor(bg);
            g.fillRect(0, 0, w, h);
            g.setFont(font);

            g.setColor(barBg);
            g.fillRect(0, 0, w, barH);
            g.fillRect(0, h - barH, w, barH);
            g.setColor(barFg);
            g.drawString(fit(title, w - 4), 2, 1, Graphics.TOP | Graphics.LEFT);

            String info;
            if (status != null) {
                info = status;
            } else {
                int pct = maxTop == 0 ? 100 : (top * 100 / maxTop);
                StringBuffer sb = new StringBuffer();
                sb.append(Integer.toString(pct)).append("%   line ");
                sb.append(Integer.toString(top + 1)).append('/');
                sb.append(Integer.toString(ls.size()));
                info = sb.toString();
            }
            g.drawString(fit(info, w - 4), 2, h - barH + 1, Graphics.TOP | Graphics.LEFT);

            g.setClip(0, areaTop, w, areaH);
            g.setColor(fg);
            for (int i = 0; i < rows && top + i < ls.size(); i++) {
                g.drawString((String) ls.elementAt(top + i), 2, areaTop + i * lh,
                        Graphics.TOP | Graphics.LEFT);
            }
            if (ls.size() > rows) {
                int thumbH = Math.max(8, areaH * rows / ls.size());
                int thumbY = areaTop + (maxTop == 0 ? 0 : (areaH - thumbH) * top / maxTop);
                g.setColor(barBg);
                g.fillRect(w - SB_W, areaTop, SB_W, areaH);
                g.setColor(barFg);
                g.fillRect(w - SB_W, thumbY, SB_W, thumbH);
            }
            g.setClip(0, 0, w, h);
        }

        protected void keyPressed(int k) {
            handleKey(k, false);
        }

        protected void keyRepeated(int k) {
            handleKey(k, true);
        }

        private void handleKey(int k, boolean rep) {
            int rows = rows();
            int total = lines.size();
            int maxTop = Math.max(0, total - rows);
            int quarter = Math.max(rows, total / 10);
            int step = rep ? 3 : 1;

            switch (k) {
                case KEY_NUM1:
                    top = 0;
                    break;
                case KEY_NUM7:
                    top = maxTop;
                    break;
                case KEY_NUM2:
                    top -= rows - 1;
                    break;
                case KEY_NUM8:
                    top += rows - 1;
                    break;
                case KEY_NUM4:
                    top -= quarter;
                    break;
                case KEY_NUM6:
                    top += quarter;
                    break;
                case KEY_NUM5:
                    if (!rep) {
                        showLinks();
                    }
                    return;
                case KEY_NUM3:
                    if (!rep) {
                        askUrl();
                    }
                    return;
                case KEY_NUM9:
                    if (!rep) {
                        askSearch();
                    }
                    return;
                case KEY_NUM0:
                    if (!rep) {
                        goHome();
                    }
                    return;
                case KEY_STAR:
                    if (!rep) {
                        goBack();
                    }
                    return;
                case KEY_POUND:
                    if (!rep) {
                        reload();
                    }
                    return;
                default:
                    int a;
                    try {
                        a = getGameAction(k);
                    } catch (Exception e) {
                        return;
                    }
                    if (a == UP) {
                        top -= step;
                    } else if (a == DOWN) {
                        top += step;
                    } else if (a == LEFT) {
                        top -= quarter;
                    } else if (a == RIGHT) {
                        top += quarter;
                    } else if (a == FIRE) {
                        if (!rep) {
                            showLinks();
                        }
                        return;
                    } else {
                        return;
                    }
            }
            if (top > maxTop) {
                top = maxTop;
            }
            if (top < 0) {
                top = 0;
            }
            repaint();
        }
    }
}
