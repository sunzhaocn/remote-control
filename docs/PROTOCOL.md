# 当前消息协议（0.3.37，含历史兼容说明）

## 0.3.37 设备帧率保护

网络字段、消息类型、授权、序号和原始有效期均不变。VIDEO_FEEDBACK现有设备耗时与decodeFps/renderFps用于30/24fps保护；senderPolicy继续在512字符内同步当前目标和保护原因。反馈缺测或过期不能作为恢复余量。纯网络拥塞仍由原有码率保护处理，默认策略保持30fps。没有新增服务端协议或存储权限；两端同版本选择新策略重连。

## 0.3.36 媒体候选自报与路径诊断

沿用VIDEO_FEEDBACK、sessionId、sequence、createdAtElapsedMs；默认和新策略均可每500ms交换媒体选路信息。新增可选localCandidateType（host/srflx/prflx/relay），只取本机媒体连接实际选中候选；localRelayProtocol只在localCandidateType=relay且协议为udp/tcp/tls时发送。新字段经独立控制连接的touch-latest发送、失败回退control，但描述的是媒体连接，不能用于判定独立控制路径。

接收保持会话、授权、递增序号和原始1500ms时间门禁；每次使用再核验剩余寿命，接收不续期。旧客户端省略字段时保持未知；已知relay优先于直连自报，远端prflx且无本机候选自报时不认定P2P。对端自报揭示中继时注明对端报告，不视为本机getStats直接观察到relay。不增加服务端消息或修改TURN配置；两端同版本重连以获得完整诊断。

## 0.3.12 顺序与有效期

消息格式不变。点击/导航在接收时按 PeerClock 的误差上界换算剩余有效期，排队沿用同一个本机截止时间，执行前再次检查；不再从接收时重新取得 2 秒。TOUCH 的既有 700ms 校验及迟到终止释放规则不变。输入 ACK 在接收时也检查 3 秒上限，重复与过期回执不更新诊断。

音频 START/STOP 的 requestVersion 在被控端跨 DataChannel/WSS 共同去重，旧 START 不能覆盖新 STOP。控制端忽略旧请求的结果；allowed=false 的本机撤权仍无条件生效，allowed=true 的旧失败不会取消新重试。

后端本地修复版只转发已确认会话的协商消息，确认/超时原子转换，确认通知失败立即撤销并释放租约；客户端与此前版本消息兼容。该后端修复尚未部署，不能视作线上已生效。

## 0.3.11 视频反馈与真实中继协议

新策略下，两端每 500ms 可通过可靠 control 发送 VIDEO_FEEDBACK；公共字段仍为 sessionId、kind、sequence、createdAtElapsedMs。控制端发送可选 queueMs/drawMs/swapMs/decodeMs，并新增 jitterMs（接收抖动缓冲的本次间隔平均耗时）；双方都可发送各自媒体连接的 localRelayProtocol，白名单 udp/tcp/tls。该字段只能来自本地选中的 relay 候选，不从远端候选的 protocol 推断。

接收方校验会话、授权、递增序号和时钟时效，耗时仍限有限的 0..2000ms；超过 3 秒不使用。发送缓冲达到 2KiB 跳过反馈。媒体候选对发生变化、暂时消失或重新出现时清除旧反馈和网络基线；控制候选对变化也重置网络基线。双方反馈用于判断一条或两条中继接入腿是否含 TCP/TLS，未确认时采用保守预算。

此变更只涉及客户端 DataChannel，无需修改后端；两端覆盖安装 0.3.11 后重连才能获得完整反馈与底层修复。以下 0.3.10 触控规则继续有效。

## 0.3.10 触控结束与几何恢复

两端安装 0.3.10 后重连。UP/CANCEL 仍经可靠 control 发送，并以相同序号复制到 touch-latest（2KiB 发送缓冲限制），接收端允许快速通道的 MOVE/UP/CANCEL；未知终止不得创建新手势，重复结束不会执行两遍。手势起点和意图固定，不能中途把 DRAG 升级为 HOLD。

