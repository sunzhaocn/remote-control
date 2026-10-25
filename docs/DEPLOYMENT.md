# 部署与 TURN

## 2026-10-17 后端版本核对与证书维护

线上运行二进制与本地最新构建完全一致，Go源码匹配构建快照，无需重新部署后端。当前release仍为 `/opt/simplerc/releases/20261016T111410Z-backend-0312`，运行SHA-256 `2e1e1341c42924201c5618aeeccec7c34aecd771102ea1a79dff7318bce6bd97`。0.3.34客户端改动无需后端升级。

现场发现公网证书已过期，标准TLS校验失败。自动续期的入口脚本仍引用迁移前的解释器目录；HTTP-01验证只在9443配置，80端口返回404；续期后的systemd Nginx reload命令也不适用于当前面板管理的Nginx。已改为现有Python解释器显式启动Certbot，新增仅匹配本机IP的80端口验证入口，并使用Nginx自身的reload命令。原有六小时续期timer保持启用、active，续期service实际运行成功。

新证书已续签，UTC有效期至2026-10-24 21:52:24。新增Certbot deploy hook，校验公私钥匹配后同步现有Coturn证书目录，并向PID1的turnserver发送SIGUSR2热加载。公网9443与5350正常TLS校验通过且证书指纹一致。后端、PostgreSQL、Redis、Coturn的容器ID、启动时间和重启计数均保持，没有重启容器。

最终验证：公网HTTPS healthz=204、本机healthz=204；未认证WSS路由按预期401；公网STUN UDP/TCP/TLS绑定均成功；Nginx配置验证通过，TURN实际提供新证书。未进行双手机会话或带认证TURN分配/中继测试，不把STUN成功写成媒体中继实测。

备份与前后配置保存在 `/opt/simplerc/shared/backups/20261018T064542Z-certificate-renewal`（0700）。本地脱敏回执 `../artifacts/SERVER_MAINTENANCE_0.3.34.json`，分项证据见其中evidence。未上传APK或修改运行密钥。

## 2026-10-16 0.3.12 配套后端更新

11:14 UTC 已更新信令服务。新版本目录 `/opt/simplerc/releases/20261016T111410Z-backend-0312`，`/opt/simplerc/current` 已指向该目录；Compose 显式固定镜像 `simplerc-server:0312-20261016t111410z`。运行二进制 11,944,096 字节，SHA-256 `2e1e1341c42924201c5618aeeccec7c34aecd771102ea1a79dff7318bce6bd97`，与本地构建及上传文件一致。数据库无 schema 迁移，只重建 server 容器，PostgreSQL、Redis、Coturn 和其他应用未重启。

实际连接记录确认后端看到代理 `::ffff:172.19.0.1`，因此 `/opt/simplerc/shared/runtime.env` 新增 `TRUSTED_PROXY_CIDRS=172.19.0.1/32`，权限从 0644 收紧为 0600。Nginx 覆写 `X-Real-IP`，后端仍仅监听宿主回环映射 `127.0.0.1:18080`。没有更换密钥或修改中继网络参数。

通过的检查：公网正常 TLS 与本机健康均 204；真实 PostgreSQL/Redis 上的注册、配对所有权、连接确认、信令双向转发、租约续期、撤销/拒绝后重连和旧连接替换共 8 项；隔离测试 IP 的限流与独立新设备连接共 2 项。实际 Redis 的 IP/设备/目标失败计数和 TTL 已核对，7 个测试设备及相关记录已按身份定点清理。TURN UDP/TCP/TLS 均完成公网认证、分配与双向回传。检查结束时服务重启计数 0，后端日志无 panic、fatal 或 SERVER_ERROR；这不代替双手机媒体、触控或延迟测试。

备份目录 `/opt/simplerc/shared/backups/20261016T111410Z-backend-0312` 保留旧二进制、旧 Compose、原运行配置和 PostgreSQL 自定义格式快照，快照目录清单已验证可读（未执行恢复演练）。旧镜像也有 rollback 标签。需要回退时，在服务器执行：

```sh
sudo /opt/simplerc/shared/backups/20261016T111410Z-backend-0312/rollback.sh
```

脚本只恢复运行配置、旧 server 容器及 current 链接，不恢复或覆盖现有数据库。当前版本日常启动使用显式项目名和运行配置，避免产生另一套卷：

```sh
sudo docker compose -p deploy --env-file /opt/simplerc/shared/runtime.env -f /opt/simplerc/current/deploy/docker-compose.yml up -d --no-deps --no-build server
```

完整证据：`../artifacts/DEPLOYMENT_0.3.12.json`、`BACKEND_LIVE_PUBLIC_0.3.12.json`、`BACKEND_LIVE_LIMIT_0.3.12.json`、`BACKEND_PROBE_CLEANUP_0.3.12.json` 和 `TURN_PROBE_0.3.12.json`（后四者均在 artifacts 目录）。客户端 APK 保留本地交付方式，本轮没有上传新 APK；用户两端安装 0.3.12 后重新连接测试。

## 公网建立超时修复

发现实际Coturn 3480/UDP、3480/TCP、5350/TCP与49261–49360/UDP未被UFW放行，旧规则只有3478及49160–49200。已备份并加入仅面向实际监听地址10.2.20.7的四条规则，其他规则/服务保持。修复后电脑公网TURN UDP/TCP/TLS认证、分配和真实双向echo全部通过，健康204；无需更新APK，手机需要结束旧会话后重连。详情和回滚位置见INTERNET_CONNECTION_FIX，未把服务器探测称为手机纯P2P已通过。

## 2026-10-13 0.3.7 下载交付

新增 `/opt/simplerc/public/SimpleRC-0.3.7-debug.apk`，不替换旧版。临时上传核对哈希后原子发布，随后从公网完整读取并对照本地：81318592字节，SHA-256 `2d59288e9eb802e2e8979e0743d9a48d9ad43585a4b12d93fa54c33154b0280c`。公网 [下载0.3.7](https://49.233.43.167:9443/simplerc-download/SimpleRC-0.3.7-debug.apk) GET/HEAD200，首尾Range206与本地内容一致，发布后健康204。没有重启或更新后端、数据库、Redis、Coturn；其现场低负载、eth0已有fq_codel，客户端新增反馈无需后端协议变更。未安装或测试手机。

## 2026-10-13 0.3.6 下载交付

为无 ADB 的手动安装新增 `/opt/simplerc/public/SimpleRC-0.3.6-debug.apk`，保留旧版文件。先临时上传并验证 SHA-256，再通过临时目标文件原子更名发布。72122760 字节；SHA-256 `f23283634064eef44acccf04cac5c9f458bc3486cb4677f9301a7de537ecd1c4`，本地、服务器与公网完整下载一致。公网 `https://49.233.43.167:9443/simplerc-download/SimpleRC-0.3.6-debug.apk` 的 GET/HEAD 200、长度与首尾 Range 206 内容通过验证。未升级或重启后端、PostgreSQL、Redis、Coturn，健康仍 204；客户端新 DataChannel 不需要后端信令变更。

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
