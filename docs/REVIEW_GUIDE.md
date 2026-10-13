# 审查指南

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
