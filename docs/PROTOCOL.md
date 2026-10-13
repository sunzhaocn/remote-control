# 当前消息协议（0.3.4）

## WSS

JSON 包含 version=1、type、sequence、sentAtEpochMs、payload，会话消息另含 sessionId。服务端最大读取 64 KiB，设备认证后校验递增序号、类型和会话归属；时间允许过去 60 秒、未来 10 秒。客户端序号分配与 Socket 发送串行执行。STALE_OR_INVALID 的脱敏 reason 区分 SEQUENCE/TIME/VERSION，不记录凭据、SDP 或 ICE 地址。

REGISTER_RANDOM/REGISTER_FIXED 发布凭据；CONNECT 使用目标 ID/code；INCOMING、CONFIRM 完成本机确认；CONFIRMED 提供会话和短期 TURN 配置。OFFER/ANSWER/ICE/GEOMETRY/AUDIO_STATE/ICE_RESTART 仅在所属会话转发。LEASE_RENEW/HEARTBEAT_ACK 续约；REVOKED 不回发 REVOKE，已结束会话的重复撤销幂等。

只有 CONNECT 验证失败累计 IP、设备、目标限流；注册、凭据发布、心跳不累计。RATE_LIMITED 带 retryAfterSeconds，客户端倒计时结束前禁止再次验证。

## DataChannel

当前仅一个可靠有序 control 通道，JSON 最大 16 KiB，拒绝二进制。滑动最多 256 点，以完整手势发送；独立高频短寿命通道未实现。

- 控制绑定 sessionId、递增 sequence、geometryVersion、createdAtElapsedMs，白名单 TAP/LONG_PRESS/SWIPE/BACK/HOME/RECENTS。
- CLOCK_PING/PONG 用会话内 nonce 和四个单调时间戳校准偏移。往返最多 990ms、不确定度最多 500ms；含不确定度的指令年龄上界不超过原 2000ms。未校准不能操作，不依赖墙钟一致。
- 坐标为归一化内容坐标；异常值、无效点数、越界、旧几何版本整段拒绝。点击/长按仅一处坐标，导航无坐标。
- ACK 成功来自 dispatchGesture 完成或 performGlobalAction 返回值；取消/拒绝返回失败。发出消息不等于执行成功。
- AUDIO_POLICY 优先可靠通道，无法发送回退 WSS AUDIO_STATE；含 command、allowed、ok、递增 version、requestVersion。本机许可权威，旧版本确认丢弃。STOP 先静音本地，再等远端确认；5 秒无确认如实显示。

恢复不重放旧操作。独立 SDP 指纹固定校验未完整实现，仍需安全审查。

## 0.3.5 补充

CONTROL_OFFER/CONTROL_ANSWER/CONTROL_ICE/CONTROL_RESTART 为专属触控PeerConnection的信令，仍受原会话归属和租约控制，不能与媒体OFFER/ICE混用。媒体PC仅视频/音频，控制PC仅DataChannel；时钟、ACK、音频许可消息优先控制PC。

CONNECT的videoPolicy仅允许default/tuned，服务器将其固定到会话并在CONFIRMED给双方。只在下次连接生效，重连沿用原值。两端必须同为0.3.5，旧客户端不兼容专属控制协商。控制消息超过16KiB或队列超过32KiB拒绝新操作，不无限积压。
