# 本地 WebRTC AAR

当前应用0.3.23沿用相同AAR；使用既有编码关键帧/QP/8帧回退钩子进行静止文字精修。库未重建，原生与音频兼容性已复核，说明见docs/RECOVERY_PROBE_0.3.23.md。

历史应用0.3.22沿用相同AAR。本版在应用内统一实测网络/设备自适应，库未重建，沿用0.3.20相同库的行为回执；哈希、APK原生库与签名已复核。说明见docs/ADAPTIVE_VIDEO_0.3.22.md。

历史应用0.3.20沿用0.3.19 AAR，库未重建；哈希与原生/音频类已复核，调度/采集桩/队列检查重跑通过，见webrtc-patch/VIDEO_BUILD_0.3.20.json。本版修改位于应用的导航、交互提示和画质策略。

历史0.3.19增加完整尺寸、多帧QP精修与16像素对齐判定。AAR SHA-256 `f1e6ef5644bd703da3b3ba932781bf5c917fd0fb0cd9173e3b127aff6f501d7b`，原生四ABI和25个音频类不变，仍有七个helper。见 `webrtc-patch/VIDEO_BUILD_0.3.19.json`。

历史0.3.18新增采集deadline与EGL线程VSync调度，含七个helper；默认解码纹理不启用采集限速。AAR SHA-256 `37debb5e61692ab6b1641a1ba903dbe455d954b159609914e2151be4cd61821d`，原生四ABI和25个音频类不变。见 `webrtc-patch/VIDEO_BUILD_0.3.18.json` 和 `docs/FRAME_PACING_0.3.18.md`。

当前应用加载 `stream-webrtc-android-simplerc-1.3.10.aar`，固定 GetStream 1.3.10 / commit `5d388e533d87f857e7de4a729630200cf9766369`。

历史0.3.17增加清晰帧关键帧请求与匹配输出确认，四ABI和25个音频类与0.3.16保持一致。AAR SHA-256 `16154938bbddb6030fb4fb4b097e2b0d125e8ec59037abfdc670f2ca08bb7464`。详见 `webrtc-patch/VIDEO_BUILD_0.3.17.json`。

历史0.3.14 保留 AudioPlaybackCapture、完整采集与旋转修复；编码原始输入恢复上游三帧上限，显示只保留最新一帧但不按 150ms 年龄丢掉末帧。四 ABI 原生库、25 个音频类及 capturer 与 0.3.13 不变。源码补丁、构建命令、许可证、原生库对照与 SHA-256 位于 `webrtc-patch/`。AAR 是普通 Git 对象，无付费制品托管。

采集尺寸变更在 Android 10/11 上释放并重建显示、Android 12+ 上 resize 后使用新 Surface 包装重绑定，保留纹理尺寸先更新的顺序及 Surface 显式释放。

