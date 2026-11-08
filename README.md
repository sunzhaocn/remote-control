# SimpleRC

SimpleRC 是一个 Android 手机互控项目。两台手机安装同一个应用，一台分享屏幕，另一台实时看画面并操作。它可以用来远程协助家人设置手机、操作自己的备用机，或者演示和排查 Android 应用问题。

[下载 Android APK](https://github.com/sunzhaocn/remote-control/releases/latest)

## 能做什么

- **远程看屏幕和操作手机**。支持点击、长按、滑动、返回、主页和最近任务。手机旋转时会同步画面方向和触控坐标。
- **局域网和跨网络连接**。两端通过信令建立会话，尝试 P2P 直连。直连失败时可通过 TURN 中继，应用内可分别查看媒体和控制链路的状态。
- **调整画质和帧率**。支持自动、清晰、高清、原画和自定义码率上限。默认由 MediaCodec 自动选择，也可切换为仅 H.264。新策略以 60 fps 为目标，高帧率档按双方设备能力开放，拥塞或设备过载时会主动降档。
- **共享手机内部声音**。被控端可单独开关，实际能否采集取决于 Android 和音源应用的权限策略。

## 怎么用

1. 在两台 Android 10 或更新版本的手机上安装同一个 APK。
2. 在要分享的手机上选择「被控端」，按提示启用无障碍服务，点击「启用被控」。记下本机 ID 和验证码。
3. 在另一台手机上选择「控制端」，输入目标 ID 和验证码。被控端按提示同意连接和系统录屏授权后，控制端即可查看和操作。

## 技术架构

| 模块 | 实现 |
| --- | --- |
| Android 客户端 | Kotlin 和 Jetpack Compose。用 MediaProjection 采集屏幕，使用 MediaCodec 编解码，通过无障碍服务执行已授权的触控和系统导航。内部声音通过 AudioPlaybackCapture 采集。 |
| 实时传输 | WebRTC 传输视频和音频，独立的 DataChannel 链路传输触控与控制消息。ICE 选择可用路径，STUN 辅助建立直连，coturn 提供 TURN 中继。 |
| 服务端 | Go 提供设备配对、会话管理和 WebSocket 信令，使用 PostgreSQL 和 Redis。仓库包含 Docker Compose 部署配置。 |

客户端位于 [android/](android/)，信令服务位于 [server/](server/)，部署配置位于 [deploy/](deploy/)，Android WebRTC 补丁位于 [webrtc-patch/](webrtc-patch/)。

## 性能实测

单台 Android 真机固定在 60 Hz，以 1272×2800、H.264 和 32 Mbps 码率上限走局域网直连。独立 Windows 接收器在 120 秒内平均组帧 60.04 fps。实际帧率和延迟取决于设备、网络和接收端，双真机和更多机型仍待验证。
