# SimpleRC · Android 手机互控

SimpleRC 让两台 Android 手机互相远程控制。两端安装同一个应用，连接时分别选择控制端和被控端。被控端授权后，控制端可以实时观看屏幕并操作手机。

[下载 Android APK](https://github.com/sunzhaocn/remote-control/releases/latest)

## 可以用在哪些场景

- **远程协助家人**。在对方授权后查看屏幕，帮忙设置手机或操作应用。
- **操作自己的备用机**。在同一局域网或不同网络下连接另一台 Android 手机。
- **演示和排查 Android 应用**。共享屏幕和系统允许采集的应用声音，复现操作步骤。

## 功能

- **远程看屏幕和操作手机**。支持点击、长按、滑动、返回、主页和最近任务。手机旋转时会同步画面方向和触控坐标。
- **局域网和跨网络连接**。支持局域网直连，跨网络时尝试 P2P 直连，直连失败时可通过 TURN 中继。应用内可分别查看媒体和控制链路的状态。
- **调整画质和帧率**。支持画质预设和自定义码率上限。默认优先使用 MediaCodec 硬件编码，也可切换为仅 H.264。目标帧率可选 30 或 60 fps，实际帧率受设备能力和网络状况限制。90 fps 及以上暂未开放。
- **共享手机内部声音**。被控端可单独开关，实际能否采集取决于 Android 和音源应用的权限策略。

## 怎么用

1. 在两台 Android 10 或更新版本的手机上安装同一个 APK。
2. 在要分享的手机上选择「被控端」，按提示启用无障碍服务，点击「启用被控」。记下本机 ID 和验证码。
3. 在另一台手机上选择「控制端」，输入目标 ID 和验证码。被控端按提示同意连接和系统录屏授权后，控制端即可查看和操作。

## 技术架构

| 技术 | 实现的功能 |
| --- | --- |
| Kotlin、Jetpack Compose | 构建同一个 Android 应用，提供控制端和被控端界面。 |
| MediaProjection、MediaCodec、AudioPlaybackCapture | 采集屏幕，编解码实时视频，按用户设置采集系统允许共享的内部声音。 |
| Android 无障碍服务 | 执行用户授权的远程手势和系统导航。 |
| WebRTC、DataChannel | 传输实时音视频，通过独立控制链路回传触控和会话消息。 |
| ICE、STUN、coturn | 协商可用的网络路径，支持直连和 TURN 中继。 |
| Go、WebSocket、PostgreSQL、Redis | 提供设备配对、会话管理和信令服务。 |
| Docker Compose | 提供信令服务、数据库和 TURN 中继的自部署配置。 |

客户端位于 [android/](android/)，信令服务位于 [server/](server/)，部署配置位于 [deploy/](deploy/)，Android WebRTC 补丁位于 [webrtc-patch/](webrtc-patch/)。开发和自部署可参考 [构建指南](docs/BUILDING.md)与[部署指南](docs/DEPLOYMENT.md)。
