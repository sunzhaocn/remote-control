# 审查指南

## 第二轮反馈 0.3.3 审查入口

- 根因/推测/复测：docs/BUG_FIX_0.3.3.md。
- 整页约束与版本显示：ui/MainScreen.kt。
- 一次加密持久化：security/SecretStore.kt；本机配置与注册确认：session/SessionCoordinator.kt、model/AppState.kt。
- 握手错误分类/过期 socket 隔离：signaling/ConnectionFailure.kt、signaling/SignalClient.kt、SessionCoordinator。
- SDP 创建/设置回调、ICE 缓存和会话清理：session/SessionMedia.kt。
- 前台服务启动后注册/采集：service/StandbyService.kt、capture/ScreenShareService.kt、MainActivity.kt。
- 服务端心跳期限和旧连接清理归属：server/internal/api/server.go 的 webSocket/removeClient。本轮仅该后端补丁部署，APK 保持本地。

当前交付已经构建验证、尚未实机验证。请先看 `STATE.md` 和 `PHASE_REPORTS.md`，不要从编译成功或目录存在推断功能已端到端完成。

| 模块 | 关键文件 | 审查重点 |
| --- | --- | --- |
| 工程与依赖 | `android/gradle/libs.versions.toml`、`android/app/build.gradle.kts` | 版本兼容、标准 WebRTC AAR、无测试钩子 |
| 单页 UI/角色 | `ui/MainScreen.kt`、`MainViewModel.kt`、`state/AppStateStore.kt` | 不滚动、冷启动未启用、角色切换停止服务、无假在线 |
| 权限/无障碍 | `AndroidManifest.xml`、`accessibility/*`、`res/xml/accessibility_service_config.xml` | 最小权限、受限设置说明、不读取节点内容 |
| 凭据与身份 | `security/DeviceIdentity.kt`、`SecretStore.kt`、`credentials/Credentials.kt` | Keystore、AES-GCM、规范化、随机数、无日志泄密 |
| 服务端凭据 | `server/internal/security/password.go`、`migrations/*` | Argon2id+pepper、固定 ID 唯一归属、回收索引 |
| 租约与限流 | `server/internal/lease/lease.go`、`ratelimit/limit.go`、`api/server.go` | Redis 原子归属、续约/释放、IP/设备/目标组合、确认超时 |
| 信令 | `signaling/*`、`session/SessionCoordinator.kt`、`server/internal/api/server.go` | WSS 设备签名、序号/时效、注册/确认/中继/撤销 |
| MediaProjection/FGS | `MainActivity.kt`、`capture/ScreenShareService.kt`、`session/SessionMedia.kt`、Manifest | 授权后启动、Intent 单次消费、ScreenCapturerAndroid、onStop 清理、通知停止 |
| WebRTC 视频 | `webrtc/WebRtcEngine.kt`、`session/SessionMedia.kt` | 轨道、SDP/ICE、渲染、ICE restart；编码优先级与完整重建缺口 |
| DataChannel 校验 | `control/ControlProtocol.kt`、`docs/PROTOCOL.md` | 会话、序号、时效、几何版本、字段范围和成功回执 |
| 坐标变换 | `geometry/GeometryMapper.kt`、`docs/GEOMETRY.md` | 内容矩形、旋转、留边拒绝、全路径取消、编码分辨率解耦 |
| 静音状态机 | `audio/InternalAudio.kt`、`webrtc-patch/` | 标准 AAR 明确不可用、分离 sender、远端确认、无虚构 AAR |
| 重连状态机 | `session/SessionCoordinator.kt`、`session/SessionMedia.kt`、`recovery/RecoveryStateMachine.kt` | 120 秒、有限退避、租约续期、ICE restart、终止条件 |
| TURN/路径 | `session/SessionMedia.kt`、`server/internal/api/server.go`、`deploy/coturn/*` | HMAC 短期凭据、实际选中候选、relay 协议、LAN 不误判 |
| 状态通知 | `service/StandbyService.kt`、`NotificationChannels.kt` | 停止入口、START_NOT_STICKY、通知文案不含秘密 |

建议审查顺序：工具链和 Manifest → 状态/权限 → `api/server.go` 安全边界 → `SessionCoordinator` → `SessionMedia` → 几何 → 音频缺口 → 重连/TURN → 对照表与未完成项。

## 内部音频与独立静音

- 补丁入口：`webrtc-patch/patches/0001-audioplaybackcapture-factory.patch`；固定提交与校验值：`webrtc-patch/UPSTREAM_REVISION`、`webrtc-patch/SHA256SUMS`。
- AudioPlaybackCapture 构造与 ADM：`android/app/src/main/java/com/simplerc/app/webrtc/WebRtcEngine.kt`。
- 同一 MediaProjection、音频 sender、静音脱轨/释放、恢复与 AUDIO_STATE ACK：`android/app/src/main/java/com/simplerc/app/session/SessionMedia.kt`。
- 权限与前台服务类型：`MainActivity.kt`、`capture/ScreenShareService.kt`、`AndroidManifest.xml`。
- 审查重点：音频权限拒绝不阻断视频；工厂不能回退到麦克风；STOP 必须先 `setTrack(null,false)` 并释放轨道，再回复 STOPPED；控制端先停本地播放再等待 ACK。
