# SimpleRC 阶段报告

## 阶段 1：工程、单页 UI、角色状态和权限引导

- 完成内容：Android 工程；单 Activity、无滚动 Compose 主页面；角色切换；无障碍和 Android 13+ 受限设置引导；`connectedDevice` 待机前台服务及通知停止入口。
- 修改范围：`android/` 基础工程、阶段 0/1 文档。
- 构建状态：未构建。当前机器缺少 JDK 与 Android SDK，`:app:assembleDebug` 在 Wrapper 启动前终止。
- 未完成项：信令未接入，因此待机服务不会显示在线；凭据只显示未生成。
- 新增决策：工具链版本、标准预编译 WebRTC AAR、冷启动不恢复、无障碍最小权限、单页紧凑布局。
- 已知风险：源码尚未经编译器检查；待机 FGS 厂商行为和受限设置入口需真机验证。

## 阶段 2：设备身份、凭据、信令与租约

- 完成内容：Android Keystore ECDSA 设备身份、AES-GCM 本机秘密存储、凭据规则与安全随机验证码、WSS 客户端和消息基本校验；Go 配置、Argon2id+pepper、Redis 原子租约/续约/归属释放、多维限流基础组件、PostgreSQL 迁移。
- 修改范围：`android/.../security`、`credentials`、`signaling`、`server/`、文档。
- 构建状态：未构建。JDK/Android SDK/Go 均不可用。
- 未完成项：HTTP 设备注册、固定 ID 更新、随机凭据注册和 WebSocket 会话路由尚未接入 `main`，因此本阶段为部分实现，不能用于真实配对。
- 新增决策：P-256 ECDSA 设备身份；AES-256-GCM 本地加密；Argon2id 64 MiB/3 次/2 lanes；凭据代次用于撤销恢复令牌。
- 已知风险：缺少编译验证；PAKE 未采用，在线验证仍会向认证服务暴露验证码，需要 TLS、限流和统一失败时序保护。
