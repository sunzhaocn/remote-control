# 错误码

| 代码 | 用户提示 | 说明 |
| --- | --- | --- |
| `AUTH_FAILED` | 此 ID 或验证码有误 | 未知、错码、未启用、占用统一返回 |
| `NETWORK_UNAVAILABLE` | 网络不可用，请检查网络 | 本地或服务不可达 |
| `CONFIRM_TIMEOUT` | 被控端确认超时 | 30 秒 |
| `HANDSHAKE_TIMEOUT` | 连接建立超时 | 20 秒 |
| `LEASE_LOST` | 会话已失效 | 租约归属或续约失败 |
| `PROJECTION_REQUIRED` | 需要重新授权屏幕共享 | token 不存在或已停止 |
| `GEOMETRY_STALE` | 画面正在同步，请稍候 | 几何版本不一致 |
| `CONTROL_REJECTED` | 操作未执行 | 无障碍拒绝、取消或字段非法 |
| `AUDIO_PATCH_MISSING` | 内部音频不可用（未接入补丁构建） | 标准 AAR 的预期状态 |
| `RECONNECT_EXPIRED` | 重连超时，会话已结束 | 历史码；当前使用15秒RECONNECT_TIMEOUT |

## 当前错误码补充（0.3.5）

| 代码 | 提示/边界 |
| --- | --- |
| RECONNECT_TIMEOUT | 15秒恢复截止，会话停止，重试/停止可见；覆盖历史120秒RECONNECT_EXPIRED说明 |
| MEDIA_TIMEOUT | 20秒视频或独立控制握手/时间校准未就绪，具体阶段提示 |
| ACCESSIBILITY_LOST | 当前服务实例销毁安全结束，需用户重新启用 |
| SCREEN_LOCKED | 主动锁屏停止共享，不自动重录屏 |
| CAPTURE_START_FAILED / CAPTURE_SERVICE_STOPPED | 采集启动/服务停止，释放资源，新会话重新授权 |
| CONTROL_SDP_CREATE_FAILED / CONTROL_SDP_SET_FAILED / CONTROL_SDP_SEND_FAILED | 独立控制描述失败；在媒体终止详情中呈现 |
| AUDIO_START_FAILED / AUDIO_STOP_FAILED | 实际启动/清理失败，不伪报远端停传成功 |
| RATE_LIMITED | 服务端秒数倒计时，仅控制端验证重试；被控注册不被该倒计时阻断 |
