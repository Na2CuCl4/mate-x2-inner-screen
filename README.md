# Mate X2 内屏锁定

一个无需 root、无需 Shizuku 的本机控制 APK。为 Mate X2（TET-AN00 / HarmonyOS 4.2.0.182）制作，使用此固件的折叠屏调试接口。

首次使用通过电脑 ADB 授予两项权限。

## 功能

- **锁定内屏**：开启系统显示模式锁定并固定到模式 1。
- **恢复自动切换**：关闭自动保持和系统锁定开关，验证系统已恢复正常折叠策略。
- **开机后自动恢复内屏锁定**：在开机或屏幕再次点亮后检查状态，必要时恢复内屏锁定。
- **诊断信息**：显示实际应用 UID、服务返回状态及最近操作结果。

自动模式使用前台服务和常驻通知，监听亮屏事件，不主动唤醒屏幕、不在息屏期间循环轮询。通知中的“关闭自动保持”只关闭自动功能；要解除当前内屏锁定，请点击应用里的“恢复自动切换”。

## 安装与首次授权

成品在 `dist/Mate-X2-InnerScreen.apk`。本次调试已安装到连接的 Mate X2 并授予以下权限；正常使用无需重复授权。

如果以后重新安装，在电脑的 ADB 目录运行：

```powershell
.\adb.exe shell pm grant net.weng.innerlock android.permission.DUMP
.\adb.exe shell pm grant net.weng.innerlock android.permission.WRITE_SECURE_SETTINGS
```

连接多部手机时，在 `shell` 前加 `-s 手机序列号`。安装或授权之后打开“内屏锁定”应用。

`DUMP` 用于读取和调用 `fold_screen` 调试入口；`WRITE_SECURE_SETTINGS` 用于写入 `lock_display_mode` 全局开关。两者是系统支持 ADB 授予的 development 权限，正常重启后会保留。应用不声明 INTERNET 权限，不含广告、统计或第三方运行库。

若华为系统询问通知权限，请允许，以便看到自动保持服务的状态。部分华为固件会自行加入通知权限管理。

## 自动模式

打开“开机后自动恢复内屏锁定”。应用会在开机完成及亮屏后恢复；关掉开关不会解除当前锁定。

如果华为系统拦截自启动，到“设置 → 应用和服务 → 应用启动管理”中，允许本应用自启动、关联启动和后台活动。强行停止应用后，需要手动重新打开，Android 才会恢复向它发送启动广播。

这项功能不会改变折叠屏硬件，也不会改写系统启动镜像。开机动画、恢复模式和服务尚未就绪时，外屏仍可能点亮。卸载应用前若希望恢复自动切换，先在应用中点击对应按钮。

## 源码与构建

纯 Java / Android 原生界面，最低 Android 8（API 26），目标 API 31。其余设备即使能安装，也不代表支持此华为折叠接口。

首次准备工具、构建：

```powershell
& 'D:\anaconda3\python.exe' tools/setup_tools.py
& 'D:\anaconda3\python.exe' build.py
```

无需 Android Studio 或 Gradle。工具从官方来源下载到工作区 `tmp/innerlock-build-tools`，不修改全局 PATH。详情见 `tools/README.md`。

本地签名密钥保存在 `.local/`，不会随源码提交。保留它才能构建可以覆盖升级当前安装的 APK。不要分享该目录。
