# SimpleRC 阶段 0 技术可行性核查

更新日期：2026-10-12  
状态：阶段 0 已完成；用户已附条件接受方案 A  
核查边界：未写业务代码，未连接或操作手机，未启动服务、数据库或容器。

## 1. 核查基线与候选依赖

- Android 最低版本保持 Android 10（API 29），因为 `AudioPlaybackCapture` 从 API 29 提供。
- 后续工程暂定 `compileSdk/targetSdk 36`，最终版本组合在阶段 1 创建工程前再次按 Android 官方兼容表固定。
- WebRTC 候选为 GetStream 维护并发布到 Maven Central 的预编译原生 WebRTC Android 包。阶段 0 查到两个仍有发布记录的坐标：
  - `io.getstream:stream-webrtc-android:1.3.10`，仓库为 Apache-2.0，暴露 `org.webrtc` API。
  - `io.getstream:stream-video-webrtc-android:145.8.0`，发行说明标明基于其上游 WebRTC 145.8.0 构建。
- 阶段 1–4 使用标准预编译 AAR，视频和控制不得依赖音频补丁。进入阶段 5 前再确认 WebRTC 修订版和构建环境。
- 音频补丁 AAR 只允许使用不产生托管费用的方式：仓库内 Git LFS 以外的普通 release 附件若平台免费额度明确可用，或直接放在本地/仓库的 `android/libs/` 并记录 SHA-256；首选可复现源码补丁加本地 AAR。不得购买制品仓库服务。

参考：

