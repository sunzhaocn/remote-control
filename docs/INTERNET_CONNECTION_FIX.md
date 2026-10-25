# 公网建立超时：TURN防火墙修复

用户确认症状为公网连接建立超时，本地连接正常。检查发现Coturn实际监听3480/UDP、3480/TCP、5350/TCP，UDP relay范围49261–49360；UFW INPUT默认DROP，却仅放行旧服务3478及49160–49200范围。本机STUN UDP/TCP/TLS均成功，修复前公网三种传输均无有效响应。

在10.2.20.7这个实际监听地址上新增四条精确UFW规则，保留其他规则及服务，不重启后端、数据库、Redis或Coturn：

```sh
sudo ufw allow proto udp to 10.2.20.7 port 3480 comment 'SimpleRC TURN UDP'
sudo ufw allow proto tcp to 10.2.20.7 port 3480 comment 'SimpleRC TURN TCP'
sudo ufw allow proto tcp to 10.2.20.7 port 5350 comment 'SimpleRC TURN TLS'
sudo ufw allow proto udp to 10.2.20.7 port 49261:49360 comment 'SimpleRC TURN relay'
```

原UFW规则已备份到服务器 `/opt/simplerc/shared/firewall-backups/turn-3fea234510f9447eb0d3b8a557ce66b9`。如需回滚，删除这四条精确规则即可；全量备份仅用于恢复核对，不应覆盖后来无关的防火墙变更。

修复后从电脑公网实际进行带短期凭据的TURN认证、分配、CreatePermission、Send/Data indication及外部UDP peer双向echo。UDP、TCP、TLS三种客户端到服务器的传输均通过；分配端口分别49313、49272、49341。TLS使用正常证书验证。测试不输出永久或短期密码，完成后释放测试分配。修复及检测回执见artifacts/TURN_FIREWALL_FIX.json、TURN_PROBE.json，服务器健康204。

TCP/TLS结果是客户端到TURN采用TCP/TLS、TURN到peer使用UDP，不是声称实现RFC6062的TCP relay。本次只确认从电脑公网到该服务器的可用中继，未运行手机运营商网络复测，也没有证明手机之间纯P2P打洞成功。客户端已有自动ICE候选和TURN配置，无需重建APK；两端结束旧会话后重连确认。

[WebRTC官方连接说明](https://webrtc.org/getting-started/peer-connections)说明STUN/TURN和ICE候选流程；[Coturn官方配置](https://github.com/coturn/coturn/wiki/turnserver)说明公开地址映射与relay端口范围。未来调整监听或relay范围时，主机防火墙、云安全组与Coturn配置必须保持一致。
