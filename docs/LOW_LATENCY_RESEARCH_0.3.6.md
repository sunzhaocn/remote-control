# 手机控制手机：无 ADB、无 root 的低延迟方案与 0.3.6 实现

日期：2026-10-13。检索覆盖 Android 官方接口、WebRTC 标准与源码、RustDesk 源码、scrcpy、AnyDesk、TeamViewer、AirDroid 和 Coturn。没有统一的同设备、同网络实测排名，不能把任何方案宣称为全网最快。

## 结论与选择

对于当前两台普通 Android 手机，选择 **MediaProjection 原生录屏 + WebRTC 硬件编码优先 + 独立控制 PeerConnection + 无障碍连续手势**。这是满足约束、能修改源码和自建服务器的工程选择，流畅度仍须实测。

| 路线 | 与约束的关系 | 本项目取舍 |
| --- | --- | --- |
| Android MediaProjection + AccessibilityService | 用户手动授权录屏与无障碍即可；连续手势 API 从 Android 8 起可用 | 采用。直接改掉松手后才执行的整段滑动 |
| RustDesk | Android 输入服务使用无障碍 `continueStroke`，支持自建服务 | 对照其公开实现验证连续手势路线；没有换软件后的同条件性能证据 |
| AirDroid/AirMirror + Control Add-on | 官方支持通过无障碍插件实现手机控制手机，免电脑连接和 root | 可行商业替代；不能从宣传材料推导它一定更快 |
| AnyDesk / TeamViewer | 通用插件依赖无障碍；厂家插件可能提供额外能力，但兼容性不同 | 普通应用不能通过修改自建服务器取得厂商签名权限 |
| scrcpy / Shizuku / scrcpy-over-WebRTC | scrcpy 标准屏幕远控路径需要调试权限；某些“免 root”应用仍靠 Shizuku 的 shell 权限 | 不采用。免 root 不等于免 ADB；OTG 例外不是本项目的互联网屏幕远控路线 |
| 大量视频数据和触控放同一 DataChannel / 同一 TCP 流 | 会产生共享队列或拥塞影响；同一 SCTP association 的流共享拥塞窗口 | 不采用。单独增加 channel 名称不足以隔离视频拥塞 |
| 自制裸 UDP 或 QUIC 全套传输 | 仍需要加密、会话权限、NAT 穿透、反馈与丢帧恢复；不解除 Android 输入权限 | 暂无证据证明重写成熟传输栈会更快，保留 WebRTC 的媒体拥塞控制和 TURN 穿透 |

## 已落实的传输与输入

媒体 PC 只承载视频/音频。控制 PC 独立协商 ICE、DTLS、SCTP，使用现有服务端 CONTROL_OFFER/ANSWER/ICE/RESTART 信令，不需要后端协议升级。

控制 PC 中 `control` 是可靠有序通道，承载 DOWN、UP、CANCEL、导航、时钟、ACK、音频许可；`touch-latest` 是无序、零重传通道，只接收 MOVE。两条 PC 隔离传输队列，但仍共享手机 CPU 和实际网络链路。

- 控制画面用单一按下/移动/抬起监听，立即发送 DOWN，不再等待拖动识别或松手后发 600ms 整段路径。长按来自实际持有时间。
- MOVE 携带手势 ID、序号、起点和最新位置，按事件最多约 16ms 一次发送，持有时每 40ms 发送最新状态。快速通道缓冲超过 2KiB 跳过本次，下一次发送最新位置。
- DOWN/UP/CANCEL 走可靠通道，缓冲超过 8KiB 不再追加。连接失效时可靠投递仍可能失败，由被控端超时负责松手。
- 接收端只保留最新移动目标和正在执行手势的一条结束动作。乱序、重复、结束后的旧包不能重新按下；旧回调不能清除下一笔手势。MOVE 先于 DOWN 到达也能从自带起点恢复。
- Android 手势一段约 24ms，通过 `continueStroke` 保持同一个触点，每次等完成回调才继续，结束续段约 1ms。这些是应用请求时长，并非实测输入延迟；Android 采样与回调会增加耗时。
- 指令绑定当前会话、权限、几何版本和校准后的单调时间，触控年龄上界含时钟不确定度不得超过 700ms。被控端 700ms 未收到有效状态则结束触点；断开、界面取消、第二根手指、权限中断和会话结束也取消。
- 起点在留边之外不发送；实时拖动移出内容区即结束当前触点，不把越界位置夹到屏幕边缘。已发生的触控无法撤销。
- 第二根手指会结束当前手势，当前版本仍为单指控制。无障碍取消通过结束续段释放触点，不能宣称拥有底层原生 ACTION_CANCEL/多点注入能力。

