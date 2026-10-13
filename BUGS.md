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
