package net.weng.innerlock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** All device operations run on one worker; callbacks are delivered on the UI thread. */
public final class FoldController {
    private static final String PREFS = "inner_lock";
    private static final String AUTO = "enableAuto";
    private static final String LOG = "diagnosticLog";
    private static final String LOCK_SETTING = "lock_display_mode";
    private static final String DUMP = "android.permission.DUMP";
    private static final String WRITE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS";
    private static final int LOG_LIMIT = 24000;
    private static final Object LOG_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "inner-lock-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern MODE = Pattern.compile("(?m)^\\s*DisplayMode\\s*=\\s*(-?\\d+)\\s*$");

    private FoldController() {}

    public interface Callback {
        void onResult(Result result);
    }

    public static final class State {
        public final boolean valid;
        public final boolean locked;
        public final boolean screenOn;
        public final int displayMode;
        public final int persistMode;
        public final boolean testPolicy;
        public final String raw;

        private State(boolean valid, boolean screenOn, int displayMode,
                      int persistMode, boolean testPolicy, String raw) {
            this.valid = valid;
            this.screenOn = screenOn;
            this.displayMode = displayMode;
            this.persistMode = persistMode;
            this.testPolicy = testPolicy;
            this.locked = valid && displayMode == 1 && persistMode == 1 && testPolicy;
            this.raw = raw;
        }

        public String summary() {
            return "DisplayMode=" + displayMode + ", persist=" + persistMode
                    + ", TestPolicy=" + testPolicy + ", screenOn=" + screenOn
                    + ", verified=" + valid;
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final String details;
        /** Can be null when permission or screen-state checks fail before a query. */
        public final State state;

        private Result(boolean success, String message, String details, State state) {
            this.success = success;
            this.message = message;
            this.details = details;
            this.state = state;
        }
    }

    private interface Operation {
        Result run(Context context) throws Exception;
    }

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasDumpPermission(Context context) {
        return context.checkSelfPermission(DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasSettingsPermission(Context context) {
        return context.checkSelfPermission(WRITE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean permissionsReady(Context context) {
        return hasDumpPermission(context) && hasSettingsPermission(context);
    }

    public static boolean isAutoEnabled(Context context) {
        return preferences(context).getBoolean(AUTO, false);
    }

    public static void setAutoEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(AUTO, enabled).apply();
    }

    public static boolean isScreenOn(Context context) {
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isInteractive();
    }

    public static void requestStatus(Context context, Callback callback) {
        submit(context, callback, c -> {
            if (!hasDumpPermission(c)) return missingPermissions(c);
            State state = readState(c);
            return new Result(state.valid,
                    !state.valid ? "暂时无法读取折叠服务" : state.locked ? "已锁定内屏" : "当前未锁定内屏",
                    state.raw, state);
        });
    }

    public static void requestLock(Context context, Callback callback) {
        submit(context, callback, c -> lock(c, false));
    }

    static void requestAutoLock(Context context, Callback callback) {
        submit(context, callback, c -> lock(c, true));
    }

    /** Stop automatic relocking before scheduling the restore, including queued auto work. */
    public static void requestUnlock(Context context, Callback callback) {
        setAutoEnabled(context, false);
        context.getApplicationContext().stopService(new Intent(context, AutoLockService.class));
        submit(context, callback, FoldController::unlock);
    }

    private static void submit(Context context, Callback callback, Operation operation) {
        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            Result result;
            try {
                result = operation.run(app);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                result = new Result(false, "操作已中断", exception.toString(), null);
            } catch (Exception exception) {
                result = new Result(false, "操作失败，请查看诊断记录", exception.toString(), null);
            }
            appendLog(app, result.message + "\n" + result.details);
            if (callback != null) {
                final Result delivered = result;
                MAIN.post(() -> callback.onResult(delivered));
            }
        });
    }

    private static Result missingPermissions(Context context) {
        return new Result(false, "尚未获得所需的 ADB 授权",
                "App UID=" + Process.myUid() + ", DUMP=" + hasDumpPermission(context)
                        + ", WRITE_SECURE_SETTINGS=" + hasSettingsPermission(context), null);
    }

    private static Result lock(Context context, boolean automatic) throws Exception {
        if (automatic && !isAutoEnabled(context)) return cancelled();
        if (!permissionsReady(context)) return missingPermissions(context);
        if (!isScreenOn(context)) {
            return new Result(false, "等待屏幕点亮后锁定；不会主动唤醒屏幕", "screenOn=false", null);
        }
        State initial = readState(context);
        if (initial.locked) return new Result(true, "已锁定内屏", initial.raw, initial);
        if (!initial.valid) {
            return new Result(false, "折叠服务尚未就绪", initial.raw, initial);
        }
        if (automatic && !isAutoEnabled(context)) return cancelled();
        if (!isScreenOn(context)) {
            return new Result(false, "屏幕已熄灭，等待下次亮屏", initial.raw, initial);
        }
        if (!Settings.Global.putInt(context.getContentResolver(), LOCK_SETTING, 1)) {
            return new Result(false, "无法启用系统的显示模式锁定开关", initial.raw, initial);
        }
        // Huawei observes the setting on another thread; allow that observer to run.
        Thread.sleep(180);
        StringBuilder detail = new StringBuilder("App UID=" + Process.myUid() + "\n");
        State state = initial;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (automatic && !isAutoEnabled(context)) return cancelled();
            if (!isScreenOn(context)) {
                return new Result(false, "屏幕已熄灭，等待下次亮屏", detail.toString(), state);
            }
            Command command = execute(6500, "/system/bin/dumpsys", "-t", "4",
                    "fold_screen", "lockDisplayMode", "1");
            detail.append("lock attempt ").append(attempt + 1).append(": ").append(command.describe()).append('\n');
            Thread.sleep(250);
            state = readState(context);
            detail.append(state.raw).append('\n');
            if (state.locked) return new Result(true, "已锁定内屏", detail.toString(), state);
            if (command.timedOut) break;
        }
        return new Result(false, "未确认锁定成功，请查看诊断记录", detail.toString(), state);
    }

