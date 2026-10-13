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

## 阶段 3：屏幕采集、WebRTC、DataChannel 与基础触控

- 完成内容：`mediaProjection` 前台服务和一次性授权持有；WebRTC 工厂、硬件编解码工厂；控制命令会话/序号/时效/几何版本/坐标校验；无障碍点击、长按式点击、滑动与三项全局操作。
- 修改范围：Manifest、`capture`、`webrtc`、`control`、无障碍服务、协议文档。
- 构建状态：未构建，JDK/Android SDK 不可用。
- 未完成项：MediaProjection Activity 授权入口、ScreenCapturerAndroid 到 PeerConnection 的完整接线、SDP/ICE 业务路由和远端渲染 UI 尚未接通，因此视频会话不能实际建立。
- 新增决策：可靠控制与短寿命滑动分通道；消息不是成功回执；授权 Intent 单次消费。
- 已知风险：标准 AAR API 尚未经编译核对；服务当前只持有 projection，VirtualDisplay 由后续 capturer 接线负责。

## 阶段 4：方向、坐标、输入法与导航

- 完成内容：实际内容矩形到归一化坐标的逆变换、0/90/180/270 度处理、远端捕获区域到屏幕映射、全路径越界取消、几何版本、方向请求协调器、应用内展开式三键导航组件、输入法边界说明。
- 修改范围：`geometry/`、`ui/FloatingNavigation.kt`、`docs/GEOMETRY.md`。
- 构建状态：未构建，JDK/Android SDK 不可用。
- 未完成项：组件尚未接入真实远程视频页面；方向/Surface resize、导航拖动和输入焦点辅助未形成端到端功能。
- 新增决策：编码分辨率不参与触控；路径任一点越界取消；不读取节点内容；不强制本地键盘。
- 已知风险：多窗口可能忽略方向请求；真实系统栏/折叠屏/IME 几何需双机验证。
