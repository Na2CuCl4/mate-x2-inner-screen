package net.weng.innerlock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(27, 39, 58);
    private static final int MUTED = Color.rgb(100, 113, 130);
    private static final int GREEN = Color.rgb(23, 110, 96);
    private static final int PALE = Color.rgb(235, 243, 240);
    private static final int BACKGROUND = Color.rgb(244, 247, 250);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusTitle;
    private TextView statusBody;
    private TextView permissionText;
    private TextView feedback;
    private TextView autoSummary;
    private LinearLayout permissionSetup;
    private Button lockButton;
    private Button unlockButton;
    private Switch autoSwitch;
    private boolean updating;
    private boolean busy;
    private String latestDetails = "尚未读取状态。";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildInterface();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermissions();
        if (FoldController.isAutoEnabled(this) && FoldController.permissionsReady(this)) {
            if (!AutoLockService.start(this)) {
                feedback.setText("自动恢复未能启动，请查看诊断信息和系统应用设置。");
            }
        }
        refreshStatus();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView eyebrow = text("MATE X2", 12, GREEN, true);
        eyebrow.setLetterSpacing(0.14f);
        content.addView(eyebrow);
        TextView title = text("内屏锁定", 30, INK, true);
        add(content, title, 6);
        add(content, text("固定使用大屏，随时恢复自动切换。", 15, MUTED, false), 7);

        LinearLayout state = card(content, 22);
        add(state, text("当前状态", 12, MUTED, true), 0);
        statusTitle = text("正在读取…", 23, INK, true);
        add(state, statusTitle, 8);
        statusBody = text("正在检查折叠屏服务", 14, MUTED, false);
        add(state, statusBody, 8);
        permissionText = text("", 13, MUTED, false);
        add(state, permissionText, 13);

        lockButton = button("锁定内屏", true);
        lockButton.setOnClickListener(v -> changeMode(true));
        add(content, lockButton, 18);
        unlockButton = button("恢复自动切换", false);
        unlockButton.setOnClickListener(v -> changeMode(false));
        add(content, unlockButton, 9);

        LinearLayout automation = card(content, 18);
        autoSwitch = new Switch(this);
        autoSwitch.setText("开机后自动恢复内屏锁定");
        autoSwitch.setTextSize(16);
        autoSwitch.setTextColor(INK);
        autoSwitch.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        autoSwitch.setPadding(0, dp(3), 0, dp(3));
        automation.addView(autoSwitch, new LinearLayout.LayoutParams(-1, -2));
        autoSummary = text("开启后在亮屏时恢复锁定，并显示后台运行通知。", 13, MUTED, false);
        add(automation, autoSummary, 10);
        add(automation, text("启动画面、恢复模式等早期阶段仍可能使用外屏。", 12, MUTED, false), 8);
        autoSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (updating) return;
            if (!FoldController.permissionsReady(this)) {
                refreshPermissions();
                toast("请先完成下面的一次性电脑授权。");
                return;
            }
            try {
                if (enabled && !AutoLockService.start(this)) {
                    feedback.setText("自动恢复未能启动，请查看诊断信息和系统应用设置。");
                    syncAutoSwitch();
                    return;
                }
                if (!enabled) AutoLockService.stop(this);
                feedback.setText(enabled ? "自动恢复已开启，正在检查内屏状态。" : "自动恢复已关闭；当前显示状态保持不变。");
                syncAutoSwitch();
                handler.postDelayed(this::refreshStatus, 1800);
            } catch (RuntimeException e) {
                FoldController.setAutoEnabled(this, false);
                syncAutoSwitch();
                feedback.setText("后台服务未能启动：" + e.getMessage());
            }
        });

        permissionSetup = card(content, 18);
        add(permissionSetup, text("首次使用：电脑授权一次", 16, INK, true), 0);
        add(permissionSetup, text("安装后执行下面两条 ADB 命令。正常重启后权限会保留；重新安装或撤销权限后可能需要再次授权。", 13, MUTED, false), 8);
        TextView commands = text(grantCommands(), 12, INK, false);
        commands.setTypeface(Typeface.MONOSPACE);
        commands.setTextIsSelectable(true);
        commands.setPadding(dp(12), dp(12), dp(12), dp(12));
        commands.setBackground(round(PALE, 12));
        add(permissionSetup, commands, 12);
        Button copy = button("复制授权命令", false);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("内屏锁定授权", grantCommands()));
            toast("命令已复制");
        });
        add(permissionSetup, copy, 9);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("刷新状态", false);
        refresh.setOnClickListener(v -> refreshStatus());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1);
        tools.addView(refresh, half);
        Button logs = button("诊断信息", false);
        logs.setOnClickListener(v -> showDiagnostics());
        LinearLayout.LayoutParams otherHalf = new LinearLayout.LayoutParams(0, dp(48), 1);
        otherHalf.leftMargin = dp(10);
        tools.addView(logs, otherHalf);
        add(content, tools, 18);
        feedback = text("", 13, MUTED, false);
        feedback.setTextIsSelectable(true);
        add(content, feedback, 12);

        LinearLayout help = card(content, 16);
        add(help, text("如果开机后没有自动恢复", 14, INK, true), 0);
        add(help, text("在华为“应用启动管理”中允许本应用自启动、关联启动和后台活动。强行停止应用后，需要重新打开它才能恢复自动运行。", 13, MUTED, false), 8);
        Button appSettings = button("打开应用设置", false);
        appSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        add(help, appSettings, 10);
        add(content, text("仅控制本机折叠屏 · 无联网权限 · 版本 1.0", 11, MUTED, false), 18);
    }

    private void changeMode(boolean lock) {
        if (busy) return;
        if (!FoldController.permissionsReady(this)) {
            refreshPermissions();
            toast("需要先完成一次性电脑授权。");
            return;
        }
        busy = true;
        refreshPermissions();
        feedback.setText(lock ? "正在锁定内屏…" : "正在恢复自动切换…");
        FoldController.Callback callback = result -> {
            if (isFinishing() || isDestroyed()) return;
            busy = false;
            render(result);
            refreshPermissions();
        };
        if (lock) FoldController.requestLock(this, callback);
        else FoldController.requestUnlock(this, callback);
    }

    private void refreshStatus() {
        if (isFinishing() || isDestroyed() || busy) return;
        FoldController.requestStatus(this, result -> {
            if (isFinishing() || isDestroyed()) return;
            render(result);
            refreshPermissions();
        });
    }

    private void render(FoldController.Result result) {
        latestDetails = result.details == null ? "" : result.details;
        if (result.state == null || !result.state.valid) {
            statusTitle.setText(FoldController.permissionsReady(this) ? "暂时无法读取" : "等待一次性授权");
            statusBody.setText(result.message);
        } else {
            FoldController.State state = result.state;
            statusTitle.setText(state.locked ? "内屏已锁定" : "未锁定内屏");
            statusTitle.setTextColor(state.locked ? GREEN : INK);
            String display = state.displayMode == 1 ? "内屏" : state.displayMode == 2 ? "外屏" : "待确认";
            statusBody.setText("当前显示：" + display + "  ·  " + (state.screenOn ? "屏幕已亮" : "等待亮屏"));
        }
        if (result.message != null) feedback.setText(result.message);
        syncAutoSwitch();
    }

    private void refreshPermissions() {
        boolean dump = FoldController.hasDumpPermission(this);
        boolean settings = FoldController.hasSettingsPermission(this);
        boolean ready = dump && settings;
        permissionText.setText("读取与控制权限：" + (dump ? "已授权" : "未授权")
                + "  ·  设置权限：" + (settings ? "已授权" : "未授权"));
        permissionText.setTextColor(ready ? GREEN : Color.rgb(154, 92, 31));
        permissionSetup.setVisibility(ready ? View.GONE : View.VISIBLE);
        lockButton.setEnabled(ready && !busy);
        unlockButton.setEnabled(ready && !busy);
        autoSwitch.setEnabled(ready && !busy);
        syncAutoSwitch();
    }

    private void syncAutoSwitch() {
        updating = true;
        boolean enabled = FoldController.isAutoEnabled(this);
        autoSwitch.setChecked(enabled);
        autoSummary.setText(enabled
                ? "已开启。重启和再次亮屏后自动检查；恢复自动切换会同时关闭此功能。"
                : "开启后在亮屏时恢复锁定，并显示后台运行通知。");
        updating = false;
    }

    private void showDiagnostics() {
        TextView body = text("设备：" + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                + "\n应用 UID：" + android.os.Process.myUid()
                + "\n\n" + latestDetails + "\n\n最近操作\n" + FoldController.getLog(this), 12, INK, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(18), dp(12), dp(18), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("本机诊断").setView(scroll)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("内屏锁定诊断", body.getText()));
                    toast("诊断信息已复制");
                }).show();
    }

    private String grantCommands() {
        return "adb shell pm grant " + getPackageName() + " android.permission.DUMP\n"
                + "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(primary ? Color.WHITE : GREEN);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(14), dp(9), dp(14), dp(9));
        view.setBackground(round(primary ? GREEN : Color.rgb(229, 239, 236), 14));
        view.setStateListAnimator(null);
        return view;
    }

    private LinearLayout card(LinearLayout parent, int top) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(18), dp(17), dp(18), dp(17));
        view.setBackground(round(Color.WHITE, 20));
        add(parent, view, top);
        return view;
    }

    private void add(LinearLayout parent, View view, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginTop);
        parent.addView(view, params);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
