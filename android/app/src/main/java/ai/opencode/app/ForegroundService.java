package ai.opencode.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;

public class ForegroundService extends Service {
    private static final String CHANNEL_ID = "opencode_background";
    private static final int NOTIFICATION_ID = 1;
    private EventNotificationWatcher watcher;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        watcher = new EventNotificationWatcher(this);
        watcher.start();
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:notification-watcher");
        wakeLock.acquire();
        // OEM freezer mitigation: ask the user to exempt the app from battery
        // optimization. ColorOS/Oplus freezer stops background processes even
        // when a foreground service is running, so this is the reliable lever.
        if (!power.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // Some OEMs do not expose the standard dialog; users exempt manually in Settings.
            }
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_service_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setSound(null, null);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.background_service_notification_title))
            .setContentText(getString(R.string.background_service_notification_description))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        // Manifest declares android:foregroundServiceType="dataSync"; the no-type
        // overload resolves to the manifest type on API 29+.
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (watcher != null) watcher.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
