# 部署与 TURN

正式环境需要用户提供信令域名、TURN 域名、公网 IP、TLS 证书、PostgreSQL/Redis 密码和至少 32 字节 credential pepper。模板中的 `example.com` 和 `REPLACE_*` 不是可用部署。

信令必须通过 HTTPS/WSS。Coturn 提供同一自建服务的 STUN、TURN/UDP、TURN/TCP 和 TURN/TLS；UDP 通常延迟最低，TCP/TLS 是受限网络回退且可能增加延迟。TURN 凭据由服务端用共享密钥签发短期用户名/密码，APK 不保存永久 TURN 密钥。

TURN 会承载完整媒体流量。粗略出口带宽：`并发中继会话数 × (视频目标码率 + 音频码率 + 约 15% 协议/重传余量) × 2方向`；月流量再乘活跃秒数并换算字节。720p/30fps 不能用固定码率承诺，必须按实际统计和峰值容量规划。

2026-10-12 已在用户提供的 Ubuntu 服务器部署 PostgreSQL、Redis 和后端健康检查基础，三项容器健康，后端仅绑定服务器 `127.0.0.1:18080`。运行密钥由服务器生成并保存于受限环境文件，不进入仓库。该实例还没有设备注册、配对或 WebSocket 业务路由，因此不是可用的公网信令服务。

Coturn 被放入 Compose 的 `turn` profile，默认不会启动。取得信令域名、TURN 域名和有效 TLS 证书后，替换示例 realm/证书并显式启用；生产还应开放 3478 UDP/TCP、5349 TCP 和配置的 UDP relay 端口范围，并配置防火墙、监控、备份和证书续期。

路径显示必须来自选中 candidate pair：relay 显示 TURN 及协议；非 relay 显示 P2P；只有地址和网络信息确认同 LAN 才显示 LAN。
