package com.erriezzans.btrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/** Foreground service that Android requires while the sound is being captured. */
public class CaptureService extends Service {

    static final String CHANNEL = "capture";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            AudioCapture.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Sound for Nokia", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("Bt-relay")
                .setContentText("Sending sound to the Nokia")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(2, n);
        }

        try {
            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager m =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection mp = m.getMediaProjection(code, data);
            AudioCapture.start(mp);
        } catch (Throwable t) {
            if (AudioCapture.logger != null) {
                AudioCapture.logger.log("Sound capture could not start: " + t);
            }
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        AudioCapture.stop();
        super.onDestroy();
    }
}