- [Android：捕获视频和音频播放](https://developer.android.com/media/platform/av-capture)
- [Android：MediaProjection](https://developer.android.com/media/grow/media-projection)
- [WebRTC 上游 JavaAudioDeviceModule](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/audio/JavaAudioDeviceModule.java)
- [WebRTC 上游 WebRtcAudioRecord](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/audio/WebRtcAudioRecord.java)
- [GetStream webrtc-android](https://github.com/GetStream/webrtc-android)
- [GetStream stream-video-android-webrtc 发行版](https://github.com/GetStream/stream-video-android-webrtc/releases)

## 2. AudioPlaybackCapture 接入 WebRTC 音频轨道

### 官方约束

Android 官方要求捕获其他应用的播放音频时：

1. 应用具有 `RECORD_AUDIO`；
2. 用户批准 `MediaProjection`；
3. 捕获方和被捕获方处于同一用户配置文件；
4. 使用 `AudioPlaybackCaptureConfiguration.Builder(mediaProjection)` 配置允许的 usage；
5. 通过 `AudioRecord.Builder.setAudioPlaybackCaptureConfig(...)` 创建 `AudioRecord`；
6. 只能捕获目标应用允许捕获且 usage 为 `USAGE_MEDIA`、`USAGE_GAME` 或 `USAGE_UNKNOWN` 的播放内容。

这不保证系统声音、通话、DRM、工作资料或明确禁止捕获的应用音频可用。

### 所选 API 的能力边界

WebRTC 上游 `JavaAudioDeviceModule.Builder` 只允许设置普通 `audioSource`、采样率、声道、音频格式等。它内部实例化 `WebRtcAudioRecord`；当前上游 `WebRtcAudioRecord.createAudioRecordOnMOrHigher()` 调用 `AudioRecord.Builder.setAudioSource(...)`，源码中没有 `setAudioPlaybackCaptureConfig(...)`，也没有公开的 `AudioRecord` 工厂或外部 PCM 注入接口。`SamplesReadyCallback` 标明仅供调试，它是输出观察回调，不是输入入口。

结论：**未修改的预编译 AAR 不能把 AudioPlaybackCapture 可靠接入 WebRTC 音频轨道。该项按当前候选库核查不通过。**

### 可实施备选

推荐方案 A：以明确的 GetStream/upstream WebRTC 修订版维护最小源码补丁并自行构建 AAR。

- 给 Android 音频设备模块增加播放捕获配置/工厂入口。
- 在 WebRTC 自己的录音线程中读取该 `AudioRecord`，继续使用原生 ADM 到音频轨道的既有 10 ms PCM 通道。
- 播放捕获时关闭面向麦克风的 AEC/NS，固定并核对 PCM 格式、采样率和声道转换。
- 将上游修订号、补丁、构建脚本、许可证和生成物校验值纳入仓库，避免依赖不可复现的本地 AAR。

代价：WebRTC 源码构建体量大，后续升级需要重放和审查补丁；实际厂商兼容性仍必须双机验证。

备选方案 B：首版只交付视频和控制，内部音频延期。此方案不满足当前 P1 完整要求，只能在用户明确同意调整范围后采用。

不采用的做法：把 PCM 放入 DataChannel、自建 Opus/RTP 旁路或切换到麦克风。这些方案分别破坏拥塞/音画同步、显著扩大实现范围，或直接违背“不采集麦克风”。

### 后台采集是否需要 microphone 前台服务类型

Android 的 AudioPlaybackCapture 文档要求 `RECORD_AUDIO`、用户批准的 `MediaProjection` 和带播放捕获配置的 `AudioRecord`。前台服务文档把 `mediaProjection` 类型限定为 MediaProjection 捕获，同时把 `microphone` 类型描述为“使用麦克风设备或录制音频”；API 参考进一步说明 target API 34+ 启动 `microphone` 类型需要 `FOREGROUND_SERVICE_MICROPHONE`，并持有 `RECORD_AUDIO` 或系统级 `CAPTURE_AUDIO_OUTPUT`。

官方文档没有单独的 `audioPlaybackCapture` 前台服务类型，也没有明确写出“播放捕获可免除 microphone 类型”。由于实现确实在后台运行 `AudioRecord` 且依赖 `RECORD_AUDIO`，按安全与正确性优先，阶段 5 的共享服务计划声明并启动 `mediaProjection|microphone` 两种类型，同时声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 和 `FOREGROUND_SERVICE_MICROPHONE`。服务必须由可见 Activity 在用户批准 MediaProjection、授予 `RECORD_AUDIO` 后启动；拒绝音频权限时只启动 `mediaProjection`，视频与控制继续可用。该结论仍需最终 targetSdk 的平台行为和双机实测确认。

这里的 `microphone` 是 Android 前台服务分类，不代表应用读取物理麦克风。实现只创建带 `setAudioPlaybackCaptureConfig(...)` 的 `AudioRecord`，界面和权限说明明确写“内部播放声音”，不得回退到 `MediaRecorder.AudioSource.MIC`。

参考：

- [Android：前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android ServiceInfo 前台服务类型 API](https://developer.android.com/reference/android/content/pm/ServiceInfo#FOREGROUND_SERVICE_TYPE_MICROPHONE)

### 已否决：混入麦克风缓冲

不采用“先让标准 WebRTC ADM 采集麦克风，再把 AudioPlaybackCapture PCM 混入或覆盖麦克风缓冲”的方案，原因如下：

1. 它仍会实际打开物理麦克风，违反“不采集麦克风”和最小权限/最小数据原则。
2. WebRTC 公共 Android API 没有受支持的发送前 PCM 替换入口；`SamplesReadyCallback` 是调试观察回调，不能安全回灌。实现仍然需要修改 ADM，不能规避补丁。
3. 混音会引入采样率、声道、时钟漂移、AEC/NS/AGC 误处理和额外缓冲，增加延迟并破坏音画同步。
4. 物理麦克风内容存在误传风险；即使把麦克风增益设为零，也不能证明敏感音频从未进入进程或编码链路。
5. 静音时难以分别证明内部播放捕获、麦克风读取和 RTP 包都已停止，不符合独立静音的确认语义。

## 3. 独立静音与真实停传

上游 `RtpSender.setTrack(null, false)` 明确等价于 Web 标准的 `replaceTrack`，源码注释说明无需额外 SDP 协商；之后可用同种类音频轨道再次 `setTrack(track, false)` 恢复。与此配合：

1. 控制端立即停止播放并清空音频播放缓存；
2. 通过可靠有序控制消息请求被控端静音；
3. 被控端先从音频 `RtpSender` 分离轨道，再停止并释放 AudioPlaybackCapture 的 `AudioRecord`；
4. 被控端返回包含会话 ID 和音频状态版本的确认；
5. 控制端收到确认后才显示“音频已停传”。

`AudioTrack.setEnabled(false)`/`MediaStreamTrack.setEnabled(false)`不作为达标方案，因为禁用轨道通常产生静音媒体，不能证明 RTP 音频包停止。修改 transceiver direction 可停发，但需要 SDP 协商，作为 `setTrack(null)` 失败时的降级路径。

统计验证：通过 `PeerConnection.getStats` 定位 `kind=audio` 的 `outbound-rtp`，在确认后的观察窗口比较 `packetsSent` 和 `bytesSent`；两者应停止增长。连接维护、RTCP/DTLS/ICE 流量仍可能存在，因此不以总网络字节为零作为判据。恢复时重新创建/启动播放捕获并把同种类轨道放回 sender；是否在目标构建和目标手机上始终无需协商，列为双机验证项。

参考：

- [WebRTC RtpSender.java](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/RtpSender.java)
- [WebRTC PeerConnection.getStats](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java)
- [W3C WebRTC Statistics](https://www.w3.org/TR/webrtc-stats/)

结论：**发送端分离轨道并停止采集在 API 和源码层面可行，不要求常规重新协商；仍须用最终选定构建和两台真机验证。**

## 4. 选中候选对与路径判断

Android `PeerConnection.getStats(RTCStatsCollectorCallback)` 返回 `RTCStatsReport`，每项包含 `type`、`id` 和成员表。按统计规范：

1. 找 `transport` 的 `selectedCandidatePairId`；兼容实现可回退到 `candidate-pair` 中 `selected=true` 或 `state=succeeded && nominated=true` 的条目，但须记录这是兼容回退。
2. 由候选对的 `localCandidateId`、`remoteCandidateId` 关联 `local-candidate` 和 `remote-candidate`。
3. 读取 `candidateType`、`protocol`、`address`/`ip`、`port`、`networkType`、`relayProtocol`。
4. 任一侧选中候选类型为 `relay` 时显示 TURN Relay；非 relay 才可显示 P2P。
5. 只有结合选中的 host 候选地址、双方地址范围和网络信息确认同一局域网时才显示 LAN，不能见到 host 就标为 LAN。无法确认时显示“P2P（局域网未确认）”。

结论：**可行。** 具体字段在不同 WebRTC 修订版的可用性需要以最终 AAR 的实际 `RTCStats` 输出做兼容解析和双机验证。

## 5. 待机在线前台服务类型

待机服务的工作是用户明确启用后长期维持到信令服务的 WebSocket，等待控制请求。官方类型中：

- `remoteMessaging` 的定义是设备间转移文本消息、支持消息任务连续性，不符合远控信令语义。
- `dataSync` 覆盖设备与云之间的数据传输，但 target API 35+ 在 Android 15 上有每 24 小时合计 6 小时限制，不适合长期待机。
- `connectedDevice` 明确覆盖通过网络与外部设备交互，需要声明 `FOREGROUND_SERVICE_CONNECTED_DEVICE`，并满足诸如 `CHANGE_NETWORK_STATE` 的运行前提。
- `specialUse` 用于其他类型不能覆盖的有效前台用例，需要在 manifest 通过 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途，商店发布时会被审核。

阶段 1 采用独立待机服务，类型暂定 `connectedDevice`，声明 `FOREGROUND_SERVICE_CONNECTED_DEVICE` 与 `CHANGE_NETWORK_STATE`，用途说明为用户主动启用、带常驻通知和停止入口的跨设备网络连接。若应用商店审核认为此语义不匹配，再评估 `specialUse`；不使用受 6 小时限制的 `dataSync`，也不把远控信令伪装成 `remoteMessaging`。

服务只能从可见 Activity 内由用户点击启用；冷启动不自动恢复。Android 12+ 后台启动前台服务受限，因此进程/服务被系统杀死后不在后台静默拉起，状态回到已停止。

结论：**技术可行，但 FGS 类型的商店政策适配存在审核风险；侧载和系统运行行为仍需真机验证。**

参考：

- [Android：前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android：后台启动前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## 6. MediaProjection 顺序和单应用共享

target API 34+ 的必需顺序：

1. 可见 Activity 发起 `createScreenCaptureIntent`；
2. 用户同意本次捕获；
3. 启动声明 `mediaProjection` 类型的前台服务，并调用带该类型的 `startForeground`；
4. 在该服务上下文中调用 `getMediaProjection`；
5. 先注册 `MediaProjection.Callback`，再且只调用一次 `createVirtualDisplay`。

manifest 必须声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`，服务声明 `foregroundServiceType="mediaProjection"`。每次新捕获会话都重新取得同意，不复用授权 Intent 或 MediaProjection。旋转/尺寸变化使用现有 `VirtualDisplay.resize()` 和 `setSurface()`，不得再次 `createVirtualDisplay()`。`onStop()` 后释放资源，恢复必须重新授权。

Android 14+ 默认让用户选择整屏或单应用。单应用共享会排除系统栏、通知和其他应用，无法满足完整远控坐标语义。阶段 3 在 API 34+ 使用 `MediaProjectionConfig.createConfigForDefaultDisplay()` 请求整屏共享，并仍检测实际捕获范围；官方说明厂商在防止滥用时可以覆盖应用的 opt-out，因此若最终得到单应用范围，应用必须禁用全屏远控并明确提示，不能冒充完整屏幕共享。

结论：**整屏授权和生命周期做法可行；不能保证所有厂商都强制提供整屏选择，必须运行时识别并双机验证。**

## 7. 阶段 0 总结与必须实机验证项

| 项目 | 结论 | 后续动作 |
| --- | --- | --- |
| AudioPlaybackCapture 接入未修改 WebRTC AAR | 不通过 | 用户确认是否维护最小 WebRTC 源码补丁 |
| 分离音频 sender + 停止采集实现真实停传 | 源码/API 可行 | 最终构建后以 audio outbound RTP 统计和双机网络行为验证 |
| 选中候选对及 LAN/P2P/TURN 路径 | 可行 | 对最终 AAR 的 stats 字段做兼容解析和双机验证 |
| 待机 FGS | 可行但有系统/商店政策风险 | 先用 `connectedDevice`，真机验证厂商保活；上架前复核政策 |
| MediaProjection 顺序与一次性授权 | 可行 | 双机验证 Android 10、14+ 及目标厂商行为 |
| 强制完整屏幕共享 | 不能绝对保证 | 请求默认整屏；运行时识别单应用范围并拒绝冒充全屏控制 |

必须由用户双机验证：内部音频可捕获范围、静音后 RTP 音频计数停止、恢复音画同步、各网络路径、厂商待机存活、锁屏停止、Android 14+ 整屏/单应用行为、旋转期间复用同一捕获会话。

## 8. 已确认的实施条件与阶段 5 前置确认

用户已接受方案 A，附带以下条件：

1. 阶段 1–4 使用标准预编译 AAR，视频和控制完全不依赖补丁。
2. 进入阶段 5 前先给出 Linux 构建方案，并再次确认 Windows 主机上的实际构建环境；WebRTC 构建必须取得用户单独授权后才可执行。
3. AAR 存放不得产生费用。

阶段 5 前计划给出的 Linux 基线：Ubuntu 22.04/24.04 x86_64 原生环境或 Windows 上的 WSL2 Ubuntu，使用 Chromium `depot_tools`、`fetch --nohooks webrtc_android`/固定源码修订、`gclient sync`、对应 Android SDK/NDK 与 Ninja/GN 构建。WebRTC 全量源码、依赖、构建目录和缓存预计需要约 40–80 GB 可用磁盘；首次拉取和构建通常约 1–4 小时，具体取决于网络、CPU、内存和修订版，低配置或网络较慢时可能更久。进入阶段 5 前会基于固定修订版重新核对命令和资源估算，不在阶段 1–4 下载源码或执行构建。

## 已构建补丁（2026-10-13）

实际采用的 GetStream 1.3.10 包含 Java 源码和预编译四 ABI 原生库，因此无需 Chromium 全量构建。补丁只增加由调用者提供 `AudioRecord` 的入口；应用提供的实现固定使用 `AudioPlaybackCaptureConfiguration`，不使用麦克风源。AAR 构建和应用构建均成功，实际资源数据见 `webrtc-patch/BUILD_LINUX.md`。后台共享时，Android 官方对正在使用 `RECORD_AUDIO` 的捕获要求 microphone 类型前台服务；实现只在用户允许且权限已授予时动态启动该类型。被否决的“混入麦克风缓冲”仍不采用，因为它扩大隐私权限、无法保证纯内部音频、会引入回声/环境声且违背不得以变通冒充内部音频的约束。

## 0.3.5 质量与亮屏核查

RTT来自候选对currentRoundTripTime，视频丢包、编解码与冻结字段遵循[W3C stats](https://www.w3.org/TR/webrtc-stats/)。字段未提供即未知，不能凭设置H.264偏好断言硬件路径，也不能把ICE RTT说成整个输入/画面延迟。新最大码率为null，表示不额外设置应用上限；仍受库默认/编码器及网络限制，无统一理论Mbps数值。

前台Activity使用KEEP_SCREEN_ON；活跃共享用有期限SCREEN_BRIGHT_WAKE_LOCK（废弃API）和CPU锁，不修改全局超时、不唤醒主动锁屏。厂商仍可限制服务/锁，不能保证永不被杀。依据[Android唤醒锁](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)。后台内部播放捕获仍只在RECORD_AUDIO许可和本机允许时加入microphone类型；未采用混入麦克风方案，前文理由保持。
