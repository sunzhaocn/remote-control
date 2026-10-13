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
| `RECONNECT_EXPIRED` | 重连超时，会话已结束 | 超过 120 秒 |