## 视频让出带宽

“新策略”起始视频上限 2Mbps，最高 4Mbps；可用带宽统计存在时，预算取 80% 后为音频预留 128kbps、为控制预留 64kbps，最低预算 150kbps。极低带宽下这个下限仍可能超过链路容量，不能保证不断流或零排队。

每两秒读一次真实统计。控制 RTT 相比其基线增加 100ms、媒体 RTT 相比其基线增加 120ms，或丢包超过 3% 时，上限乘 0.65；每三个健康样本最多恢复 15%。固定远距离 RTT 不直接当作拥塞；控制路径类型变化时重新建立基线。阈值是工程初值，未做双机网络调参。

连续两个样本编码超过 25ms 或报告 CPU 限制时，发送分辨率缩小至原长宽的二分之一；连续恢复后再还原。采集长边 1280、源输出最高 30fps、优先保帧率；可用硬件 H.264 优先并保留兼容 codec。`setParameters` 失败会如实显示，硬件能力广告不等于实际编码器成功初始化。

## 服务器现场检查

SSH 使用工作区已有凭据与已知主机密钥，未打印秘密。检查时后端和 Coturn CPU 均低于 1%，可用内存约 0.8–0.9GiB；内核 eth0 各发送队列已有 fq_codel；本机后端 `/healthz` 返回 204。仅是当前快照，不是公网压力或 TURN 会话性能测试。

已有配置提供 TURN/UDP、TURN/TCP/TLS 回退。正常 ICE 会尝试直连；不能用 URL 列表顺序保证选中 UDP，也没有禁用受限网络所需的 TCP/TLS 回退。本次无证据要求重写或重启后端、数据库、Redis、Coturn；服务器只增加新版 APK 的独立下载文件。

## 验证与待测

Android `assembleDebug` 和指定 `testDebugUnitTest` 通过。单元测试覆盖：丢 DOWN、MOVE 乱序、UP 先到、旧手势/回调、失联松手、保持长按、大量移动合并、串行调度、下一笔快速输入、上一笔结束位置保存、会话重置、Android 取消/拒绝分支、RTT/丢包/容量/CPU 降级和逐步恢复。

测试中的手势驱动是确定性模拟，不是手机系统注入。当前版本没有双机拖动、听感、公网 TURN、移动网络切换或端到端触控到画面延迟实测；没有用 ADB、root、Shizuku 安装或操控手机。两端须手动覆盖安装 0.3.6，选择“新策略”后重新连接。

实机重点：长按后拖动、快速往返滚动、短点按与快速连点、拖动中断网/后台/权限失效；再分别比较同 Wi-Fi、移动网络和 TURN 回退，记录控制 RTT、编码/解码时间、帧率、冻结与缓冲。只看 RTT 不能证明画面流畅。

## 可核对的主要来源

1. [Android MediaProjection 官方说明](https://developer.android.com/media/grow/media-projection)：录屏授权、前台服务、会话与新版本限制。
2. [Android StrokeDescription / continueStroke](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.StrokeDescription)：连续触点和首尾点必须匹配。
3. [Android AccessibilityService / dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)：手势回调与取消行为。
4. [WebRTC DataChannel 标准 RFC 8831](https://www.rfc-editor.org/rfc/rfc8831.html)：同 SCTP association 共享拥塞窗口，可靠性和顺序设置。
5. [WebRTC Android DataChannel.java](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/DataChannel.java)：ordered、maxRetransmits 和 bufferedAmount。
6. [RustDesk Android 输入源码](https://github.com/rustdesk/rustdesk/blob/master/flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/InputService.kt)：连续手势路线的公开实现。
7. [RustDesk Android 官方指南](https://rustdesk.com/docs/en/client/android/)：控制与屏幕权限。
8. [AirDroid 官方手机远程协助指南](https://www.airdroid.com/guide/personal-remote-support/)：AirMirror 与无障碍 Control Add-on。
9. [AnyDesk Android 官方说明](https://support.anydesk.com/docs/anydesk-for-android)：通用/厂家插件及兼容性。
10. [TeamViewer Universal add-on](https://www.teamviewer.com/en-us/global/support/knowledge-base/teamviewer-classic/mobile/android/universal-add-on-for-android/)：通用无障碍插件和厂家差异。
11. [scrcpy 官方项目](https://github.com/Genymobile/scrcpy)：标准屏幕远控的调试权限与 OTG 边界。
12. [Coturn 官方选项说明](https://github.com/coturn/coturn/wiki/turnserver)：中继传输和容量配置。
