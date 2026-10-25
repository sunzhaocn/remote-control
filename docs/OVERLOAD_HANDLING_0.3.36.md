# 过载处理方案（0.3.36）

## 0. 结论

正常网络和硬件下，当前链路已经能做到“清楚又顺”；剩下的问题不在尺寸参数，而在**过载时缺少可用的执行器**：

- 尺寸被锁死、30fps 被锁死，唯一还能动的只有码率；码率一低，每帧分到的数据不够，只能糊。
- 设备过载（编码慢、解码慢、显示慢、编码队列满）目前只记录、只限制画质恢复，**没有任何主动减负动作**，所以只能靠编码器丢帧，表现就是卡。
- 继续在 90% / 1080p 之间换尺寸，解决不了这两个问题。

0.3.36 的方向：

1. 先用硬编能力探测确认 1080p 尺寸能不能稳定编码，不能就退回下一档；尺寸只决定一次，运行期不再改。
2. 压力持续时先把 30fps 小幅降到 24fps，给每帧更多编码时间和码率；稳定后再慢速恢复，避免 24↔30 来回跳。
3. 把“网络排队、编码慢、解码/显示慢”分成三类，分别驱动码率、帧率、帧率，不再用一个“pressure”混着判。
4. 操作时保持尺寸稳定；停手后用现有静止精修补文字细节，并把它做成可观测、可验收的状态。

前提：先修正 0.3.35 的 90% 漏乘（见 `FIXED_1080P_ISSUE_0.3.35.md`）。否则基线是 `1080×2400`，任何过载数据都不可比。

---

## 1. 现状与证据

| 现状 | 代码位置 | 后果 |
|---|---|---|
| fps 固定 30 | `AppConfig.VIDEO_START_FPS=30`、`InteractiveVideoPolicy.targetFps`、`ScreenContentVideoPlan.fps`、`VideoSenderProfile.maxFramerate` | 没有时间域降级；过载只能丢帧或糊 |
| 设备压力只记录 | `DetailProtection.observe()` 只改 `device.scale`；0.3.31 起 `ScreenContentVideoPlan` 忽略 `resourceScale` | 编码/解码/显示慢时没有减负动作 |
| 网络与设备压力混用 | `InteractiveVideoPolicy.update()` 的 `pressure` 只含 RTT/丢包/发送排队/抖动/BWE 缺口；`device.pressureReason` 只用于阻止试升 | 设备过载不会触发码率/帧率动作；网络不足也不会区分“排队”和“编码慢” |
| 尺寸与 30fps 锁死 | 0.3.31 之后固定尺寸；0.3.35 又漏乘 90% | 过载时只能压糊，或冻结 |
| 精修不可观测 | `VideoRecoveryProbe` / `ClearFrameRefresh` / `SimpleRcFrameRefresh` 有名额和 QP，但诊断没有“请求/完成/失败/前后 QP” | 无法确认精修是否真的生效 |

已有可用信号（不需要新增 AAR）：

- 编码：`encodeMs`、`limitation=cpu`、`encoderQueueLimited`、`encoderDropped`、`framesDropped`、`QP`、`bpp`；
- 发送：`sendDelayMs`、`lossPercent`、`availableKbps`、`rttMs`；
- 接收（控制端回传）：`decodeMs`、`renderMs`、`renderQueueMs`、`renderSwapMs`、`jitterBufferMs`、`decodeFps`、`renderFps`、`freezes`；
- 节奏：`sourceFps`、`sendFps`、`decodeFps`、`renderFps`。

---

## 2. 设计

### 2.1 硬编能力探测（先确认 1080p 尺寸）

不能只看屏幕分辨率，也不能只看 SDP；要在会话开始前查询本机 H.264 硬编能力：

```text
MediaCodecList(REGULAR_CODECS)
  → codecInfo.isEncoder && supportedTypes 含 "video/avc"
  → 优先 codecInfo.isHardwareAccelerated（API 29+），否则按名称排除 software/google
  → capabilities = getCapabilitiesForType("video/avc").videoCapabilities
  → capabilities.areSizeAndRateSupported(width, height, 30)
```

候选尺寸按“1080 短边上限 + 90%”计算，从大到小探测：

| 候选 | 尺寸（20:9） | 说明 |
|---|---|---|
| 1 | 972×2160 | 首选（Level 4.2 内） |
| 2 | 950×2112 | 88% 档 |
| 3 | 864×1920 | 80% 档 |
| 4 | 648×1440 | 720p 档 |

规则：

- 选第一个被硬编支持的尺寸；全部不支持时退回 720p，并在诊断写明“硬编不支持 1080p 尺寸，已使用 …”。
- 只决定“会话起始尺寸”，运行期不因压力改尺寸；旋转时对新方向再查一次。
- 查询结果按设备缓存；失败时退回 1080p 档并记录，不阻塞会话。
- 纯逻辑 `EncoderSizePolicy.choose(screenW, screenH, maxShort, supports)` 单独测试；Android 查询放在 `MediaCodecSizeSupport` 里，单元测试用假支持矩阵。

### 2.2 fps 阶梯：30 → 24 → 30

