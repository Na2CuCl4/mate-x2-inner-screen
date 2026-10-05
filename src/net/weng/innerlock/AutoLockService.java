package net.weng.innerlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Event-driven foreground service. It does not acquire a wake lock or poll while asleep. */
public final class AutoLockService extends Service {
    private static final String CHANNEL = "automatic_inner_display";
    private static final String ACTION_STOP = "net.weng.innerlock.STOP_AUTOMATIC";
    private static final int NOTIFICATION = 1;
    private static final long[] RETRIES = {2000, 5000, 10000};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private boolean registered;
    private boolean runningRequest;
    private boolean pendingEvent;
    private int attempt;

    private final Runnable lockAttempt = this::checkAndLock;
    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handler.removeCallbacks(lockAttempt);
                pendingEvent = false;
                updateNotification("等待下次亮屏；不会主动唤醒屏幕");
            } else {
                scheduleForEvent();
            }
        }
    };

    /** Returns false if Android rejects foreground-service startup. */
    public static boolean start(Context context) {
        Context app = context.getApplicationContext();
        if (!FoldController.permissionsReady(app)) {
            FoldController.appendLog(app, "Cannot start automatic mode: required ADB permissions missing");
            return false;
        }
        FoldController.setAutoEnabled(app, true);
        try {
            app.startForegroundService(new Intent(app, AutoLockService.class));
            return true;
        } catch (RuntimeException exception) {
            FoldController.setAutoEnabled(app, false);
            FoldController.appendLog(app, "Foreground service startup failed: " + exception);
            return false;
        }
    }

    /** Stops automatic relocking; does not itself change the currently locked display mode. */
    public static void stop(Context context) {
        FoldController.setAutoEnabled(context, false);
        context.getApplicationContext().stopService(new Intent(context, AutoLockService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "自动保持内屏", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("开机或亮屏后恢复内屏锁定");
        manager.createNotificationChannel(channel);
        startForeground(NOTIFICATION, notification("等待检查内屏锁定状态"));
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenEvents, filter);
        registered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            FoldController.setAutoEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!FoldController.isAutoEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        scheduleForEvent();
        return START_STICKY;
    }

    private void scheduleForEvent() {
        if (destroyed || !FoldController.isAutoEnabled(this)) return;
        handler.removeCallbacks(lockAttempt);
        attempt = 0;
        if (!FoldController.isScreenOn(this)) {
            updateNotification("等待下次亮屏；不会主动唤醒屏幕");
            return;
        }
        if (runningRequest) {
            pendingEvent = true;
            return;
        }
        // Combine SCREEN_ON and USER_PRESENT bursts into a single check.
        handler.postDelayed(lockAttempt, 700);
    }

    private void checkAndLock() {
        if (destroyed || !FoldController.isAutoEnabled(this)) return;
        if (!FoldController.isScreenOn(this)) {
            updateNotification("等待下次亮屏；不会主动唤醒屏幕");
            return;
        }
        if (!FoldController.permissionsReady(this)) {
            updateNotification("缺少 ADB 授权，请打开应用检查");
            return;
        }
        if (runningRequest) return;
        runningRequest = true;
        FoldController.requestAutoLock(this, result -> {
            runningRequest = false;
            if (destroyed || !FoldController.isAutoEnabled(this)) return;
            updateNotification(result.message);
            if (!FoldController.isScreenOn(this)) {
                pendingEvent = false;
                return;
            }
            if (pendingEvent) {
                pendingEvent = false;
                if (!result.success) scheduleForEvent();
                return;
            }
            if (!result.success && FoldController.permissionsReady(this) && attempt < RETRIES.length) {
                handler.postDelayed(lockAttempt, RETRIES[attempt++]);
            }
        });
    }

    private Notification notification(String detail) {
        Intent open = new Intent().setClassName(getPackageName(), "net.weng.innerlock.MainActivity");
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AutoLockService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("自动保持内屏")
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pending)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                        "关闭自动保持", stop).build())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String detail) {
        if (!destroyed) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(detail));
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(screenEvents);
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
