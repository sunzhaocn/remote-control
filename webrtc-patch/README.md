# SimpleRC WebRTC 内部音频与低延迟视频补丁

当前应用0.3.23沿用相同AAR；使用既有编码关键帧/QP/8帧回退钩子进行静止文字精修。库未重建，原生与音频兼容性已复核，说明见docs/RECOVERY_PROBE_0.3.23.md。

历史应用0.3.22沿用相同AAR。本版在应用内统一实测网络/设备自适应，库未重建，沿用0.3.20相同库的行为回执；哈希、APK原生库与签名已复核。说明见docs/ADAPTIVE_VIDEO_0.3.22.md。

历史应用0.3.20沿用0.3.19 AAR，库未重建；哈希与原生/音频类已复核，调度/采集桩/队列检查重跑通过，见webrtc-patch/VIDEO_BUILD_0.3.20.json。本版修改位于应用的导航、交互提示和画质策略。

历史0.3.19：SimpleRcFrameRefresh支持16像素编码对齐，匹配的新关键帧之后还需多帧/低QP精修才确认；HardwareVideoEncoder将实际QP传入。七个helper和既有采集/VSync补丁保留，原生四ABI及25个音频类不变。AAR SHA-256 `f1e6ef5644bd703da3b3ba932781bf5c917fd0fb0cd9173e3b127aff6f501d7b`，三补丁重放一致，回执 `VIDEO_BUILD_0.3.19.json`，说明 `../docs/TEXT_DETAIL_0.3.19.md`。

历史0.3.18 AAR增加采集时隙与EGL线程VSync调度，新增SimpleRcFramePacer、SimpleRcCaptureScheduler、SimpleRcVsyncScheduler；共七个helper均须复制。低延迟视频补丁同时覆盖SurfaceTextureHelper，只有投屏helper显式启用限速，解码纹理默认不变。Surface重建重设唤醒，完整释放取消回调，计划VSync等待单独计量。AAR SHA-256 `37debb5e61692ab6b1641a1ba903dbe455d954b159609914e2151be4cd61821d`；三补丁重放一致，原生/25个音频类不变。16项实际AAR调度器行为桩、10项采集和8项队列检查通过；无手机/GPU实测。见 `../docs/FRAME_PACING_0.3.18.md` 与 `VIDEO_BUILD_0.3.18.json`。

历史0.3.17 AAR增加 `SimpleRcFrameRefresh`：应用发出匹配尺寸/新时间戳的清晰帧请求，硬编在编码线程请求一次关键帧，匹配的新关键帧输出才确认完成。保留0.3.14的投屏与队列修复，四ABI及25个音频类与0.3.16不变。新AAR SHA-256 `16154938bbddb6030fb4fb4b097e2b0d125e8ec59037abfdc670f2ca08bb7464`，回执 `VIDEO_BUILD_0.3.17.json`。四个helper源文件均须随补丁复制；应用层负责有界补帧和合成帧排除，详见 `../docs/CONTENT_VIDEO_0.3.17.md`。

历史 0.3.14 AAR 把编码器原始输入上限恢复到上游实际允许的 3 帧。之前从 2 帧起步、等待持续堵塞再扩到 3 帧，可能在有正常流水线延迟的硬编上一直丢帧：零星输出会不断清空堵塞计时。新上限始终为 3，长期堵塞也不会继续扩大。渲染仍仅保留最新待显示帧，但不再因为等待超过 150ms 就丢弃最后一次屏幕更新，避免滑动停止后画面停在旧状态。四 ABI 原生库以及全部 25 个音频 Java 类与 0.3.13 AAR 逐字节一致，见 `VIDEO_BUILD_0.3.14.json` 和 `SHA256SUMS`。

0.3.13 的投屏裁切修复保持不变：Android 12+ 先更新纹理尺寸、resize，然后创建新的 Surface 包装重新绑定；成功后释放旧包装，绑定失败则释放新包装并保留旧引用供关闭清理。之前把同一个 Surface 对象交回 `VirtualDisplay.setSurface()`，会被 Android 的对象身份检查直接忽略。

旋转和采集尺寸调整遵循当前官方 WebRTC 的系统版本分支：先更新纹理尺寸；Android 10/11 释放并重建 VirtualDisplay，避免旧系统 resize 的缩放错误；Android 12+ 保留已有 VirtualDisplay 并 resize/setSurface，也满足 Android 14+ 同一投屏授权只允许创建一次显示的约束。补丁明确释放旧 Surface，避免反复旋转留下 Surface。参考：[官方 ScreenCapturerAndroid](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/ScreenCapturerAndroid.java)、[Android VirtualDisplay 的 Surface 身份判断](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/display/VirtualDisplay.java)。应用层另外把网络自适应的输出缩放与投屏尺寸分离，降低频繁触发系统尺寸变化的风险。

补丁目标是给 upstream WebRTC Android `JavaAudioDeviceModule` 增加受控的 `AudioRecord` 创建接口，使其能使用 `AudioRecord.Builder.setAudioPlaybackCaptureConfig(...)`。补丁模式下禁止 `MediaRecorder.AudioSource.MIC` 回退，并关闭面向麦克风的 AEC/NS。

可复现构建见 `BUILD_LINUX.md`。固定上游先应用 `patches/0001-audioplaybackcapture-factory.patch`，再运行 `scripts/apply-realtime-patch.py <upstream-root>` 和 `scripts/apply-capture-resize-patch.py <upstream-root>`；脚本验证修订、应用视频改动并复制 `library/org/webrtc/*.java`。`patches/0002-low-latency-video.patch`、`patches/0003-rotation-safe-capture.patch` 是对应可审查 diff，使用 diff 路线时还须复制七个 helper 源文件；不可同时应用同一补丁的 diff 和脚本。

`scripts/verify-realtime-patch.py <upstream-root>` 在独立干净检出上重放全部三份补丁并与用于构建的 Java 源码逐字节核对；`scripts/verify-video-build.py <previous-aar> <built-aar> <receipt-json>` 核对四 ABI 原生库、音频 Java 字节码、必要补丁类及编码器已不设置静默丢输入参数。`scripts/test-capture-surface.py <upstream-root> [receipt-json]` 编译实际 capturer 源码并使用保留 Android 对象身份行为的测试桩，覆盖 API 29/30/31/32/34/35 的重建、重绑定、释放和异常清理共 10 项；注回旧版同对象调用后测试应当失败。没有真实构建产物不得填写校验值。行为桩不模拟真实图形驱动，这些核对不替代手机上的编码、显示及端到端延迟测试。

`scripts/test-video-queues.py <previous-aar> <built-aar> <receipt-json>` 直接加载构建 AAR 的 Java helper，覆盖 30fps/90ms 和 60fps/45ms 流水线延迟、持续堵塞上限、重置、末帧保留和大量帧合并共 8 项；同一测试加载旧 AAR 时必须检出吞吐下降。另核对实际 EglRenderer 字节码不再调用旧年龄丢弃逻辑。此项验证队列行为，不测量真实硬编/GPU 帧率。

许可证：补丁代码采用与上游兼容的 BSD-3-Clause；WebRTC 自身还受其仓库 `LICENSE`、`PATENTS`、`AUTHORS` 约束。GetStream 包装仓库为 Apache-2.0。正式分发前必须随 AAR 保留双方许可证材料。
