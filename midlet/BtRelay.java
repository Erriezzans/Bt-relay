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
import javax.microedition.lcdui.Image;
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
            + "Up/Down: move between links (scrolls when none)\n"
            + "Select: open the selected link\n"
            + "2 / 8: page up / down\n"
            + "4 / 6 or Left/Right: fast jump (10%)\n"
            + "1 / 7: top / bottom\n"
            + "5: list of all links\n"
            + "Images show as (Image: ...) links: Select opens the picture\n"
            + "Options > Remote browser: the real page with a cursor (forms, login, JavaScript)\n"
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
    private ImageView imageView;
    private RemoteView remote;
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
    private Command remoteCmd = new Command("Remote browser", Command.SCREEN, 3);
    private Command sendCmd = new Command("Send", Command.OK, 1);
    private Command sendEnterCmd = new Command("Send + Enter", Command.SCREEN, 2);
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
            view.addCommand(remoteCmd);
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
            } else if (c == remoteCmd) {
                startRemote();
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
        } else if (d == remote) {
            remote.command(c);
        } else if (d == inputBox) {
            if (inputMode >= 2) {
                display.setCurrent(remote);
                if (c == okCmd || c == sendCmd || c == sendEnterCmd) {
                    remote.input(inputMode, inputBox.getString(), c == sendEnterCmd);
                }
                return;
            }
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
                    openLink(lk[1]);
                }
            } else {
                display.setCurrent(view);
            }
        } else if (d == imageView) {
            display.setCurrent(view);
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

    private void openLink(String url) {
        if (url.startsWith("IMG:")) {
            showImage(url.substring(4));
        } else {
            load(url, true);
        }
    }

    // ------------------------------------------------------- remote browser

    private void startRemote() {
        if (remote == null) {
            remote = new RemoteView();
        }
        display.setCurrent(remote);
        if (remote.pageUrl.length() == 0) {
            if (curUrl.length() > 0) {
                remote.open(curUrl);
            } else {
                askRemoteUrl();
            }
        }
    }

    private void showInput(TextBox box, int mode) {
        inputBox = box;
        inputMode = mode;
        inputBox.setCommandListener(this);
        display.setCurrent(inputBox);
    }

    private void askRemoteUrl() {
        String start = (remote != null && remote.pageUrl.length() > 0) ? remote.pageUrl : "http://";
        TextBox b = new TextBox("Go to address", start, 512, TextField.URL);
        b.addCommand(okCmd);
        b.addCommand(cancelCmd);
        showInput(b, 2);
    }

    private void askRemoteSearch() {
        TextBox b = new TextBox("Search", "", 200, TextField.ANY);
        b.addCommand(okCmd);
        b.addCommand(cancelCmd);
        showInput(b, 3);
    }

    private void askRemoteText(String hint, String type, String value) {
        int cons = TextField.ANY;
        if (type.equals("password")) {
            cons = TextField.PASSWORD;
        } else if (type.equals("email")) {
            cons = TextField.EMAILADDR;
        } else if (type.equals("number")) {
            cons = TextField.NUMERIC;
        } else if (type.equals("tel")) {
            cons = TextField.PHONENUMBER;
        } else if (type.equals("url")) {
            cons = TextField.URL;
        }
        String title = hint.length() > 0 ? hint : "Type text";
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        if (value.length() > 400) {
            value = value.substring(0, 400);
        }
        TextBox b = new TextBox(title, value, 500, cons);
        b.addCommand(sendCmd);
        b.addCommand(sendEnterCmd);
        b.addCommand(cancelCmd);
        showInput(b, 4);
    }

    private String rb(String cmd, String a, String b) {
        StringBuffer s = new StringBuffer();
        s.append("RB\t").append(cmd);
        if (a != null) {
            s.append('\t').append(a);
        }
        if (b != null) {
            s.append('\t').append(b);
        }
        s.append('\n');
        return s.toString();
    }

    private static String flat(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // --------------------------------------------------------------- images

    private void showImage(final String url) {
        if (busy) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                doImage(url);
            }
        }).start();
    }

    private void imageError(String msg) {
        view.setStatus(null);
        Alert a = new Alert("Image", msg, null, AlertType.ERROR);
        a.setTimeout(2500);
        display.setCurrent(a, view);
    }

    private void doImage(String url) {
        busy = true;
        try {
            view.setStatus("Loading image...");
            StringBuffer req = new StringBuffer();
            req.append("IMG\t").append(url).append('\t');
            req.append(Integer.toString(view.getWidth())).append('\t');
            req.append(Integer.toString(view.getHeight())).append('\n');
            byte[] data = exchange(req.toString(), 400000, "Waiting for image...");
            if (data.length == 0) {
                imageError("Cannot load this image");
                return;
            }
            Image im = Image.createImage(data, 0, data.length);
            data = null;
            imageView = new ImageView(im);
            imageView.addCommand(backCmd);
            imageView.setCommandListener(this);
            view.setStatus(null);
            display.setCurrent(imageView);
        } catch (Throwable t) {
            imageError("Image failed (too big or relay error)");
        } finally {
            busy = false;
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
        StringBuffer req = new StringBuffer();
        req.append("GET\t").append(url).append('\t');
        req.append(Integer.toString(PAGE_CHARS[pageIdx])).append('\n');
        byte[] data = exchange(req.toString(), 3000000, "Waiting for page...");
        return new String(data, "UTF-8");
    }

    // Sends one request line to the relay and returns the length-prefixed reply.
    private byte[] exchange(String reqLine, int maxLen, String waitMsg) throws IOException {
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
            os.write(reqLine.getBytes("UTF-8"));
            os.flush();

            view.setStatus(waitMsg);
            dis = conn.openDataInputStream();
            int len = dis.readInt();
            if (len < 0 || len > maxLen) {
                throw new IOException("Bad reply from relay");
            }
            byte[] data = new byte[len];
            if (len > 0) {
                dis.readFully(data);
            }
            return data;
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
        private int linkFg;
        private int selBg;
        private int selFg;
        private Vector marks = new Vector();   // int[]{line, start, end, linkNo}
        private int[] linkLine = new int[1];   // linkNo -> line index, -1 if not found
        private int sel = -1;                  // selected link number (1-based), -1 = none
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
                linkFg = 0x66B3FF;
                selBg = 0xFFD84A;
                selFg = 0x000000;
            } else {
                bg = 0xFFFFFF;
                fg = 0x101010;
                barBg = 0x3355AA;
                barFg = 0xFFFFFF;
                linkFg = 0x0033CC;
                selBg = 0x0033CC;
                selFg = 0xFFFFFF;
            }
            lastW = -1;
            repaint();
        }

        void setPage(String t, String body) {
            title = t;
            raw = body;
            status = null;
            top = 0;
            sel = -1;
            marks = new Vector();
            linkLine = new int[1];
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
            buildMarks();
        }

        // Finds the "label[n]" link markers in the wrapped lines (numbers must come in order).
        private void buildMarks() {
            Vector lk = links;
            int nl = lk.size();
            Vector m = new Vector();
            int[] ll = new int[nl + 1];
            for (int i = 0; i < ll.length; i++) {
                ll[i] = -1;
            }
            int next = 1;
            for (int li = 0; li < lines.size() && next <= nl; li++) {
                String s = (String) lines.elementAt(li);
                int pos = 0;
                while (next <= nl) {
                    int a = s.indexOf('[', pos);
                    if (a < 0) {
                        break;
                    }
                    int b = a + 1;
                    int num = 0;
                    while (b < s.length() && b - a <= 5 && s.charAt(b) >= '0' && s.charAt(b) <= '9') {
                        num = num * 10 + (s.charAt(b) - '0');
                        b++;
                    }
                    if (b < s.length() && s.charAt(b) == ']' && b > a + 1
                            && num >= next && num <= next + 3 && num <= nl) {
                        String label = ((String[]) lk.elementAt(num - 1))[0];
                        int st = a - label.length();
                        if (st < 0 || !s.substring(st, a).equals(label)) {
                            // label wrapped or differs: colour only the last word
                            st = a;
                            while (st > 0 && s.charAt(st - 1) != ' ') {
                                st--;
                            }
                        }
                        ll[num] = li;
                        m.addElement(new int[]{li, st, b + 1, num});
                        next = num + 1;
                        pos = b + 1;
                    } else {
                        pos = a + 1;
                    }
                }
            }
            linkLine = ll;
            marks = m;
        }

        private boolean visible(int line) {
            return line >= top && line < top + rows();
        }

        // Keeps the selection on a link that is on screen (or none).
        private void fixSel(int dir) {
            int nl = linkLine.length - 1;
            if (sel >= 1 && sel <= nl && linkLine[sel] >= 0 && visible(linkLine[sel])) {
                return;
            }
            sel = -1;
            if (dir >= 0) {
                for (int i = 1; i <= nl; i++) {
                    if (linkLine[i] >= 0 && visible(linkLine[i])) {
                        sel = i;
                        return;
                    }
                }
            } else {
                for (int i = nl; i >= 1; i--) {
                    if (linkLine[i] >= 0 && visible(linkLine[i])) {
                        sel = i;
                        return;
                    }
                }
            }
        }

        // Up/Down: go to the next/previous link; if it is off screen, scroll one line.
        private void moveSel(int dir) {
            int nl = linkLine.length - 1;
            int rows = rows();
            int base = sel;
            if (base < 1) {
                base = dir > 0 ? 0 : nl + 1;
                for (int i = 1; i <= nl; i++) {
                    int ln = linkLine[i];
                    if (ln < 0) {
                        continue;
                    }
                    if (dir > 0 && ln < top) {
                        base = i;
                    }
                    if (dir < 0 && ln >= top + rows && base == nl + 1) {
                        base = i;
                    }
                }
            }
            int cand = -1;
            for (int i = base + dir; i >= 1 && i <= nl; i += dir) {
                if (linkLine[i] >= 0) {
                    cand = i;
                    break;
                }
            }
            if (cand > 0 && visible(linkLine[cand])) {
                sel = cand;
                return;
            }
            int maxTop = Math.max(0, lines.size() - rows);
            top += dir;
            if (top > maxTop) {
                top = maxTop;
            }
            if (top < 0) {
                top = 0;
            }
            if (cand > 0 && visible(linkLine[cand])) {
                sel = cand;
            } else {
                fixSel(dir);
            }
        }

        private void openSel() {
            if (sel >= 1 && sel <= links.size()) {
                String[] l = (String[]) links.elementAt(sel - 1);
                openLink(l[1]);
            } else {
                showLinks();
            }
        }

        private void drawLine(Graphics g, String s, int li, int y) {
            int x = 2;
            int pos = 0;
            int fh = font.getHeight();
            for (int m = 0; m < marks.size(); m++) {
                int[] mk = (int[]) marks.elementAt(m);
                if (mk[0] != li) {
                    continue;
                }
                int st = Math.max(mk[1], pos);
                if (st > pos) {
                    String pre = s.substring(pos, st);
                    g.setColor(fg);
                    g.drawString(pre, x, y, Graphics.TOP | Graphics.LEFT);
                    x += font.stringWidth(pre);
                }
                String seg = s.substring(st, mk[2]);
                int sw = font.stringWidth(seg);
                if (mk[3] == sel) {
                    g.setColor(selBg);
                    g.fillRect(x - 1, y, sw + 2, fh);
                    g.setColor(selFg);
                    g.drawString(seg, x, y, Graphics.TOP | Graphics.LEFT);
                } else {
                    g.setColor(linkFg);
                    g.drawString(seg, x, y, Graphics.TOP | Graphics.LEFT);
                    g.drawLine(x, y + fh - 1, x + sw, y + fh - 1);
                }
                x += sw;
                pos = mk[2];
            }
            if (pos < s.length()) {
                g.setColor(fg);
                g.drawString(s.substring(pos), x, y, Graphics.TOP | Graphics.LEFT);
            }
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
            fixSel(1);

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
                drawLine(g, (String) ls.elementAt(top + i), top + i, areaTop + i * lh);
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
            // Centre key of the D-pad: -5 on Nokia, 10/13 on some other phones.
            if (k == -5 || k == 10 || k == 13) {
                if (!rep) {
                    openSel();
                }
                return;
            }
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
                        if (rep) {
                            top -= step;
                        } else {
                            moveSel(-1);
                            repaint();
                            return;
                        }
                    } else if (a == DOWN) {
                        if (rep) {
                            top += step;
                        } else {
                            moveSel(1);
                            repaint();
                            return;
                        }
                    } else if (a == LEFT) {
                        top -= quarter;
                    } else if (a == RIGHT) {
                        top += quarter;
                    } else if (a == FIRE) {
                        if (!rep) {
                            openSel();
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

    // ----------------------------------------------------------- image view

    private class ImageView extends Canvas {
        private Image img;
        private int ox = 0;
        private int oy = 0;

        ImageView(Image i) {
            img = i;
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            g.setColor(0x000000);
            g.fillRect(0, 0, w, h);
            int x = img.getWidth() <= w ? (w - img.getWidth()) / 2 : -ox;
            int y = img.getHeight() <= h ? (h - img.getHeight()) / 2 : -oy;
            g.drawImage(img, x, y, Graphics.TOP | Graphics.LEFT);
        }

        protected void keyPressed(int k) {
            int a = 0;
            try {
                a = getGameAction(k);
            } catch (Exception e) {
                a = 0;
            }
            int step = 40;
            if (a == UP) {
                oy -= step;
            } else if (a == DOWN) {
                oy += step;
            } else if (a == LEFT) {
                ox -= step;
            } else if (a == RIGHT) {
                ox += step;
            } else {
                display.setCurrent(view);   // any other key goes back to the page
                return;
            }
            ox = Math.max(0, Math.min(ox, img.getWidth() - getWidth()));
            oy = Math.max(0, Math.min(oy, img.getHeight() - getHeight()));
            repaint();
        }
    }

    // ------------------------------------------------------------ remote view

    /** Shows the relay's screenshot, a cursor you move with the D-pad, and a status bar. */
    private class RemoteView extends Canvas {
        private final int[] ZOOMS = {240, 320, 400, 480, 640, 800, 1024};
        private Image shot;
        private int cx = 100;
        private int cy = 100;
        private int zoom = 3;
        private int pct = 0;
        private int lastShotBytes = 0;
        private String title = "";
        String pageUrl = "";
        private String msg = "";
        private volatile boolean working = false;
        private boolean afterClick = false;
        private boolean editOn = false;
        private String editType = "";
        private String editHint = "";
        private String editValue = "";
        private Font font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        private Command rUrl = new Command("Go to address", Command.SCREEN, 1);
        private Command rSearch = new Command("Search", Command.SCREEN, 2);
        private Command rType = new Command("Type in field", Command.SCREEN, 3);
        private Command rFwd = new Command("Forward", Command.SCREEN, 4);
        private Command rReload = new Command("Reload", Command.SCREEN, 5);
        private Command rZoomIn = new Command("Zoom in", Command.SCREEN, 6);
        private Command rZoomOut = new Command("Zoom out", Command.SCREEN, 7);
        private Command rText = new Command("Text mode", Command.SCREEN, 8);
        private Command rBack = new Command("Back", Command.BACK, 1);
        private Command rExit = new Command("Exit", Command.EXIT, 9);
        private Command rClick = new Command("Click (Select)", Command.SCREEN, 1);

        RemoteView() {
            addCommand(rClick);
            addCommand(rUrl);
            addCommand(rSearch);
            addCommand(rType);
            addCommand(rFwd);
            addCommand(rReload);
            addCommand(rZoomIn);
            addCommand(rZoomOut);
            addCommand(rText);
            addCommand(rBack);
            addCommand(rExit);
            setCommandListener(BtRelay.this);
        }

        private int barH() {
            return font.getHeight() + 2;
        }

        private String fit(String s, int maxW) {
            if (font.stringWidth(s) <= maxW) {
                return s;
            }
            int n = s.length();
            while (n > 1 && font.stringWidth(s.substring(0, n)) > maxW) {
                n--;
            }
            return s.substring(0, n);
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            int bh = barH();
            g.setColor(0xFFFFFF);
            g.fillRect(0, 0, w, h);
            g.setFont(font);
            if (shot != null) {
                g.drawImage(shot, 0, 0, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setColor(0x333333);
                g.drawString("Remote browser", 4, 4, Graphics.TOP | Graphics.LEFT);
                g.drawString("3 = address, 9 = search", 4, 4 + font.getHeight(),
                        Graphics.TOP | Graphics.LEFT);
            }
            // cursor: white shadow under a black cross with a red centre
            g.setColor(0xFFFFFF);
            g.drawLine(cx - 6, cy + 1, cx + 6, cy + 1);
            g.drawLine(cx + 1, cy - 6, cx + 1, cy + 6);
            g.setColor(0x000000);
            g.drawLine(cx - 6, cy, cx + 6, cy);
            g.drawLine(cx, cy - 6, cx, cy + 6);
            g.setColor(0xFF0000);
            g.fillRect(cx - 1, cy - 1, 3, 3);

            g.setColor(0x2A3A66);
            g.fillRect(0, h - bh, w, bh);
            g.setColor(0xFFFFFF);
            String t;
            if (msg.length() > 0) {
                t = msg;
            } else {
                StringBuffer b = new StringBuffer();
                b.append(Integer.toString(pct)).append("% ").append(title);
                t = b.toString();
            }
            g.drawString(fit(t, w - 4), 2, h - bh + 1, Graphics.TOP | Graphics.LEFT);
        }

        // ---- keys

        protected void keyPressed(int k) {
            key(k, false);
        }

        protected void keyRepeated(int k) {
            key(k, true);
        }

        private void key(int k, boolean rep) {
            if (k == -5 || k == 10 || k == 13 || k == KEY_NUM5) {
                if (!rep) {
                    click();
                }
                return;
            }
            if (k == KEY_NUM2) {
                if (!rep) {
                    scroll(-1);
                }
                return;
            }
            if (k == KEY_NUM8) {
                if (!rep) {
                    scroll(1);
                }
                return;
            }
            if (k == KEY_NUM4 || k == KEY_STAR) {
                if (!rep) {
                    navigate("BACK");
                }
                return;
            }
            if (k == KEY_NUM6) {
                if (!rep) {
                    navigate("FWD");
                }
                return;
            }
            if (k == KEY_POUND) {
                if (!rep) {
                    navigate("RELOAD");
                }
                return;
            }
            if (k == KEY_NUM1) {
                if (!rep) {
                    zoomBy(-1);
                }
                return;
            }
            if (k == KEY_NUM7) {
                if (!rep) {
                    zoomBy(1);
                }
                return;
            }
            if (k == KEY_NUM3) {
                if (!rep) {
                    askRemoteUrl();
                }
                return;
            }
            if (k == KEY_NUM9) {
                if (!rep) {
                    askRemoteSearch();
                }
                return;
            }
            if (k == KEY_NUM0) {
                if (!rep) {
                    typeNow();
                }
                return;
            }
            int a = 0;
            try {
                a = getGameAction(k);
            } catch (Exception e) {
                return;
            }
            int step = rep ? 24 : 8;
            if (a == UP) {
                move(0, -step);
            } else if (a == DOWN) {
                move(0, step);
            } else if (a == LEFT) {
                move(-step, 0);
            } else if (a == RIGHT) {
                move(step, 0);
            } else if (a == FIRE) {
                if (!rep) {
                    click();
                }
            } else if (!rep) {
                msg = cat("Key ", Integer.toString(k));
                repaint();
            }
        }

        private void move(int dx, int dy) {
            int w = getWidth();
            int ih = getHeight() - barH();
            cx = Math.max(0, Math.min(w - 1, cx + dx));
            int ny = cy + dy;
            if (ny < 0) {
                cy = 0;
                scroll(-1);          // pushed against the top edge: scroll the page
            } else if (ny > ih - 1) {
                cy = ih - 1;
                scroll(1);           // pushed against the bottom edge
            } else {
                cy = ny;
            }
            repaint();
        }

        // ---- actions

        private void click() {
            if (working) {
                msg = "Busy, wait...";
                repaint();
                return;
            }
            afterClick = true;
            send(rb("CLICK", Integer.toString(cx), Integer.toString(cy)));
        }

        private void scroll(int dir) {
            int ih = getHeight() - barH();
            send(rb("SCROLL", Integer.toString(dir * ih * 3 / 4), null));
        }

        private void navigate(String what) {
            send(rb(what, null, null));
        }

        private void zoomBy(int d) {
            int z = Math.max(0, Math.min(ZOOMS.length - 1, zoom + d));
            if (z == zoom) {
                return;
            }
            zoom = z;
            send(rb("ZOOM", Integer.toString(ZOOMS[zoom]), null));
        }

        private void typeNow() {
            if (editOn) {
                askRemoteText(editHint, editType, editValue);
            } else {
                msg = "Click a text field first";
                repaint();
            }
        }

        private void leave() {
            display.setCurrent(view);
            view.setStatus(null);
        }

        void command(Command c) {
            if (c == rExit) {
                destroyApp(true);
                notifyDestroyed();
            } else if (c == rClick) {
                click();
            } else if (c == rUrl) {
                askRemoteUrl();
            } else if (c == rSearch) {
                askRemoteSearch();
            } else if (c == rType) {
                typeNow();
            } else if (c == rFwd) {
                navigate("FWD");
            } else if (c == rReload) {
                navigate("RELOAD");
            } else if (c == rZoomIn) {
                zoomBy(-1);
            } else if (c == rZoomOut) {
                zoomBy(1);
            } else if (c == rText) {
                leave();
            } else if (c == rBack) {
                if (msg.equals("No history")) {
                    leave();
                } else {
                    navigate("BACK");
                }
            }
        }

        /** Called when an address / search / text box was confirmed. */
        void input(int mode, String t, boolean enter) {
            if (mode == 4) {
                StringBuffer b = new StringBuffer();
                b.append("RB\tTEXT\t").append(enter ? "1" : "0").append('\t').append(flat(t)).append('\n');
                send(b.toString());
                return;
            }
            String u = t.trim();
            if (u.length() == 0) {
                return;
            }
            if (mode == 2) {
                if (u.indexOf("://") < 0) {
                    u = cat("http://", u);
                }
                open(u);
            } else if (mode == 3) {
                open(cat("https://duckduckgo.com/?q=", urlEncode(u)));
            }
        }

        // ---- talking to the relay

        void open(final String url) {
            if (working) {
                return;
            }
            working = true;
            msg = "Opening...";
            pageUrl = url;
            repaint();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(150);   // let the screen size settle
                    } catch (InterruptedException e) {
                    }
                    StringBuffer r = new StringBuffer();
                    r.append("RB\tOPEN\t").append(url).append('\t');
                    r.append(Integer.toString(getWidth())).append('\t');
                    r.append(Integer.toString(getHeight() - barH())).append('\t');
                    r.append(Integer.toString(ZOOMS[zoom])).append('\t');
                    r.append("60\n");
                    talk(r.toString());
                }
            }).start();
        }

        private boolean send(final String line) {
            if (working) {
                return false;
            }
            working = true;
            msg = "Working...";
            repaint();
            new Thread(new Runnable() {
                public void run() {
                    talk(line);
                }
            }).start();
            return true;
        }

        private void talk(String line) {
            try {
                byte[] d = exchange(line, 600000, "Remote browser...");
                apply(d);
                if (line.startsWith("RB\tSCROLL") && lastShotBytes < 2000) {
                    Thread.sleep(500);   // page not painted yet: ask for the screen again
                    d = exchange(rb("SHOT", null, null), 600000, "Remote browser...");
                    apply(d);
                }
            } catch (Throwable t) {
                afterClick = false;
                String e = String.valueOf(t);
                msg = e.length() > 60 ? e.substring(0, 60) : e;
            } finally {
                working = false;
                view.setStatus(null);
                repaint();
            }
        }

        private void apply(byte[] d) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
            int ml = in.readInt();
            if (ml < 0 || ml > d.length - 4) {
                throw new IOException("Bad reply");
            }
            byte[] mb = new byte[ml];
            in.readFully(mb);
            String meta = new String(mb, "UTF-8");
            int off = 4 + ml;
            lastShotBytes = d.length - off;
            if (d.length > off) {
                shot = Image.createImage(d, off, d.length - off);
            }
            msg = "";
            boolean edit = false;
            String et = "";
            String eh = "";
            String ev = "";
            int pos = 0;
            while (pos < meta.length()) {
                int e = meta.indexOf('\n', pos);
                if (e < 0) {
                    e = meta.length();
                }
                String ln = meta.substring(pos, e);
                pos = e + 1;
                int q = ln.indexOf('=');
                if (q <= 0) {
                    continue;
                }
                String k = ln.substring(0, q);
                String v = ln.substring(q + 1);
                if (k.equals("title")) {
                    title = v;
                } else if (k.equals("url")) {
                    if (v.length() > 0) {
                        pageUrl = v;
                        curUrl = v;
                    }
                } else if (k.equals("pct")) {
                    try {
                        pct = Integer.parseInt(v);
                    } catch (NumberFormatException nfe) {
                        pct = 0;
                    }
                } else if (k.equals("edit")) {
                    edit = v.equals("1");
                } else if (k.equals("etype")) {
                    et = v;
                } else if (k.equals("ehint")) {
                    eh = v;
                } else if (k.equals("evalue")) {
                    ev = v;
                } else if (k.equals("msg")) {
                    msg = v;
                }
            }
            editOn = edit;
            editType = et;
            editHint = eh;
            editValue = ev;
            boolean ask = afterClick && edit;
            afterClick = false;
            if (ask) {
                askRemoteText(eh, et, ev);
            }
        }
    }
}
