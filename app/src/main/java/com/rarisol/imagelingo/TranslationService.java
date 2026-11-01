package com.rarisol.imagelingo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service that keeps the app process at foreground priority while a folder
 * translation runs, so Android does not cut its network or put the CPU to sleep when the user
 * switches apps or turns the screen off. The translation work itself still runs in
 * MainActivity's batch thread; this service only holds the notification and a wake lock.
 */
public final class TranslationService extends Service {
    private static final String CHANNEL_ID = "translation_progress";
    private static final String DONE_CHANNEL_ID = "translation_done";
    static final int PROGRESS_NOTIFICATION_ID = 1001;
    static final int DONE_NOTIFICATION_ID = 1002;
    private static final String EXTRA_TEXT = "text";
    /** Upper bound so a forgotten lock can never drain the battery. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L;

    // start/stop/onStartCommand all run on the main thread, so these need no extra locking.
    /** startForegroundService was called but onStartCommand has not run yet. */
    private static boolean pendingStart;
    /** stop() arrived while pendingStart; the service stops itself right after startForeground. */
    private static boolean stopAfterStart;

    private PowerManager.WakeLock wakeLock;

    /** Starts the service. Call while the app is visible (when the user taps 폴더 번역). */
    static void start(Context context, String text) {
        Intent intent = new Intent(context, TranslationService.class).putExtra(EXTRA_TEXT, text);
        stopAfterStart = false;
        pendingStart = true;
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException notAllowed) {
            // Android refuses to start foreground services from the background. The batch still
            // runs; it just is not protected until the next start from the foreground.
            pendingStart = false;
        }
    }

    static void stop(Context context) {
        // Stopping before the service has called startForeground can crash on some Android
        // versions, so a very short batch defers the stop to onStartCommand.
        if (pendingStart) {
            stopAfterStart = true;
            return;
        }
        context.stopService(new Intent(context, TranslationService.class));
    }

    /** Updates the ongoing notification (progress bar + text) while translation runs. */
    static void updateProgress(Context context, int done, int total, String text) {
        if (!canPostNotifications(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannels(context);
        manager.notify(PROGRESS_NOTIFICATION_ID, buildProgress(context, text, done, total));
    }

    /** A dismissible "finished" notice, shown when the app is not on screen. */
    static void notifyFinished(Context context, String title, String text) {
        if (!canPostNotifications(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannels(context);
        Notification notification = new Notification.Builder(context, DONE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build();
        manager.notify(DONE_NOTIFICATION_ID, notification);
    }

    static void cancelFinished(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(DONE_NOTIFICATION_ID);
    }

    private static boolean canPostNotifications(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel progress = new NotificationChannel(CHANNEL_ID, "번역 진행",
                    NotificationManager.IMPORTANCE_LOW);
            progress.setDescription("폴더 번역이 백그라운드에서 진행되는 동안 표시됩니다.");
            progress.setShowBadge(false);
            manager.createNotificationChannel(progress);
        }
        if (manager.getNotificationChannel(DONE_CHANNEL_ID) == null) {
            NotificationChannel done = new NotificationChannel(DONE_CHANNEL_ID, "번역 완료",
                    NotificationManager.IMPORTANCE_DEFAULT);
            done.setDescription("앱을 보고 있지 않을 때 폴더 번역이 끝나면 알려 줍니다.");
            manager.createNotificationChannel(done);
        }
    }

    private static Notification buildProgress(Context context, String text, int done, int total) {
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("ImageLingo 번역 중")
                .setContentText(text == null || text.isEmpty() ? "폴더 번역 준비 중" : text)
                .setContentIntent(openAppIntent(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (total > 0) builder.setProgress(total, Math.max(0, Math.min(done, total)), false);
        else builder.setProgress(0, 0, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    /** Brings the existing app task to the front instead of opening a second copy of the app. */
    private static PendingIntent openAppIntent(Context context) {
        Intent open = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannels(this);
        String text = intent == null ? "" : intent.getStringExtra(EXTRA_TEXT);
        Notification notification = buildProgress(this, text, 0, 0);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(PROGRESS_NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException notAllowed) {
            pendingStart = false;
            stopAfterStart = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        pendingStart = false;
        if (stopAfterStart) {
            stopAfterStart = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        // Not sticky: if Android kills the process the batch thread is gone too, so a restarted
        // service would only show a stale notification.
        return START_NOT_STICKY;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) return;
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ImageLingo:translation");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    /** Android 15+ limits dataSync services to 6 hours a day; stop cleanly when that is reached. */
    @Override public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
