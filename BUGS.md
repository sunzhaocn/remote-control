# 已知问题与阻塞

## BLOCK-001 WebRTC 预编译 AAR 无内部播放音频注入入口

- 状态：方案已确定，等待阶段 5 实施
- 发现阶段：阶段 0
- 已确认事实：Android 要求用 `AudioRecord.Builder.setAudioPlaybackCaptureConfig(...)`；上游 WebRTC Android 的 `JavaAudioDeviceModule`/`WebRtcAudioRecord` 没有公开配置或外部 PCM 注入入口。
- 影响：未经修改的候选 AAR 无法实现需求中的内部音频轨道，不能将其标记为已实现。
- 处理决定：阶段 1–4 使用标准预编译 AAR；阶段 5 固定上游修订版并维护最小 ADM 补丁。WebRTC 构建须取得用户单独授权，AAR 免费存放。

## ENV-001 当前环境缺少 Android 构建工具（已解决）

- 状态：已解决
- 发现阶段：阶段 1
- 已确认事实：当前 Windows 环境找不到 `java`、`JAVA_HOME`、Android SDK、Android Studio 或全局 Gradle。
- 影响：`gradlew.bat :app:assembleDebug` 在 Wrapper 启动前终止，尚未产生 APK，也无法确认源码编译结果。
- 处理结果：已安装 JDK 17、Android SDK Platform 36 和兼容 Build Tools，`:app:assembleDebug` 成功并生成 debug APK。未连接手机。

## GAP-002 端到端远控链路未完成（代码接线已解决）

- 状态：已完成代码接线并编译，待双机验证。
- 结果：设备注册、配对、确认、SDP/ICE、视频渲染、DataChannel 与无障碍控制已接通；仍缺远端手势执行成功回执。

## GAP-003 WebRTC 音频补丁未形成可应用构建

- 状态：用户已授权，但本机需重启后完成 WSL2 Ubuntu 初始化
- 影响：内部音频运行时不可用。
- 事实：补丁目录目前包含变更规范，不是绑定具体上游 SHA 的最终行级 diff；没有 AAR 和 SHA-256。

## GAP-004 后端与部署配置未验证（已解决）

- 状态：已解决构建与公网部署。
- 结果：Go/Compose 校验成功；HTTPS/WSS、数据库、Redis、Coturn、APK 下载和证书续期均已部署。功能仍待双机验证。

## GAP-003 WebRTC 音频补丁未形成可应用构建（已解决）

- 状态：已解决并编译，待双机验证。
- 结果：固定上游提交的行级补丁已通过应用检查，四 ABI AAR 已构建并以 SHA-256 锁定；Android 0.3.0 已使用该 AAR 编译。
- 剩余验证：不同设备的 AudioPlaybackCapture、音画同步、静音后 outbound-rtp 停止增长及恢复发送需要用户人工双机验收。

## FIX-001 自定义凭据、重连限流与输入校验（2026-10-13）

### 已确认原因

1. `registerFixed` 未取消 `credentialRefresh`；计时器到期仍调用随机注册。信令重连成功后也无条件调用 `registerRandomCredentials`，导致自定义模式被随机凭据覆盖。
2. 服务端在设备注册、WebSocket 建连、随机/固定凭据发布之前统一调用计数器，成功请求也累计；凭据发布阈值为十分钟 10 次。客户端重连又重复发布随机凭据，因此正常重连会触发 `RATE_LIMITED`。
3. 客户端收到 `RATE_LIMITED` 只显示固定文本，不读取等待时间；注册响应、WebSocket 建连以外的凭据发布没有响应超时；错误状态没有独立重试操作。
4. 客户端输入框没有字符集、最大长度或完整实时校验；服务端 ID 规则是 5–32，与产品要求不一致，验证码也没有 32 位上限或空白校验。

### 尚属推测

- 服务端旧版本没有记录信令消息类型，无法从运行日志证明该次手机先命中了 `ws` 还是 `pair` 限流。客户端出现的中文文案来自信令 ERROR，结合 10 次阈值，最可能命中 `REGISTER_RANDOM/REGISTER_FIXED` 的 `pair` 限流。
- 代码没有从第 10 位截断输入。数字“消失”更可能是密码掩码或厂商 Compose 单行输入渲染/可视区域问题；新版加入明文显示切换、标准单行横向编辑和完整校验提示，仍需原设备复测。

### 修复

- 自定义模式保存和恢复时取消随机计时器，随机刷新入口再次检查持久化模式；重连按当前模式发布。
- 服务端切换随机模式时删除该设备固定 ID；保存固定模式时原子替换本设备旧固定 ID，并清理旧随机映射。
- 成功注册、心跳、WebSocket 和凭据发布不计入限流；只有 CONNECT 验证失败按 IP、设备、目标累计。超限 ERROR 带 `retryAfterSeconds`。
- 客户端显示服务端倒计时并禁止提前重试；重连为 1–15 秒有限指数退避加 0–749ms 抖动，120 秒截止；建连和凭据发布分别有 20 秒超时；错误状态提供“重试”和“停止”。
- 客户端与服务端统一 ID 6–16 位、`[A-Za-z0-9_-]`、比较/保存转小写；自定义验证码 12–32 位且不含空白。输入不截断，错误实时显示并禁用保存。