    private static Result cancelled() {
        return new Result(false, "自动锁定已关闭", "Skipped queued automatic operation", null);
    }

    private static Result unlock(Context context) throws Exception {
        if (!permissionsReady(context)) return missingPermissions(context);
        // The service's unlock method refuses to run while the display is off.
        if (!isScreenOn(context)) {
            return new Result(false, "自动保持已关闭；请点亮屏幕后再次恢复自动切换", "screenOn=false", null);
        }
        State initial = readState(context);
        if (!initial.valid) return new Result(false, "无法读取折叠服务，未更改显示设置", initial.raw, initial);
        if (initial.persistMode != 0
                && Settings.Global.getInt(context.getContentResolver(), LOCK_SETTING, 0) == 0) {
            // A prior attempt can have reached the observer after the screen turned off.
            // Force a real setting transition; rewriting an unchanged zero may not notify it.
            if (!Settings.Global.putInt(context.getContentResolver(), LOCK_SETTING, 1)) {
                return new Result(false, "无法重新触发显示模式恢复", initial.raw, initial);
            }
            Thread.sleep(180);
        }
        if (!Settings.Global.putInt(context.getContentResolver(), LOCK_SETTING, 0)) {
            return new Result(false, "无法关闭系统的显示模式锁定开关", initial.raw, initial);
        }
        State state = initial;
        StringBuilder detail = new StringBuilder("lock_display_mode=0; automatic relocking disabled\n");
        for (int attempt = 0; attempt < 3; attempt++) {
            Thread.sleep(250);
            state = readState(context);
            detail.append(state.raw).append('\n');
            if (state.valid && state.persistMode == 0 && !state.testPolicy) {
                return new Result(true, "已恢复内外屏自动切换", detail.toString(), state);
            }
            if (!isScreenOn(context)) break;
        }
        return new Result(false, "自动保持已关闭，但尚未确认系统恢复自动切换", detail.toString(), state);
    }

    private static State readState(Context context) throws IOException, InterruptedException {
        Command dump = execute(6500, "/system/bin/dumpsys", "-t", "4", "fold_screen");
        Command property = execute(2500, "/system/bin/getprop", "persist.sys.foldDispMode");
        Matcher matcher = MODE.matcher(dump.output);
        int mode = matcher.find() ? parseInt(matcher.group(1)) : -1;
        int persisted = parseInt(property.output.trim());
        boolean test = Pattern.compile("(?m)^\\s*TestPosturePreprocess\\s*$").matcher(dump.output).find();
        boolean valid = !dump.timedOut && !property.timedOut && dump.exitCode == 0
                && property.exitCode == 0 && mode >= 0 && persisted >= 0;
        String raw = "App UID=" + Process.myUid() + "\nfold_screen: " + dump.describe()
                + "\npersist.sys.foldDispMode: " + property.describe();
        return new State(valid, isScreenOn(context), mode, persisted, test, raw);
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static final class Command {
        final int exitCode;
        final boolean timedOut;
        final long elapsedMillis;
        final String output;
        Command(int exitCode, boolean timedOut, long elapsedMillis, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.elapsedMillis = elapsedMillis;
            this.output = output;
        }
        String describe() {
            return "exit=" + exitCode + ", timeout=" + timedOut + ", elapsed=" + elapsedMillis + "ms\n" + output;
        }
    }

    /** Drain output concurrently so a full stdout pipe cannot deadlock waitFor(). */
    private static Command execute(long timeoutMillis, String... command)
            throws IOException, InterruptedException {
        final long began = SystemClock.elapsedRealtime();
        final java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final LimitedOutput captured = new LimitedOutput();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) captured.add(buffer, count);
            } catch (IOException ignored) {
                // A timed-out process has its stream closed below.
            }
        }, "inner-lock-output");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            }
            reader.join(500);
            return new Command(finished ? process.exitValue() : -1, !finished,
                    SystemClock.elapsedRealtime() - began, captured.text());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
        }
    }

    private static final class LimitedOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        synchronized void add(byte[] buffer, int count) {
            int keep = Math.min(count, 65536 - bytes.size());
            if (keep > 0) bytes.write(buffer, 0, keep);
        }
        synchronized String text() { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
    }

    static void appendLog(Context context, String entry) {
        synchronized (LOG_LOCK) {
            SharedPreferences prefs = preferences(context);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
            String log = prefs.getString(LOG, "") + "\n[" + time + "] " + entry + "\n";
            if (log.length() > LOG_LIMIT) log = log.substring(log.length() - LOG_LIMIT);
            prefs.edit().putString(LOG, log).apply();
        }
    }

    public static String getLog(Context context) {
        synchronized (LOG_LOCK) { return preferences(context).getString(LOG, ""); }
    }

    public static void clearLog(Context context) {
        synchronized (LOG_LOCK) { preferences(context).edit().remove(LOG).apply(); }
    }
}
