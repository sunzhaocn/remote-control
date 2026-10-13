# Android 权限用途

## 阶段 1 已声明

| 权限/能力 | 用途 |
| --- | --- |
| `INTERNET` | 阶段 2 的信令和阶段 3 的 WebRTC 网络连接 |
| `ACCESS_NETWORK_STATE` | 判断网络可用状态 |
| `CHANGE_NETWORK_STATE` | 满足 `connectedDevice` 前台服务的官方运行前提；应用不修改用户网络配置 |
| `FOREGROUND_SERVICE` | 运行用户主动启用且有明确通知的待机服务 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 长期维持与另一设备/服务的网络连接 |
| `POST_NOTIFICATIONS` | Android 13+ 显示待机和共享状态通知；拒绝后应用内仍显示状态 |
| 无障碍服务 | 在确认的会话中执行点击、滑动和系统导航；当前阶段只提供权限检查和服务骨架 |

无障碍配置只声明手势能力和窗口状态事件，不声明读取窗口内容。当前实现不读取、记录、保存或传输节点文字。

## 后续阶段才声明

- `FOREGROUND_SERVICE_MEDIA_PROJECTION`：阶段 3 屏幕共享。
- `RECORD_AUDIO` 与 `FOREGROUND_SERVICE_MICROPHONE`：阶段 5 内部播放音频；不读取物理麦克风。
- 不申请全局悬浮窗、文件访问或忽略电池优化权限。

