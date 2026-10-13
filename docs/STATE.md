# SimpleRC 当前状态

更新日期：2026-10-13。版本：0.3.0（versionCode 3，debug）。所有“已编译”仅表示构建验证，均未实机验证。

## 已编译

- Android 单 APK：角色/权限、身份与凭据、WSS 信令、本机确认、MediaProjection 视频、WebRTC、可靠 DataChannel、触控与系统导航、坐标与方向、有限重连、TURN、路径显示。
- 内部音频：固定 GetStream WebRTC 1.3.10 提交的最小 AudioRecord 工厂补丁；AudioPlaybackCapture 使用屏幕采集创建的同一 MediaProjection；音频许可拒绝不阻断视频；控制端本地立即静音，被控端通过 `RtpSender.setTrack(null,false)` 脱离轨道并释放 AudioSource/AudioTrack，ACK 后显示停止发送；恢复时重建并重新挂接轨道。
- Go 后端和公网部署：PostgreSQL、Redis、Coturn、Nginx HTTPS/WSS、短期 TURN 凭据、APK 下载及证书续期。

## 构建与产物

- 补丁 AAR：`android/app/libs/stream-webrtc-android-simplerc-1.3.10.aar`，SHA-256 `8f218e65c7cde1faa4d6bfbb06e79eae3b0134921c65cf376034459acf7da337`。
- 补丁 AAR 首次 WSL2 构建耗时 7 分 49 秒；实际占用：源码 258 MiB、Gradle 缓存 2.9 GiB、Android SDK 754 MiB。
- `:app:assembleDebug` 成功；APK SHA-256 `1a5e329683c72bda7fcc47340fe004df586e2c2649aea039ba00a5edb8d237de`。
- `go build ./...`、`go vet ./...`、`docker compose config` 在上一交付已成功，本轮后端未改动。

## 未完成与限制

- 全部手机行为待用户双机验收；没有运行自动测试、模拟器、ADB、安装或手机操作。
- 部分应用因 DRM、`FLAG_SECURE`、播放捕获策略或非允许 usage 不提供可捕获音频，这是 Android 平台限制。
- 静音真实停包需在双机验收中以 WebRTC outbound-rtp `bytesSent` 增量停止确认；本轮仅完成代码和编译验证。
- H.264 明确排序、动态质量策略、完整质量统计、PeerConnection 失败后的完整重建、媒体停滞检测、DataChannel 执行成功回执、Android 14 单应用共享范围拒绝仍未完整实现。
- P2 功能未做：60fps、几何诊断层、本地输入后发送文字、高级统计。

## 部署

- 公网入口：`https://49.233.43.167:9443/simplerc/`；TURN 3480 UDP/TCP、5350 TLS。
- APK 下载：`https://49.233.43.167:9443/simplerc-download/SimpleRC-0.3.0-debug.apk`。
