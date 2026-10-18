package com.btrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Listens on a Bluetooth Serial Port (SPP) and hands each connection to RelayCore. */
public class RelayService extends Service {
    public interface Listener { void onLog(String line); }

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static volatile boolean running = false;
    static volatile Listener listener;
    static final StringBuilder history = new StringBuilder();

    private BluetoothServerSocket server;
    private ExecutorService pool;

    static synchronized void log(String s) {
        history.append(s).append('\n');
        if (history.length() > 20000) history.delete(0, history.length() - 15000);
        Listener l = listener;
        if (l != null) l.onLog(s);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        startForegroundCompat();
        running = true;
        pool = Executors.newCachedThreadPool();
        new Thread(this::acceptLoop, "bt-accept").start();
        return START_STICKY;
    }

    private void startForegroundCompat() {
        String ch = "relay";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(ch, "BT Relay", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        Notification n = b.setContentTitle("BT Relay running")
                .setContentText("Sharing internet with your Symbian phone")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, n);
    }

    private void acceptLoop() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            // "Insecure" = no pairing/encryption required; Hiisi asks for NOAUTHENTICATE_NOENCRYPT.
            server = ad.listenUsingInsecureRfcommWithServiceRecord("Hiisi Relay", SPP);
            log("Listening on Bluetooth serial port...");
            while (running) {
                final BluetoothSocket s = server.accept();
                pool.submit(() -> serve(s));
            }
        } catch (IOException | SecurityException e) {
            if (running) log("Bluetooth error: " + e.getMessage());
        } finally {
            running = false;
            log("Stopped.");
            stopSelf();
        }
    }

    private void serve(BluetoothSocket s) {
        try {
            RelayCore.handle(s.getInputStream(), s.getOutputStream(), RelayService::log);
            s.getOutputStream().flush();
            Thread.sleep(300); // let Bluetooth drain before closing
        } catch (Exception e) {
            log("Connection error: " + e);
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    @Override public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }
}
