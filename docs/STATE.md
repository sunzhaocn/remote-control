# SimpleRC 当前状态

更新日期：2026-10-13。版本：0.2.0（versionCode 2，debug）。所有“已编译”仅表示构建验证，均未实机验证。

## 已编译

- Android 单 APK：单页角色 UI、权限引导、待机/共享前台服务、设备身份、随机/自定义凭据、WSS 信令、本机确认、MediaProjection 视频、WebRTC SDP/ICE、可靠 DataChannel、触控与系统导航、方向跟随、视频留边坐标换算、有限重连、短期 TURN 凭据和候选路径显示。
- Go 后端：设备注册认证、随机/固定凭据、Argon2id+pepper、固定 ID 归属与 180 天清理、随机 ID 原子占用、统一失败延迟、多维限流、Redis 租约、确认超时、信令中继、租约续期、短期 TURN 凭据。
- 部署：PostgreSQL、Redis、Go 服务、Coturn、Nginx HTTPS/WSS、APK 下载和证书续期已部署。旧 lightremote 项目已按用户要求删除。

## 部分实现或未实现

- 内部音频未实现：标准 AAR 不能注入 AudioPlaybackCapture；运行时明确显示“内部音频不可用（未接入补丁构建）”。现有补丁是设计规范，没有补丁 AAR。WSL2 组件已安装，但 Windows 必须重启后才能安装 Ubuntu 并执行预计 1–8 小时、80 GB 规划的源码构建。
- 音频独立静音只有接口和状态机，因无音频轨道不能工作。
- H.264 优先排序、动态码率/分辨率/帧率、完整质量统计尚未实现。
- 路径按实际选中候选区分 TURN/P2P 与 UDP/TCP；LAN 保守显示“局域网未确认”。
- Android 14 单应用共享范围没有可靠运行时拒绝逻辑。
- DataChannel 已校验会话、序号、时效、几何版本和范围，但没有远端执行成功回执。
- 信令重连和 ICE restart 已接入；PeerConnection 失败后的完整重建和媒体停滞检测不完整。
- P2 功能未做：60fps、几何诊断层、本地输入后发送文字、高级统计。

## 构建与部署

- `:app:assembleDebug` 成功；APK：`android/app/build/outputs/apk/debug/app-debug.apk`。
- APK SHA-256：`0bd49322b889fe4c4aae8791f9c2fbda66a6b2e5551e254ccc22256fcfa917a3`。
- `go build ./...`、`go vet ./...`、`docker compose config` 成功。
- 公网健康检查：`https://49.233.43.167:9443/simplerc/healthz` 返回 204。
- APK 下载：`https://49.233.43.167:9443/simplerc-download/SimpleRC-0.2.0-debug.apk`。
- TURN：3480 UDP/TCP、5350 TLS；relay 49261–49360。

## 未实机验证

全部手机行为均待用户双机验收。没有运行自动测试、模拟器、ADB、安装 APK 或操作手机。
