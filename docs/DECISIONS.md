# SimpleRC 设计决策

更新日期：2026-10-12

## 已确定

### D-001 仓库结构与交付边界

- 采用 `android/`、`server/`、`deploy/`、`docs/`。
- applicationId 默认 `com.simplerc.app`。
- 最低 Android 版本为 API 29。
- 只使用 debug 签名，不创建或提交正式密钥。
- P2 不在阶段 0–7 内实现，需用户另行同意。

理由：遵循完整需求的工程基础约定和功能分级。

### D-002 阶段 0 不创建业务工程

本阶段只写可行性、状态和决策文档，不创建空壳 Android/Go 项目。

理由：需求明确要求阶段 0 不写业务代码；避免用空壳冒充交付。

### D-003 音频静音的达标语义

静音采用“控制端立即停播并清缓存 + 发送端从 `RtpSender` 分离音频轨道 + 停止 AudioPlaybackCapture + 远端确认”的状态机。只有确认后才显示“音频已停传”。以 `outbound-rtp` 音频项的 `packetsSent`、`bytesSent` 停止增长作为媒体停传判据。

理由：仅禁用 track 不能证明 RTP 音频包停止，不符合真实停传要求。

### D-004 待机服务类型

待机在线服务暂定使用 `connectedDevice` 前台服务类型，由用户在可见 Activity 中明确启动，提供常驻通知和停止入口；进程被杀后不在后台静默恢复。

理由：它最接近通过网络与外部设备持续交互的用途；`dataSync` 在 Android 15 有 6 小时限制，`remoteMessaging` 的官方语义不符。若商店审核不接受，再评估带用途声明的 `specialUse`。

### D-005 完整屏幕共享优先

Android 14+ 请求默认显示器整屏共享。若实际捕获为单应用范围，禁用全屏坐标控制并明确提示范围限制。

理由：安全与坐标准确性优先，不能把单应用共享冒充完整屏幕控制。

### D-006 WebRTC 内部音频接入方式

用户已附条件接受：固定 GetStream/upstream WebRTC 修订版，维护一个最小 Android ADM 补丁，使 `WebRtcAudioRecord` 能使用带 `AudioPlaybackCaptureConfiguration` 的 `AudioRecord`，并保存可复现构建说明、补丁、许可证和校验值。

- 阶段 1–4 只用标准预编译 AAR，视频和控制不得依赖补丁。
- 进入阶段 5 前再次确认 Linux/WSL2 构建环境；未经用户单独授权不得执行 WebRTC 构建。
- AAR 使用不产生费用的本地/仓库存放方式，不购买制品托管服务。

### D-007 后台内部音频的前台服务类型

阶段 5 的共享服务计划使用 `mediaProjection|microphone`；拒绝 `RECORD_AUDIO` 时只使用 `mediaProjection`。`microphone` 是 Android 对后台录音工作的服务分类，实际只允许创建配置了 AudioPlaybackCapture 的 `AudioRecord`，禁止物理麦克风来源和任何自动回退。

理由：官方没有独立的播放捕获 FGS 类型，也没有明确豁免 `AudioRecord` 播放捕获使用 microphone 类型；安全与正确性优先。

### D-008 否决混入麦克风缓冲

不打开麦克风后混入或覆盖内部音频 PCM。该方法会读取不需要的敏感输入、没有受支持的公共 PCM 回灌接口、引入时钟与处理链问题，也不能证明独立静音真实停传。

## 待阶段 5 前确认

### D-P02 WebRTC 最终发行坐标/修订版和构建环境

阶段 1 锁定标准预编译 AAR 供阶段 1–4 使用。进入阶段 5 前，基于该依赖对应的上游修订重新核对 Linux 构建命令、预计耗时和磁盘，并等待用户授权构建。

## 后续必须补充

- 阶段 1 固定 AGP、Gradle、JDK、Kotlin、Compose、compileSdk/targetSdk 的兼容组合。
- 阶段 2 记录超时、心跳、限流、密码哈希参数、PAKE 取舍。
- 阶段 3–6 记录协议、编解码、坐标、重连、TURN 和资源清理决策。

## 阶段 1

### D-009 Android 工具链

固定 AGP 8.10.1、Gradle 8.11.1、JDK 17、Kotlin/Compose Compiler 插件 2.2.10、Compose BOM 2025.06.01、compileSdk/targetSdk 36、minSdk 29。

理由：AGP 官方兼容表确认 8.10 支持 API 36，并要求 Gradle 8.11.1 和 JDK 17；Kotlin 2.2.10 的兼容范围覆盖 AGP 8.10 和 Gradle 8.11.1。使用版本目录统一管理。

### D-010 阶段 1–4 的 WebRTC 依赖

使用 Maven Central 的 `io.getstream:stream-webrtc-android:1.3.10` 标准预编译 AAR。阶段 1 不调用 WebRTC API，但提前固定依赖，确保后续视频和控制不依赖音频补丁。

### D-011 阶段 1 状态不得伪装在线

待机前台服务启动后显示“待机服务运行中（信令将在阶段 2 接入）”，不显示“待机在线”。ID 和验证码显示“尚未生成/阶段 2 生成”，不制造假凭据。

理由：阶段 1 尚无服务端注册和凭据，核心状态必须真实。

### D-012 冷启动与角色状态

角色和待机启用状态不持久化。进程冷启动默认为控制端、未启用；只有用户在被控端明确点击后才启动待机服务。待机服务使用 `START_NOT_STICKY`，被杀后不自动恢复。

### D-013 无障碍最小权限

阶段 1 只声明 `canPerformGestures` 和窗口状态事件，不启用 `canRetrieveWindowContent`，不读取节点内容。后续若阶段 4 为输入焦点确需读取窗口内容，必须先更新权限说明和决策。

### D-014 单页布局

使用单 Activity、单 Compose 主页面和点击式角色切换，不使用滚动容器或左右滑页。次要权限说明放入弹窗；紧凑高度减少间距以保留主要按钮。