普通 DRAG 起始位移须超过被控端 scaledTouchSlop 加 1px 的舍入余量才注入，等待期间没有 Android 按下触点；小位移结束不变成 TAP。已识别 DRAG 240ms 无有效状态则释放，明确 HOLD 仍采用 700ms。本地移动/松手不依据超时产生 HOLD，仅仍按住时的本地计时器触发。取消清理待执行点击和导航，保留会话内去重水位。

新增可靠 control 的 GEOMETRY 消息：sessionId、kind=GEOMETRY、payload（原 WSS GEOMETRY 字段）。只由控制端接收有效且更新的几何版本；发送失败回退原信令，控制通道打开与连接恢复时重发。媒体/控制协商在 answer 完成前不重复发起。无需修改后端。

以下是此前版本的行为记录；与上述规则冲突时以 0.3.10 为准。

## 0.3.9 触控意图与结束保护

控制界面在本机使用系统移动/长按阈值分类，短点击松手后发送既有 TAP，远端固定 50ms 完整手势，不使用持续 DOWN。滑动和长按使用 TOUCH，新增 `intent=DRAG/HOLD`，所有段携带同一意图；缺省兼容 DRAG。首个 HOLD 在原点等待被控端系统长按阈值加 50ms，随后继续到最新目标。

UP/CANCEL 发送缓冲上限 64KiB，其他可靠触控 8KiB、MOVE 2KiB。过期或旧几何结束消息只能释放本会话已知同 ID 触点，不能用旧坐标新建手势；其他过期状态仍拒绝。TAP 与持续手势在同一执行泵串行，最多 16 个待执行点击，排队超过 2 秒拒绝，断线清空。两端同为 0.3.9 才能完整采用本地意图分类；后端信令没有变更。

## WSS

JSON 包含 version=1、type、sequence、sentAtEpochMs、payload，会话消息另含 sessionId。服务端最大读取 64 KiB，设备认证后校验递增序号、类型和会话归属；时间允许过去 60 秒、未来 10 秒。客户端序号分配与 Socket 发送串行执行。STALE_OR_INVALID 的脱敏 reason 区分 SEQUENCE/TIME/VERSION，不记录凭据、SDP 或 ICE 地址。

REGISTER_RANDOM/REGISTER_FIXED 发布凭据；CONNECT 使用目标 ID/code；INCOMING、CONFIRM 完成本机确认；CONFIRMED 提供会话和短期 TURN 配置。OFFER/ANSWER/ICE/GEOMETRY/AUDIO_STATE/ICE_RESTART 仅在所属会话转发。LEASE_RENEW/HEARTBEAT_ACK 续约；REVOKED 不回发 REVOKE，已结束会话的重复撤销幂等。

只有 CONNECT 验证失败累计 IP、设备、目标限流；注册、凭据发布、心跳不累计。RATE_LIMITED 带 retryAfterSeconds，客户端倒计时结束前禁止再次验证。

## DataChannel

0.3.4 的旧输入格式使用可靠有序 control 通道，JSON 最大 16 KiB，拒绝二进制。0.3.6 控制界面改用下述实时 TOUCH 格式；0.3.16 删除绕过统一触控调度的旧整段 SWIPE 派发，收到旧 SWIPE 明确返回失败 ACK，不影响正在执行的手势。两端应升级同版本。

- 控制绑定 sessionId、递增 sequence、geometryVersion、createdAtElapsedMs，离散操作白名单 TAP/LONG_PRESS/BACK/HOME/RECENTS；滑动统一使用 TOUCH。
- CLOCK_PING/PONG 用会话内 nonce 和四个单调时间戳校准偏移。往返最多 990ms、不确定度最多 500ms；含不确定度的指令年龄上界不超过原 2000ms。未校准不能操作，不依赖墙钟一致。
- 坐标为归一化内容坐标；异常值、无效点数、越界、旧几何版本整段拒绝。点击/长按仅一处坐标，导航无坐标。
- ACK 成功来自 dispatchGesture 完成或 performGlobalAction 返回值；取消/拒绝返回失败。发出消息不等于执行成功。
- AUDIO_POLICY 优先可靠通道，无法发送回退 WSS AUDIO_STATE；含 command、allowed、ok、递增 version、requestVersion。本机许可权威，旧版本确认丢弃。STOP 先静音本地，再等远端确认；5 秒无确认如实显示。

