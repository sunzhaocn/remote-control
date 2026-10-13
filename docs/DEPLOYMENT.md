# 部署与 TURN

正式环境需要用户提供信令域名、TURN 域名、公网 IP、TLS 证书、PostgreSQL/Redis 密码和至少 32 字节 credential pepper。模板中的 `example.com` 和 `REPLACE_*` 不是可用部署。

信令必须通过 HTTPS/WSS。Coturn 提供同一自建服务的 STUN、TURN/UDP、TURN/TCP 和 TURN/TLS；UDP 通常延迟最低，TCP/TLS 是受限网络回退且可能增加延迟。TURN 凭据由服务端用共享密钥签发短期用户名/密码，APK 不保存永久 TURN 密钥。

TURN 会承载完整媒体流量。粗略出口带宽：`并发中继会话数 × (视频目标码率 + 音频码率 + 约 15% 协议/重传余量) × 2方向`；月流量再乘活跃秒数并换算字节。720p/30fps 不能用固定码率承诺，必须按实际统计和峰值容量规划。

Compose 只暴露模板；本次未启动或验证容器。生产应将数据库/Redis限制在内网，开放 3478 UDP/TCP、5349 TCP 和配置的 UDP relay 端口范围，并使用防火墙、监控、备份和证书续期。

路径显示必须来自选中 candidate pair：relay 显示 TURN 及协议；非 relay 显示 P2P；只有地址和网络信息确认同 LAN 才显示 LAN。
