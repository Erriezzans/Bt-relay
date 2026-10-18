import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/**
 * Bt-relay client for Nokia (Java ME, CLDC 1.1 / MIDP 2.0 / JSR-82).
 * Sends a URL to the Android relay over Bluetooth and shows the text reply.
 */
public class BtRelay extends MIDlet implements CommandListener {

    // Must match APP_UUID in the Android app (without dashes).
    private static final String SVC_UUID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";

    private Form form;
    private TextField urlField;
    private StringItem output;
    private Command goCmd;
    private Command exitCmd;

    public void startApp() {
        if (form == null) {
            form = new Form("Bt-relay");
            urlField = new TextField("URL", "http://", 256, TextField.URL);
            output = new StringItem("", "Enter a URL and press Go.");
            form.append(urlField);
            form.append(output);
            goCmd = new Command("Go", Command.OK, 1);
            exitCmd = new Command("Exit", Command.EXIT, 2);
            form.addCommand(goCmd);
            form.addCommand(exitCmd);
            form.setCommandListener(this);
        }
        Display.getDisplay(this).setCurrent(form);
    }

    public void pauseApp() {
    }

    public void destroyApp(boolean unconditional) {
    }

    public void commandAction(Command c, Displayable d) {
        if (c == goCmd) {
            final String url = urlField.getString();
            // Never do Bluetooth I/O on the UI thread.
            new Thread(new Runnable() {
                public void run() {
                    fetch(url);
                }
            }).start();
        } else if (c == exitCmd) {
            destroyApp(true);
            notifyDestroyed();
        }
    }

    private void fetch(String url) {
        StreamConnection conn = null;
        try {
            show("Searching for relay...\n(Android must be discoverable)");
            DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
            String connUrl = agent.selectService(
                    new UUID(SVC_UUID, false),
                    ServiceRecord.AUTHENTICATE_ENCRYPT,
                    false);
            if (connUrl == null) {
                show("Relay not found.\nStart the Android app and make it discoverable.");
                return;
            }

            show("Connecting...");
            conn = (StreamConnection) Connector.open(connUrl);

            OutputStream os = conn.openOutputStream();
            os.write(url.concat("\n").getBytes("UTF-8"));
            os.flush();

            show("Loading...");
            InputStream is = conn.openInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = is.read(buf)) != -1) {
                bo.write(buf, 0, n);
            }
            show(new String(bo.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            show("Error: ".concat(String.valueOf(t)));
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void show(String text) {
        output.setText(text);
    }
}
