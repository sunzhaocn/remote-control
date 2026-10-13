# 审查指南（0.3.5）

先读 STATE、BUG_FIX_0.3.4、DEVICE_TEST_0.3.3/0.3.4 和 VIDEO_DIAGNOSTICS。0.3.4 有部分双机证据，0.3.5 用户结束测试后仅构建；不要把旧结果移作独立控制连接或新策略通过。

Android 下表简写路径的基目录是 `android/app/src/main/java/com/simplerc/app/`；服务端使用完整仓库路径。

| 模块 | 关键文件 | 审查重点 |
| --- | --- | --- |
| 版本、依赖、权限 | android/app/build.gradle.kts、android/gradle/libs.versions.toml、android/app/src/main/AndroidManifest.xml | 0.3.5/code8、debug签名、锁定AAR、无安装/测试构建钩子 |
| 凭据与模式 | security/DeviceIdentity.kt、security/SecretStore.kt、credentials/Credentials.kt、session/SessionCoordinator.kt | Keystore身份、AES-GCM一次保存、随机计时取消、固定恢复不随机注册、控制角色不发布、6–16/12–32配置化 |
| 凭据注册/限流 | server/internal/api/server.go、server/internal/security/password.go、server/internal/ratelimit/limit.go | 原子唯一归属、Argon2id+pepper、只有CONNECT验证失败计数、多维计数、retryAfterSeconds |
| 租约 | server/internal/lease/lease.go、server/internal/api/server.go | Acquire/Renew/Release归属、等待续租、确认补续、失效终止、旧连接不可删除新连接 |
| 本机授权/前台服务 | MainActivity.kt、capture/ScreenShareService.kt、service/StandbyService.kt、service/ControllerService.kt | 本机确认、每次新录屏授权、Intent单次消费、先startForeground再注册/采集、锁屏及onStop清理 |
| 保活/亮屏/通知 | service/ForegroundSupport.kt、service/NotificationChannels.kt、state/AppStateStore.kt | CPU与显示锁分开、安全期限/释放、无全局timeout修改、2秒通知取样、被杀不假在线/不静默录屏 |
| 无障碍门禁 | accessibility/AccessibilityStatus.kt、accessibility/RemoteControlAccessibilityService.kt、ui/MainScreen.kt | 设置+实际绑定、未满足不进被控、旧实例onDestroy隔离、无节点内容读取 |
| 视频和音频连接 | session/SessionMedia.kt、webrtc/WebRtcEngine.kt | 独立控制以外的媒体PC、SDP/ICE顺序、getStats实际 codec/profile、屏幕源true、直接目标尺寸、source/sender帧率、发送参数返回值 |
| 独立触控连接 | webrtc/ControlTransport.kt、session/SessionMedia.kt、server/internal/api/server.go | 数据专用PeerConnection/ICE/DTLS/SCTP、CONTROL_*类型、原会话归属、独立候选/恢复、停止两端资源，无视频轨道 |
| DataChannel 校验 | session/SessionMedia.kt、control/PeerClock.kt、control/ControlProtocol.kt、docs/PROTOCOL.md | 当前运行校验在SessionMedia；16KiB、队列32KiB、类型/时效/序号/几何/坐标、校准nonce、真实执行回执，旧指令不补发 |
| 坐标 | geometry/GeometryMapper.kt、geometry/OrientationCoordinator.kt、session/SessionMedia.kt、ui/MainScreen.kt | 真实视频内容矩形、留边/越界整段取消、编码与物理像素解耦；旋转采集动态接线仍缺口 |
| 音频静音 | session/SessionMedia.kt、webrtc/WebRtcEngine.kt、webrtc-patch/ | initial muted、SEND_ONLY空轨、START接轨/recording、OFF独立逐步清理、本机权威version/request、ACK5秒失败真实、无麦克风fallback |
| 质量诊断/策略 | webrtc/QualityStats.kt、model/MediaDiagnostics.kt、config/AppConfig.kt、MainViewModel.kt、server/internal/api/server.go | codecId关联实际格式、缺失未知、计数差分/重置、区间编码/解码/缓冲、累计冻结/限制时长、静止与滑动分开；策略下一会话同步 |
| 恢复 | session/SessionCoordinator.kt、webrtc/ControlTransport.kt、session/SessionMedia.kt、recovery/RecoveryStateMachine.kt | 运行协调器15秒独立截止、有限抖动、信令与媒体/控制独立恢复、租约门禁、终止/资源释放；完整重建未实现 |
| UI与菜单 | ui/MainScreen.kt、ui/FloatingNavigation.kt | 普通页面溢出才滚动、控制画面无常驻统计、主动诊断弹窗、菜单音频/断开、菜单横屏溢出滚动、按钮不穿透 |
| 部署 | deploy/docker-compose.yml、deploy/coturn/、server/internal/config/config.go | 自建TURN短期凭据、9443入口、3480/5350、只更新后端、不上传APK |

额外安全审查：独立SDP指纹固定绑定、180天固定ID回收、单应用捕获范围识别与完整输入/旋转组合尚未完成；逐条状态见 REQUIREMENTS_TRACEABILITY。历史辅助状态机不能替代运行接线证据。
