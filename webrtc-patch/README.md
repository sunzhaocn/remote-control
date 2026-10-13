# SimpleRC WebRTC AudioPlaybackCapture 补丁

此目录只保存补丁源码和可复现构建资料。阶段 5 未下载源码、未执行 WebRTC 构建、未生成 AAR。

补丁目标是给 upstream WebRTC Android `JavaAudioDeviceModule` 增加受控的 `AudioRecord` 创建接口，使其能使用 `AudioRecord.Builder.setAudioPlaybackCaptureConfig(...)`。补丁模式下禁止 `MediaRecorder.AudioSource.MIC` 回退，并关闭面向麦克风的 AEC/NS。

构建前必须由用户单独授权。生成后执行 `sha256sum stream-webrtc-android-simplerc.aar > SHA256SUMS`；没有真实 AAR 时不得填写虚构校验值。

许可证：补丁代码采用与上游兼容的 BSD-3-Clause；WebRTC 自身还受其仓库 `LICENSE`、`PATENTS`、`AUTHORS` 约束。GetStream 包装仓库为 Apache-2.0。正式分发前必须随 AAR 保留双方许可证材料。