先只做一档（30/24），20fps 留到有双机数据后再评估。

状态机：

- **下降**：连续 2 个有效样本（约 1.5 秒）存在设备压力，或 1 个 severe 样本；下降后最小驻留 3 秒。
- **恢复**：连续 8 个样本（约 6 秒）无设备压力、帧节奏健康、无网络压力；恢复后最小驻留 8 秒。
- **severe**：`encodeMs > 35ms`、编码队列持续满、`encoderDropped` 明显增长、`decodeMs > 35ms`、`renderQueueMs > 40ms`。
- 切换只改 `maxFramerate`（RTP 参数），不重配编码器、不改尺寸；`frameCadence` 和静止精修的进度判断改用当前 fps。
- 诊断显示“目标 30fps / 实际 24fps（编码压力）”。

为什么是 24：每帧时间从 33ms 增到 41.7ms，编码/解码/显示的单帧预算增加约 25%；比继续压码率更直接，也比掉到 15fps 更不破坏观感。

### 2.3 三类压力分开，各驱动自己的执行器

| 类型 | 判定信号 | 首选动作 | 次选动作 |
|---|---|---|---|
| 网络排队/带宽不足 | `sendDelay` 升高、`jitterBuffer` 升高、丢包、RTT 相对基线偏移、BWE 缺口 | 降码率上限（现有路径） | 持续时允许 fps 30→24，减少每帧比特需求 |
| 编码慢 | `encodeMs` 持续偏高、`limitation=cpu` 且发送帧率缺口、`encoderQueueLimited`、`encoderDropped` 增长、硬编回退软编 | fps 30→24 | 仍持续则小幅降码率；不优先降尺寸 |
| 解码/显示慢 | `decodeMs`、`renderMs`、`renderQueueMs`、`renderSwapMs` 偏高；`decodeFps`/`renderFps` 低于发送帧率 | fps 30→24（发送端减负） | 仍持续则降码率；不优先降尺寸 |

约束：

- 网络不足**不能**靠提高码率解决；设备处理不过来**不能**只降码率。
- 任何压力都不在交互瞬间改尺寸；尺寸只在会话起始（含旋转）变化。
- 码率与帧率不要同时大幅变化；先看主要矛盾：网络 → 码率，设备 → 帧率。
- 恢复顺序：先恢复码率（网络健康），再恢复 fps（设备余量、实际帧率稳定）；避免“30fps + 低码率”导致每帧更糊。

### 2.4 尺寸稳定 + 静止精修

- 交互期尺寸不变（0.3.31 已做到）；操作结束后由 `VideoRecoveryProbe` / `ClearFrameRefresh` / `SimpleRcFrameRefresh` 做有界补帧。
- 精修必须可观测：
  - `applicationRefreshRequests`（已有）；
  - 新增：精修请求/完成/失败次数、补帧次数、精修前后 QP、失败原因（暂停/预算/QP 不达标/超时）。
- 精修预算维持 `max(current, min(8M, 0.85×BWE−预留))` 的“只抬不降”规则；压力出现时立即取消补帧并暂停新请求。
- 验收：停手后 1–2 秒内 QP 下降、小字变清；如果 `StillRefinementGate` 因 QP>32 或估算预算 <2.5Mbps 阻塞，诊断要能说清原因。

### 2.5 诊断

在 `videoPolicyStatus` / `MediaDiagnostics` 增加：

- `targetFps` / `currentFps` / `fpsReason`（“编码压力降帧”“解码压力降帧”“恢复中”）；
- `encoderPressure` / `decoderPressure` / `networkPressure` 三个原因字符串；
- `selectedSize` 与 `sizeSupportedByEncoder`（硬编探测结果）；
- `refinementRequests` / `completed` / `failed` / `qpBefore` / `qpAfter`；
- 保持 768 字符上限，必要时拆成独立反馈字段。

---

## 3. 代码落点

| 文件 | 改动 |
|---|---|
| `webrtc/OverloadFpsPolicy.kt`（新增） | 纯逻辑：输入设备/网络压力、severe、健康样本、时间，输出 30/24；滞回与最小驻留 |
| `webrtc/InteractiveVideoPolicy.kt` | 接入 `OverloadFpsPolicy`；`targetFps` 保留为最大值，新增 `fps`；分开计算网络/编码/解码压力；状态文案 |
| `webrtc/DetailProtection.kt` | 暴露 `encoderPressure` / `decoderPressure`（或复用 `pressureReason` 分类）；`scale` 仅保留为诊断，不再影响输出 |
| `webrtc/ScreenContentVideoPlan.kt` | 沿用 0.3.35 的 1080 上限 + 0.9；`fps` 使用 `interactivePolicy.fps` |
| `session/SessionMedia.kt` | `contentVideoPlan` 传当前 fps；`frameCadence` 用当前 fps；硬编探测选尺寸；诊断字段 |
| `webrtc/VideoRecoveryProbe.kt` | `Sample` 增加 `targetFps`（默认 30），连续进度用 `min(sourceFps, targetFps)` |
| `webrtc/EncoderSizePolicy.kt` + `MediaCodecSizeSupport.kt`（新增） | 候选尺寸与 Android `MediaCodecList` 查询 |
| `model/MediaDiagnostics.kt` + `VideoPolicyStatus` | 新指标与文案，保持 768 字符 |

