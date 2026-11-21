package tech.obscureoctopus.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class KeepAliveService extends Service {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel("oo-link", "Watch link", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "oo-link")
                .setContentTitle("OCTOPUS")
                .setContentText("Forwarding SMS and notifications to the watch")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
        startForeground(17, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
