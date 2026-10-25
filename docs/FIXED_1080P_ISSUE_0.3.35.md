# 固定 1080p 改动的回归分析与改进方案（0.3.35）

## 1. 结论

0.3.35 把视频输出改成“短边不超过 1080、保留全屏比例”，方向是对的；但正常策略在实现时把 0.3.30 以来的 **90% 缩放丢掉了**，变成了“精确 1080 短边输出”。直接后果：

- 1080p 手机从 `972×2160`（2.10MP）变成 `1080×2400`（2.59MP），**像素 +23.5%**；
- 720p 手机从 `648×1440` 变成 `720×1600`，同样 **+23.5%**；
- 1.5K/2K 手机确实被压到了 `1080×2400`（比 0.3.33 小 9%–31%）。

所以如果“更卡”的方向里被控端是 1080p 手机，这次改动恰好把它的编码负载和码率需求都抬高了一档。除此之外，`1080×2400` 还超出 H.264 Level 4.2 的单帧宏块上限（`972×2160` 刚好在限内），在只支持 Level 4.2 的老编码器上可能触发配置失败或软编回退，把“+23%”放大成数量级的卡顿。

修复很小：正常分支补回 `1.0 / 0.90`，输出回到 `972×2160`（20:9）、`972×1728`（16:9）、`648×1440`（720p），同时保留“1.5K/2K 不超过 1080p 口径”的封顶。

---

## 2. 背景与目标

- 目标：双方固定 1080p 口径，即使一方是 1.5K/2K 屏，也先压到 1080p 再发送。
- 0.3.35 的实现：短边上限 1080，保留全屏比例，不裁成 16:9；1080p 屏直接输出 `1080×2400`（原生尺寸，不再套 90%），720p 屏输出原生尺寸。
- 实际采用的公式只做了“封顶”，没有做“封顶后再按 90% 输出”，于是 1080p 及以下的屏幕反而比 0.3.33 更大。

---

## 3. 现状实现

### 3.1 输出计划

`ScreenContentVideoPlan.kt:17-18`：

```kotlin
val baseScale = maxOf(1.0, minOf(width, height).toDouble() / AppConfig.VIDEO_SEND_SHORT_EDGE)
val scale = baseScale * (if (fixed88Compare) 1.0 / 0.88
    else if (fixed80Compare) 1.0 / 0.80
    else 1.0)
```

- `VIDEO_SEND_SHORT_EDGE = 1080`（`AppConfig.kt:25`）。
- 正常分支乘 `1.0`，即“精确 1080 短边”；88/80 分支仍按比例缩小。
- `captureLongEdge` 仍是原屏长边，采集面不变；`ScreenCapturePipeline` 只在输出侧缩放，见 `ScreenCapturePipeline.kt:12-29`。
- `VideoCaptureFormat.forScreen` 用 `native / scale` 得到输出尺寸，并做偶数对齐（`VideoCaptureFormat.kt:6-14`）。

### 3.2 发送参数

`VideoSenderProfile.kt:7-19`：`maxBitrateBps = ceilingBps`、`maxFramerate = 30`、`scaleResolutionDownBy = 1.0`、`degradationPreference = DISABLED`。
`SessionMedia.applyVideoSenderPolicy()` 在 0.3.35 中不再有 `if (!tunedVideoPolicy) return` 的守卫，默认策略也会下发固定 `scaleResolutionDownBy` / `maxFramerate` / `DISABLED`（码率仍由库控制，见第 5.3 节）。

---

## 4. 问题

### 4.1 主因：正常分支漏掉 90%

| 屏幕（竖屏） | 0.3.33（短边 90%） | 0.3.35（精确 1080 短边） | 像素变化 |
|---|---|---|---|
| 1080×2400 | 972×2160 = 2.10MP | **1080×2400 = 2.59MP** | **+23.5%** |
| 720×1600 | 648×1440 = 0.93MP | **720×1600 = 1.15MP** | **+23.5%** |
| 1260×2800（1.5K） | 1134×2520 = 2.86MP | 1080×2400 = 2.59MP | −9.3% |
| 1440×3200（2K） | 1296×2880 = 3.73MP | 1080×2400 = 2.59MP | −30.6% |
| 1080×1920（16:9） | 972×1728 = 1.68MP | 1080×1920 = 2.07MP | +23.5% |

影响：