恢复不重放旧操作。独立 SDP 指纹固定校验未完整实现，仍需安全审查。

## 0.3.5 补充

CONTROL_OFFER/CONTROL_ANSWER/CONTROL_ICE/CONTROL_RESTART 为专属触控PeerConnection的信令，仍受原会话归属和租约控制，不能与媒体OFFER/ICE混用。媒体PC仅视频/音频，控制PC仅DataChannel；时钟、ACK、音频许可消息优先控制PC。

CONNECT的videoPolicy仅允许default/tuned，服务器将其固定到会话并在CONFIRMED给双方。只在下次连接生效，重连沿用原值。两端必须同为0.3.5，旧客户端不兼容专属控制协商。控制消息超过16KiB或队列超过32KiB拒绝新操作，不无限积压。

## 0.3.6 实时触控

两端须同为 0.3.6。媒体与控制仍是两条独立 PC，后端信令无需变更。控制 PC 有两个 DataChannel：`control` 可靠有序，`touch-latest` 无序且 `maxRetransmits=0`，后者只接受 MOVE。

TOUCH JSON：sessionId、kind=TOUCH、phase=DOWN/MOVE/UP/CANCEL、gestureId、sequence、geometryVersion、createdAtElapsedMs、startX/startY/x/y。坐标归一化，包含原始起点和当前目标；MOVE 先于 DOWN 可恢复。DOWN/UP/CANCEL 必须经可靠通道；MOVE 无序按手势内序号丢旧，仅保留最新目标。终止 ID 不能复活。

触控消息含校准不确定度的年龄上界 700ms；连续 700ms 没有有效状态取消。发送端约 16ms 事件节流及 40ms 持有状态更新；快速/可靠缓冲阈值为 2/8KiB。被控端一次仅一个 Android 连续手势 dispatch，在执行手势的终止位置不会被下一笔覆盖。ACK 来自驱动完成/取消回调，发送成功不等于系统执行成功。

目前单指，取消通过结束续段释放触点；不具备系统级 ACTION_CANCEL 或多点底层输入注入。更多策略、来源和验证界限见 LOW_LATENCY_RESEARCH_0.3.6。

## 0.3.7 显示压力与实际往返

两端同为 0.3.7。控制端每秒可通过可靠 control 发送 VIDEO_FEEDBACK：sessionId、kind、sequence、createdAtElapsedMs、可选 queueMs/drawMs/swapMs/decodeMs。只传汇总耗时，有限值须在 0..2000ms；缺测字段省略。消息通过会话/序号/时钟时效校验，超过 3 秒不再参与策略；bufferedAmount 达 2KiB 则跳过反馈，不把反馈排队。

CLOCK 校准起始 5 次、间隔 300ms，随后每两秒探测。保存最近有效应用往返用于诊断及视频退让；低误差校准或 30 秒更新偏移。操作 ACK 仅匹配本会话最多 32 条、记录时清理超过 3 秒的 UP/CANCEL/导航提交；回执匹配才更新耗时，旧回执不会覆盖状态。既有 TOUCH 和独立 PC 协商不变，新增消息无需服务器转发。

## 0.3.20 视频交互提示

VIDEO_MOTION只调节画质，不能注入输入、不推进lastControlSequence。带sessionId、gestureId、sequence、geometryVersion、createdAtElapsedMs和ended；优先touch-latest，失败回退可靠control，结束在双通道发送。移动位移至少2个远端像素，提示间隔至少16ms，220ms寿命扣除原始事件年龄、网络年龄和时钟误差。结束水位拒绝迟到重启，最多80ms收尾；持续视频与触控心跳不续期。导航脉冲仅在被控端实际执行BACK/HOME/RECENTS前启动350ms，无需服务端协议变更。两端升级同版本。

## 0.3.21 控制端动作提示

VIDEO_ACTION带sessionId、sequence、geometryVersion、createdAtElapsedMs，共用离散操作序号，不推进lastControlSequence、不注入输入。控制端先向touch-latest和control发送提示，再发TAP/LONG_PRESS/BACK/HOME/RECENTS；350ms寿命扣除网络年龄与时钟误差，双通道副本按独立actionSequence去重。被控端收到有效提示即应用交互档，操作派发和ACK不重新续期。VIDEO_MOTION沿用，服务端不变，两端需同版本。

