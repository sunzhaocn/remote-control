# 部署与 TURN

## 2026-10-13 后端修复更新（APK 未更新）

按用户澄清，仅更新后端 server 容器：Ping/合法消息延長读取期限、旧连接退出按归属清理。部署前在 shared 目录保留旧二进制和 api 源文件；未修改数据库、Redis、Coturn 或密钥。Compose config --quiet 成功；重建后正常 TLS 的公网 healthz 返回 204。

服务器后端 SHA-256：8d107fdbbb2c7734462e1806072463eb609375c2668a6f8cdbde185dd6cf45b0。服务器 SimpleRC-0.3.1-debug.apk 仍为 9ee002bf6d85c1299411fcb0b5dfd0ccc885a284b3d94ef21d0c64d990364e5c，0.3.3 APK 未上传。

当前环境使用用户提供服务器的公网 IP 证书；运行秘密只保存在服务器 `/opt/simplerc/shared/runtime.env`，不进入仓库。

信令必须通过 HTTPS/WSS。Coturn 提供同一自建服务的 STUN、TURN/UDP、TURN/TCP 和 TURN/TLS；UDP 通常延迟最低，TCP/TLS 是受限网络回退且可能增加延迟。TURN 凭据由服务端用共享密钥签发短期用户名/密码，APK 不保存永久 TURN 密钥。

TURN 会承载完整媒体流量。粗略出口带宽：`并发中继会话数 × (视频目标码率 + 音频码率 + 约 15% 协议/重传余量) × 2方向`；月流量再乘活跃秒数并换算字节。720p/30fps 不能用固定码率承诺，必须按实际统计和峰值容量规划。

2026-10-13 已部署设备注册、配对、WebSocket 信令和短期 TURN 凭据后端。Nginx 在 `https://49.233.43.167:9443/simplerc/` 反向代理回环后端，健康检查返回 204。APK 位于 `/simplerc-download/SimpleRC-0.3.0-debug.apk`。证书续期由 `simplerc-cert-renew.timer` 检查。

Coturn 已用 `turn` profile 启动，监听 3480 UDP/TCP、5350 TLS，relay 范围 49261–49360。共享密钥仅存在服务器环境文件。云安全组端口和真实中继仍须双机验证。

按用户明确指示，废弃的 `/opt/lightremote`、相关容器、镜像和 Nginx 配置已删除；证书工具、ACME webroot 和续期 timer 已迁移到 `/opt/simplerc/shared`。

路径显示必须来自选中 candidate pair：relay 显示 TURN 及协议；非 relay 显示 P2P；只有地址和网络信息确认同 LAN 才显示 LAN。


2026-10-13：0.3.0 debug APK 已上传并由公网入口返回 HTTP 200，Content-Length 71657210；服务器 SHA-256 与本地一致。

2026-10-13：0.3.1 修复版后端与 APK 部署，服务端只对 CONNECT 验证失败限流；APK SHA-256 9ee002bf6d85c1299411fcb0b5dfd0ccc885a284b3d94ef21d0c64d990364e5c，Content-Length 71765888。
