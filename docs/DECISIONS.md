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

## 阶段 2

### D-015 设备身份与秘密存储

设备身份使用 Android Keystore 中不可导出的 P-256 ECDSA 私钥和 `SHA256withECDSA` 签名。需要本机显示的验证码用另一把 Keystore AES-GCM 密钥加密，备份规则排除全部应用数据。

### D-016 服务端验证码哈希

使用 Argon2id：64 MiB、3 次迭代、2 lanes、32 字节输出、16 字节随机盐，再拼接环境变量 pepper。参数后续可按部署机器基准提高，不能降低到明文或快速哈希。

### D-017 PAKE 取舍

首版不实现 SPAKE2/OPAQUE。原因是 Android/Go 间成熟、审计充分且维护稳定的互操作实现选择有限，错误实现会降低安全性。首版依赖 WSS、设备认证、Argon2id+pepper、多维限流、统一失败和本机确认。代价是服务端验证路径可接触用户提交的验证码；上线前仍应将 PAKE 作为安全升级重新评估。

### D-018 租约归属

Redis 使用 `SET NX PX` 原子占用，以目标 ID 为键、随机会话 ID 为值；续约和释放均用 Lua 比较会话归属后操作，防止旧会话释放新租约。TTL 30 秒，10 秒续约。

## 阶段 3

### D-019 MediaProjection 所有权

共享前台服务拥有本次授权和停止生命周期；授权 Intent 只消费一次，`onStop` 清理并要求下次重新授权。旋转不得重新消费授权。

### D-020 控制指令验证

每条命令绑定会话、递增序号、2 秒时效和几何版本；点坐标必须有限且在 `[0,1]`，路径最多 256 点。发送成功不等于执行成功，必须等待无障碍回调。

## 阶段 4

### D-021 坐标与越界

控制端只基于实际视频内容矩形归一化；远端只基于实际捕获矩形还原。编码像素不参与坐标。手势起点或任何后续点越界时取消整个手势，不做 clamp。

### D-022 输入法范围

优先真实触控远端输入框和远端键盘。当前不声明读取窗口内容，因此不做节点 ACTION_FOCUS；也不弹控制端键盘冒充远端输入。

## 阶段 5

### D-023 音频构建隔离

应用层只依赖 `InternalAudioCapture`。标准 AAR 绑定 `StandardAarAudioCapture` 并返回“内部音频不可用（未接入补丁构建）”。Manifest 在补丁可构建并重新核对 FGS 要求前不声明音频权限。

### D-024 静音状态

静音顺序是本地停播/清缓存、`RtpSender.setTrack(null,false)`、停止采集、可靠发送停止请求、等待远端确认。未确认只显示待确认；重连保持待确认/已静音意图，禁止短暂恢复声音。

## 阶段 6

### D-025 分级恢复

临时网络先等待；信令单独恢复；ICE 先两次 restart 再重建；媒体停滞进入重建。120 秒后终止。用户停止或 projection 停止从不自动恢复。

### D-026 TURN 与路径

只使用自建 Coturn。relay 按实际协议显示；host/srflx/prflx 只能说明 P2P，只有额外确认同一 LAN 才显示 LAN。TURN 密钥只在服务端环境中，客户端获取短期凭据。

## 阶段 7

### D-027 未构建交付标记

由于 JDK、Android SDK、Go 和 Docker 均不可用，任何源码模块都不得标“已编译”。没有 APK、服务二进制或经过解析的 Compose 配置。最终审查以需求对照表和明确缺口为准。

## 构建与部署修复

### D-028 中文路径构建

保留仓库现有中文路径，并启用 `android.overridePathCheck=true`。Windows 桌面运行环境的 Java 临时目录需显式设为短的本地路径，以避免 NIO 本地套接字初始化失败；这只影响构建环境。

### D-029 服务器部署边界

服务器没有可用于 SimpleRC 的域名和 TLS 证书，因此只部署 PostgreSQL、Redis 和后端健康检查基础，后端绑定 `127.0.0.1:18080`。不公开 HTTP 配对服务，也不启用 Coturn；取得域名、证书并补齐业务路由后才能配置 HTTPS/WSS 与 TURN/TLS。

### D-030 后端容器制品

服务器访问 Go 模块代理超时，部署时在已完成 `go build`/`go vet` 的本机交叉编译静态 Linux amd64 二进制，再由 `scratch` 镜像封装。二进制目录不纳入 Git；源码依赖由 `go.mod` 和 `go.sum` 固定。

### D-031 公网入口与 TURN 端口

9443 提供 `/simplerc/` HTTPS/WSS 与 `/simplerc-download/` APK 下载。Coturn 使用 3480 UDP/TCP、5350 TLS 和 49261–49360 relay 范围。

### D-032 选中路径显示

使用 `onSelectedCandidatePairChanged` 判断 relay 与协议；其他候选显示“P2P（局域网未确认）”，不把 host 候选直接声称为 LAN。

### D-033 WebRTC 构建环境阻塞

用户已授权源码构建。本机已安装 WSL 并启用所需 Windows 功能，但系统要求重启后才能初始化 Ubuntu。音频保持未实现，Manifest 不声明音频权限。

## D-030 可复现补丁 AAR 与内部音频接入（2026-10-13）

固定 `GetStream/webrtc-android` 1.3.10 提交 `5d388e533d87f857e7de4a729630200cf9766369`。其发布仓库包含 Java 源码与四 ABI 原生库，采用最小 `AudioRecordFactory` 补丁即可在约 4 GiB 实际工作集内构建，无需 Chromium 全量源码。AAR 直接纳入 Git 普通对象，不用 Git LFS 或付费托管。

音频捕获复用 `ScreenCapturerAndroid.getMediaProjection()` 返回的同一投影实例，避免重复消费授权 Intent。`RECORD_AUDIO` 拒绝时只关闭音频；视频和控制继续。音频启用时共享服务动态包含 `microphone` FGS 类型；未启用时只声明运行中的 `mediaProjection` 类型。静音先在控制端停播放，再由被控端脱离 sender 轨道并释放采集，最后回 ACK。