1. **同码率下每像素比特数下降**：4Mbps、30fps 时，`972×2160` 是 0.063 bpp，`1080×2400` 只有 0.051 bpp（−19%），QP 更高，文字更糊。
2. **编码负载上升 23.5%**：2.59MP@30fps 比 2.10MP@30fps 多四分之一像素；本来就接近编码上限的 1080p 设备会开始出现编码队列满、丢帧，表现就是卡。
3. **码率需求上升**：按 0.10 bpp 估算，`972×2160` 约需 6.3Mbps，`1080×2400` 约需 7.8Mbps；而当前起步上限仍是直连 4M / UDP 中继 3M / TCP 2M（`AppConfig.kt:32-34`），弱上行下只会更糊、更容易排队。

### 4.2 放大器：1080×2400 超出 H.264 Level 4.2 的单帧上限

按 16×16 宏块计算：

| 输出 | 宏块数 | Level 4.2 MaxFS=8,704 | 结论 |
|---|---|---|---|
| 972×2160 | 61×135 = **8,235** | 未超 | 4.2 内 |
| 1080×1920 | 68×120 = 8,160 | 未超 | 4.2 内 |
| **1080×2400** | 68×150 = **10,200** | **超出** | 需要 Level 5.0（MaxFS=22,080） |

如果编码器或协商参数按 Level 4.2 配置，`1080×2400` 可能被 `MediaCodec` 拒绝、触发重试，或回退到软件编码；软件编码 2.59MP@30fps 在手机上基本不可用，体感就是严重卡顿。此外部分编码器的最大高度只声明到 2160，2400 高会直接不支持。

这一条是**静态推断**，必须在真机上用诊断确认，不能仅凭代码下结论：

- `编码` 字段：hardware 还是 software；
- `sendCodec` 的 profile-level（是否 4.2）；
- `编码耗时 P50/P95` 是否明显升高；
- `限制` 是否为 `cpu`；
- `编码队列 ... /3 · 丢帧` 是否增长。

### 4.3 次要回归：默认策略也会关闭库自带降级

`SessionMedia.applyVideoSenderPolicy()` 现在没有 `if (!tunedVideoPolicy) return`（`SessionMedia.kt:650` 起），`VideoSenderProfile.apply(..., tunedBitrate=false)` 仍会设置 `degradationPreference = DISABLED`、`scaleResolutionDownBy = 1.0`、`maxFramerate = 30`。也就是说，**默认策略**下 WebRTC 自带的画质降级被关掉，但码率仍由库控制。新策略不受影响；如果测试时用的是“默认策略”，这是另一个独立的变卡来源。

### 4.4 测试把错误尺寸写成了期望值

以下测试目前断言 `1080×2400` / `scale = 1.0`，会随实现一起“通过”，因此不能证明尺寸正确：

- `ScreenContentVideoPlanTest.kt`：第 10、18、27、31、41、47、56 行附近；
- `Fixed1080VideoPipelineTest.kt`：第 43、49、55、85、100、137、162、178、189、223 行附近；
- `NativeResolutionPipelineTest.kt`：第 31、81、85 行附近。

---

## 5. 改进方案

### 5.1 主修复：正常分支补回 90%

```kotlin
val baseScale = maxOf(1.0, minOf(width, height).toDouble() / AppConfig.VIDEO_SEND_SHORT_EDGE)
val scale = baseScale * when {
    fixed88Compare -> 1.0 / 0.88
    fixed80Compare -> 1.0 / 0.80
    else           -> 1.0 / 0.90
}
```

预期输出：

| 屏幕 | 修复后输出 | 说明 |
|---|---|---|
| 1080×2400 | 972×2160 | 回到 0.3.33，Level 4.2 内 |
| 1260×2800 | 972×2160 | 1.5K 被压到 1080p 口径 |
| 1440×3200 | 972×2160 | 2K 被压到 1080p 口径 |
| 1220×2712 | 972×2160 | 偶数取整 |
| 1240×2772 | 972×2172 | 保留全屏比例 |
| 1080×1920 | 972×1728 | 16:9 的 90% |
| 2160×3840 | 972×1728 | 4K 被压到 1080p 口径 |
| 720×1600 | 648×1440 | 小屏保持 90%，不放大 |

- 88/80 对照版：`950×2112` / `864×1920`（20:9），保持相对关系；这两个分支本来就有比例因子，本次修复只改正常分支。
- 采集面仍是原屏尺寸，`ScreenCapturePipeline` 不需要改；没有运行期尺寸切换，不引入编码器重配。
- 这是“1080p 上限 + 原有 90%”，不是“精确 1080×2400”。

