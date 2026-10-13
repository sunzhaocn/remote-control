# 已知问题与阻塞

## BLOCK-001 WebRTC 预编译 AAR 无内部播放音频注入入口

- 状态：方案已确定，等待阶段 5 实施
- 发现阶段：阶段 0
- 已确认事实：Android 要求用 `AudioRecord.Builder.setAudioPlaybackCaptureConfig(...)`；上游 WebRTC Android 的 `JavaAudioDeviceModule`/`WebRtcAudioRecord` 没有公开配置或外部 PCM 注入入口。
- 影响：未经修改的候选 AAR 无法实现需求中的内部音频轨道，不能将其标记为已实现。
- 处理决定：阶段 1–4 使用标准预编译 AAR；阶段 5 固定上游修订版并维护最小 ADM 补丁。WebRTC 构建须取得用户单独授权，AAR 免费存放。

## ENV-001 当前环境缺少 Android 构建工具

- 状态：阻塞构建验证
- 发现阶段：阶段 1
- 已确认事实：当前 Windows 环境找不到 `java`、`JAVA_HOME`、Android SDK、Android Studio 或全局 Gradle。
- 影响：`gradlew.bat :app:assembleDebug` 在 Wrapper 启动前终止，尚未产生 APK，也无法确认源码编译结果。
- 处理方式：安装 JDK 17、Android SDK Platform 36 和兼容 Build Tools，或提供现有安装路径后重新执行明确的 `:app:assembleDebug`。不需要连接手机。
