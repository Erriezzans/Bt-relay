package com.btrelay;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private TextView logView;
    private ScrollView scroll;
    private Button toggle;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText("1. Tap Start.\n2. Tap Make discoverable.\n3. On the Symbian phone, run Hiisi, choose Bluetooth mode, and pick this phone.");
        root.addView(help);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> toggle());
        root.addView(toggle);

        Button disc = new Button(this);
        disc.setText("Make discoverable (5 min)");
        disc.setOnClickListener(v -> {
            requestPerms();
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(i);
        });
        root.addView(disc);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        requestPerms();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        RelayService.listener = line -> runOnUiThread(() -> { append(line); refresh(); });
    }

    @Override protected void onPause() {
        RelayService.listener = null;
        super.onPause();
    }

    private void refresh() {
        toggle.setText(RelayService.running ? "Stop relay" : "Start relay");
        synchronized (RelayService.class) { logView.setText(RelayService.history.toString()); }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void append(String s) {
        logView.append(s + "\n");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toggle() {
        Intent i = new Intent(this, RelayService.class);
        if (RelayService.running) {
            i.setAction("STOP");
            startService(i);
        } else {
            requestPerms();
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                append("Allow the Bluetooth permission, then tap Start again.");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        toggle.postDelayed(this::refresh, 500);
    }

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(missing.toArray(new String[0]), 1);
    }
}