伪代码：

```kotlin
// OverloadFpsPolicy
fun update(nowMs: Long, devicePressure: Boolean, networkPressure: Boolean,
           severe: Boolean, cadenceHealthy: Boolean, frameInfoReady: Boolean): Boolean {
    // down: 2 samples or severe, min dwell 3s
    // up: 8 healthy samples, min dwell 8s
    // returns true if fps changed
}
```

```kotlin
// SessionMedia.startControlledCapture
val size = EncoderSizePolicy.choose(screen.screenWidth, screen.screenHeight,
    AppConfig.VIDEO_SEND_SHORT_EDGE, MediaCodecSizeSupport)
val initialPlan = contentVideoPlan(screen, size)
```

---

## 4. 测试

- `OverloadFpsPolicyTest`：连续压力降 24、severe 立即降、恢复需 8 个健康样本、最小驻留、不会 24↔30 抖动、网络压力不单独降 fps（按最终规则）。
- `EncoderSizePolicyTest`：20:9/16:9/720p 的候选顺序；只支持 864×1920 时选 80% 档；全不支持退回 720p；横屏交换。
- `InteractiveVideoPolicyTest`：网络压力只降码率；编码压力降 fps；解码压力降 fps；两类同时出现时优先设备；恢复顺序。
- `VideoRecoveryProbeTest`：`targetFps=24` 时验证进度用 24 而不是 30。
- `ScreenContentVideoPlanTest` / `Fixed1080VideoPipelineTest`：更新为 972×2160（0.3.35 的 90% 修复）。
- 真机前先保证 `:app:testDebugUnitTest`、`testCompare80UnitTest`、`testCompare88UnitTest` 全绿。

---

## 5. 双机验收

固定同一对手机、同一页面、同一网络，先找“最慢的一段”，再决定阈值。

### 5.1 场景

1. **静止小字 60 秒**：小字/表格可读性（相机拍屏）、QP/bpp、精修完成/失败、冻结。
2. **快速滑动 30 秒**：`source/send/decode/render fps`、`freezes`、`sendDelay`、操作回执、相机测输入→画面延迟 P50/P95。
3. **连续视频 10 分钟**：fps 稳定性（是否 24↔30 抖动）、`encodeMs P50/P95`、`encoderDropped`、`freezes`、发热。
4. **弱网**：2Mbps 限速 / 5% 丢包 / 80ms 抖动；分别走直连、UDP 中继、TCP 中继。

### 5.2 最慢一段的判定

| 现象 | 判定 | 该改什么 |
|---|---|---|
| `sendDelay`/`jitter` 高、BWE 低 | 网络排队 | 码率上限、路径/中继 |
| `encodeMs` 高、队列满、`encoderDropped` 增长 | 编码慢 | fps 先降；查硬编/尺寸 |
| `编码=software` 或 `codecOptionsFallback` | 硬编回退 | 尺寸探测、Level、编码器选择 |
| `decodeMs` 高、`decodeFps < sendFps` | 解码慢 | fps 降；控制端性能 |
| `renderQueueMs`/`renderSwapMs` 高、`renderFps < decodeFps` | 显示慢 | fps 降；渲染队列 |
| 视频正常、`inputAckMs` 高 | 注入延迟 | 无障碍/触控链路，不是视频 |

### 5.3 通过标准

- 24fps 能稳定保持，10 分钟内 24↔30 切换 <2 次；
- `encodeMs P95` 比 30fps 基线下降；
- `freezes` <1 次/10 分钟；
- 小字可读性不低于 0.3.33 同场景（相机对比）；
- 操作回执不因降帧变差；
- 全过程无交互期尺寸变化；
- 硬编探测结果与诊断一致（`编码` hardware、尺寸被支持）。

如果 24fps 仍不足，再评估 20fps；不要回到“持续压码率”或“反复改尺寸”。

---

## 6. 排期与回滚

- **0.3.36**：0.3.35 的 90% 修正（前置）＋硬编尺寸探测＋30/24fps 阶梯＋三类压力分离＋精修诊断；不改 AAR/原生/协议/后端。
- **0.3.37**：用双机数据调阈值（24 vs 20、编码/解码阈值、恢复时间）、精修策略；必要时再评估 60fps 或区域 TURN。
- 回滚：保留 0.3.33/0.3.35 APK；可加 `AppConfig.OVERLOAD_FPS_ENABLED` 开关，关闭后回到 30fps 固定行为。

---

## 7. 边界

- 本文是方案，不是实测结论；未跑双机、未做弱网注入前，不宣称卡顿或延迟已有量化改善。
- 固定 1080p、30fps、低延迟、清晰四项在弱网/弱机上无法同时硬保；本文选择“受控小幅降帧”，而不是持续压糊或反复冻结。
- 不改 AAR、原生库、协议、后端；尺寸探测、fps 阶梯、诊断都在应用层。
- 尺寸仍是会话起始固定值；运行期只允许 fps 和码率变化。
