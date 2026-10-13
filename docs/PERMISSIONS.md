# 当前权限与服务用途（0.3.4）

| 权限/能力 | 用途和边界 |
| --- | --- |
| INTERNET / ACCESS_NETWORK_STATE | 信令、WebRTC、默认网络检测 |
| CHANGE_NETWORK_STATE | connectedDevice 服务运行前提；应用不改变用户网络 |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE | 待机和控制服务；用户可见页面启动、通知停止入口、START_NOT_STICKY |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | 本机确认及当次系统授权之后共享 |
| POST_NOTIFICATIONS | Android 13+ 通知；授权后按钮消失，拒绝仍显示应用状态 |
| RECORD_AUDIO / FOREGROUND_SERVICE_MICROPHONE | 仅内部播放捕获；许可后动态增加 microphone 类型，不读取物理麦克风 |
| WAKE_LOCK | CPU 锁；活跃会话另持屏幕亮锁，10 分钟安全期限、8 分钟刷新、结束释放 |
| 无障碍 | 已确认会话的手势/导航；不读取节点内容 |

无障碍开关、侧载受限设置、USB RSA 及每次录屏授权由用户手动确认。点击被控选项卡先验证 Settings 与实际服务实例；未就绪显示引导，不进入被控页面。

前台 Activity 使用 FLAG_KEEP_SCREEN_ON；活跃会话用 SCREEN_BRIGHT_WAKE_LOCK，不修改全局 screen_off_timeout，不设置 ACQUIRE_CAUSES_WAKEUP，不绕过手动锁屏。屏幕锁 API 已废弃、厂商可能限制；CPU 锁本身不能保证屏幕亮或永不被杀。

不申请全局悬浮窗、文件访问、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。电池设置仅供用户手动调整。

测试注意：普通 uiautomator dump 临时抑制无障碍服务，不能在被控会话中用它观察页面，否则触发安全断开。使用应用专属日志、系统元数据或应用内截图；不因此删除正式安全逻辑。[Android UiAutomation](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
