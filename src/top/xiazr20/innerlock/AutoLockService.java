package top.xiazr20.innerlock;

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

/** Temporary recovery service: wait for a lit screen, verify the lock, then stop. */
public final class AutoLockService extends Service {
    private static final String CHANNEL = "automatic_inner_display";
    private static final String ACTION_STOP = "top.xiazr20.innerlock.STOP_AUTOMATIC";
    private static final int NOTIFICATION = 1;
    private static final int FAILURE_NOTIFICATION = 2;
    private static final long[] RETRIES = {2000, 5000, 10000};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private boolean finished;
    private boolean registered;
    private boolean runningRequest;
    private boolean pendingEvent;
    private int attempt;

    private final Runnable lockAttempt = this::checkAndLock;
    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (finished || destroyed) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handler.removeCallbacks(lockAttempt);
                pendingEvent = false;
                updateNotification("等待下次亮屏；不会主动唤醒屏幕");
            } else {
                scheduleForEvent();
            }
        }
    };

    /** Starts one recovery attempt without changing the saved boot preference. */
    public static boolean start(Context context) {
        Context app = context.getApplicationContext();
        if (!FoldController.isAutoEnabled(app)) return false;
        if (!FoldController.permissionsReady(app)) {
            FoldController.appendLog(app, "Cannot start automatic mode: required ADB permissions missing");
            return false;
        }
        try {
            app.startForegroundService(new Intent(app, AutoLockService.class));
            return true;
        } catch (RuntimeException exception) {
            FoldController.appendLog(app, "Foreground service startup failed: " + exception);
            return false;
        }
    }

    /** Stops automatic relocking; does not itself change the currently locked display mode. */
    public static void stop(Context context) {
        FoldController.setAutoEnabled(context, false);
        context.getApplicationContext().stopService(new Intent(context, AutoLockService.class));
        context.getSystemService(NotificationManager.class).cancel(FAILURE_NOTIFICATION);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "开机恢复内屏", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("等待亮屏恢复锁定；确认成功后自动退出");
        manager.createNotificationChannel(channel);
        manager.cancel(FAILURE_NOTIFICATION);
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
            finishRecovery("Automatic boot recovery disabled by user");
            return START_NOT_STICKY;
        }
        if (!FoldController.isAutoEnabled(this)) {
            finishRecovery("Automatic boot recovery is disabled");
            return START_NOT_STICKY;
        }
        if (finished) return START_NOT_STICKY;
        scheduleForEvent();
        // Android may restart us while waiting for the first usable screen-on.
        // Explicit stopSelf() on success/final failure ends this lifecycle.
        return START_STICKY;
    }

    private void scheduleForEvent() {
        if (destroyed || finished || !FoldController.isAutoEnabled(this)) return;
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
        if (destroyed || finished || !FoldController.isAutoEnabled(this)) return;
        if (!FoldController.isScreenOn(this)) {
            updateNotification("等待下次亮屏；不会主动唤醒屏幕");
            return;
        }
        if (!FoldController.permissionsReady(this)) {
            failRecovery("缺少 ADB 授权，请打开应用检查");
            return;
        }
        if (runningRequest) return;
        runningRequest = true;
        FoldController.requestAutoLock(this, result -> {
            runningRequest = false;
            if (destroyed || finished || !FoldController.isAutoEnabled(this)) return;
            if (result.success) {
                finishRecovery("Inner display lock verified; recovery service stopped, boot preference retained");
                return;
            }
            updateNotification(result.message);
            if (!FoldController.permissionsReady(this)) {
                failRecovery("缺少 ADB 授权，请打开应用检查");
                return;
            }
            if (!FoldController.isScreenOn(this)) {
                pendingEvent = false;
                return;
            }
            if (pendingEvent) {
                pendingEvent = false;
                scheduleForEvent();
                return;
            }
            if (attempt < RETRIES.length) {
                handler.postDelayed(lockAttempt, RETRIES[attempt++]);
            } else {
                failRecovery(result.message);
            }
        });
    }

    private void finishRecovery(String reason) {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        pendingEvent = false;
        if (registered) {
            unregisterReceiver(screenEvents);
            registered = false;
        }
        FoldController.appendLog(this, reason);
        stopForeground(true);
        stopSelf();
    }

    private void failRecovery(String detail) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification failure = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("内屏自动恢复未完成")
                .setContentText("点击打开应用重试；开机恢复仍已开启")
                .setStyle(new Notification.BigTextStyle().bigText(detail
                        + "。本次服务已停止，点击打开应用重试；下次开机仍会尝试恢复。"))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(FAILURE_NOTIFICATION, failure);
        finishRecovery("Automatic recovery failed after waiting/retries: " + detail);
    }

    private Notification notification(String detail) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AutoLockService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("正在恢复内屏锁定")
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pending)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                        "关闭开机恢复", stop).build())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String detail) {
        if (!destroyed && !finished) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(detail));
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