## 0.3.22 画质自适应语义

VIDEO_ACTION/VIDEO_MOTION格式与排序不变，继续只描述控制端输入上下文；收到提示不再固定降低分辨率或码率。所有状态使用同一实测自适应预算，状态本身不重配编码器。VIDEO_FEEDBACK原有decodeFps/renderFps等字段用于实际接收吞吐保护，反馈有效期收紧到1.5秒，未知或过期值不虚构余量。无需服务端协议修改。

## 0.3.23 恢复验证反馈

VIDEO_FEEDBACK可经touch-latest实时通道发送，失败回退control，sessionId/sequence/原始时间门禁保持，寿命1500ms。新增decodedWitness、decodedWidth、decodedHeight、completedSwaps描述实际解码/显示提交进度；被控端senderPolicy最多512字符同步恢复状态到控制端。试升确认使用扣除时钟不确定度后的采样时间，必须晚于参数应用；旧序号和旧采样不确认新试验。计数不是屏幕实际呈现或端到端延迟证明，无服务端协议修改。两端需同版本。

## 0.3.24 恢复验证稳定性

反馈字段及1500ms原始时间门禁不变。持续画面从参数应用后的新反馈建立基线，连续三个采样间隔核对目标尺寸、新解码帧和显示提交进度，观察跨度至少2秒；重复、停滞或错误尺寸不能累积通过。视频试验总期限5秒，静止仍2.5秒；调试文本包含刚确认后撤回的原因与重试等待。无需服务端协议修改，两端覆盖同版本。

## 0.3.25 本机亮屏状态

亮屏保护由本机前台服务租约、Activity可见性和系统亮灭屏事件管理，与视频策略及输入帧率独立。screenAwakeStatus、lastBackgroundScreenAwake、lastScreenOff仅为本机应用诊断状态，不增加网络消息或改变输入/媒体协议。悬浮显示授权由Android系统管理，无服务端改动。

## 0.3.26 本机亮屏生命周期

前台标记、后台窗口及显示锁统一按当前会话驱动；无会话的注册/待机重试不申请亮屏。资源释放失败时保留归属并继续维护，诊断可显示正在释放或重建。均为本机资源行为，未添加或修改网络消息。

## 0.3.27 导航恢复和本地返回

VIDEO_ACTION/VIDEO_MOTION格式与寿命不变。有效提示重新允许到期后的静止清晰补帧；文字精修最长5秒、静止保护档试升最长3.5秒，连续画面仍5秒，确认继续核对新解码/显示反馈。返回100次门槛只在控制端Activity本地处理，不发送远端输入；不修改无障碍服务、被控端导航与服务端协议。两端同版本重连。

## 0.3.28 实际输出与恢复

网络消息和原始时间门禁不变。应用在纹理上精确缩放，原生分辨率/帧率资源适配关闭，拥塞控制保留。senderPolicy继续在512字符内同步，内容新增目标输出、最近编码和对端解码尺寸；尺寸错误时恢复原因显示实际宽高。连续画面两次经接收/显示进度验证的成功试升可解除旧失败恢复门槛，成功后仍有稳定停留和迟到压力回退。两端同版本、选择新策略后重连，无后端修改。

## 0.3.29 静止文字清晰档

网络字段和时效门禁不变。静止快照使用独立尺寸/预算并确认，不提交为连续视频保护档；原屏细节长边上限3200，预算尝试8Mbps并受路径最大值限制。文字补帧至少12、最多24次请求，间隔至少150ms，验证最长6秒；操作或真实画面恢复即退出。senderPolicy继续同步目标/最近编码/对端解码和回退原因。两端覆盖同版本、选择新策略后重连，无服务端修改。

## 0.3.30 无操作固定90%分辨率

消息格式和原始时间门禁不变。没有有效交互提示时，静止/媒体输出宽高约为原屏90%，忽略资源像素保护档、试升档和绝对长边上限；文字精修沿用同一尺寸。senderPolicy增加固定90%说明，码率和真实拥塞保护仍自适应。没有增加全局触摸监听，也不将图像变化当输入。两端同版本、选择新策略后重连，无服务端修改。