### 5.2 测试更新

- 将上述测试的期望改为 `972×2160`、`972×1728`、`648×1440` 等实际输出；
- 增加边界：`1080×2400`、`1260×2800`、`1440×3200`、`1240×2772`、横屏交换、720p 不放大；
- 增加一条“1080p 屏输出不得是 1080×2400”的回归断言，防止再次漏掉 90%。

### 5.3 默认策略守卫（可选，但建议）

如果默认策略也要保留“1080p 封顶”，至少不要关闭库自带降级：

- 恢复 `applyVideoSenderPolicy()` 开头的 `if (!tunedVideoPolicy) return`；或
- 在 `tunedBitrate = false` 时只设置 `scaleResolutionDownBy = 1.0` / `maxFramerate`，不设置 `degradationPreference = DISABLED`。

测试时先确认诊断页显示“新策略”，再判断本次改动的效果；默认策略的问题单独排查。

### 5.4 如果坚持精确 1080×2400

必须同时满足：

1. 强制 H.264 Level 5.0+，并确认两台手机的硬编都支持 2400 高；
2. 把起步码率按 2.59MP 重新评估（0.10 bpp 约 7.8Mbps），否则弱上行只会更糊；
3. 接受 1080p/720p 手机编码负载 +23.5%。

老设备上不建议走这条路；`972×2160` 是 Level 4.2 安全档，也是 0.3.33 已经验证过的尺寸。

---

## 6. 验收

### 6.1 单元测试

```bash
cd android
./gradlew :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest --no-daemon --console=plain
```

重点看 `ScreenContentVideoPlanTest`、`Fixed1080VideoPipelineTest`、`NativeResolutionPipelineTest` 的新期望。

### 6.2 真机

同一对手机、同一页面、同一网络，对比 0.3.33 与修复版；两个方向各做一次：

- 被控端 `目标输出` / `最近编码` 应为 `972×2160`（20:9）/ `972×1728`（16:9）/ `648×1440`（720p）；
- `编码` 应为 hardware；若仍为 software，检查 Level/尺寸支持；
- `QP均值` 下降、`有效bpp` 上升；
- `编码耗时 P50/P95` 下降；
- `编码队列丢帧`、`freezes`、`发包排队` 下降；
- 控制端相机拍屏，对比小字边缘。

### 6.3 通过标准

- 1080p 被控端不再出现 `1080×2400` 编码；
- 编码器没有回退到 software；
- 两个方向的 `编码耗时` 和 `freezes` 回到 0.3.33 水平或更好；
- 1.5K/2K 方向仍保持封顶（`972×2160`），不因修复回升到 1.5K/2K。

---

## 7. 风险与边界

- 修复后 1.5K/2K 的输出会比 0.3.35 的 `1080×2400` 更小（`972×2160`），文字略软，但码率、编码耗时和稳定性更好；这是“1080p 上限 + 90%”的直接取舍。
- 1080p/720p 手机会回到 0.3.33 行为，不再被放大。
- 本次仍**不解决**上行带宽、NAT/中继 RTT、触摸注入延迟；这些需要单独用路径和回执数据判断。
- 本文的 Level 4.2 影响是规范层面的推断，实际是否回退必须以真机 `编码` / `编码耗时` / `限制` 为准；未做双机实测前，不宣称卡顿已量化改善。
- 未修改 AAR、原生库、协议、后端；修复只改应用层输出计算与测试。

---

## 8. 附：关键位置

- 输出计划：`android/app/src/main/java/com/simplerc/app/webrtc/ScreenContentVideoPlan.kt`
- 尺寸换算与偶数对齐：`android/app/src/main/java/com/simplerc/app/webrtc/VideoCaptureFormat.kt`
- 采集面与输出分离：`android/app/src/main/java/com/simplerc/app/webrtc/ScreenCapturePipeline.kt`
- 发送参数：`android/app/src/main/java/com/simplerc/app/webrtc/VideoSenderProfile.kt`
- 策略应用：`android/app/src/main/java/com/simplerc/app/session/SessionMedia.kt`
- 常量：`android/app/src/main/java/com/simplerc/app/config/AppConfig.kt`
- 相关测试：`ScreenContentVideoPlanTest.kt`、`Fixed1080VideoPipelineTest.kt`、`NativeResolutionPipelineTest.kt`
