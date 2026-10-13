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

## 阶段 5：内部音频与静音

- 完成内容：音频捕获接口、标准 AAR 的明确不可用实现、静音待确认状态机、sender 分离和采集停止顺序、Linux/WSL2 构建方案、补丁设计源、许可证和校验值生成规则。
- 修改范围：`audio/InternalAudio.kt`、`webrtc-patch/`、可行性/状态/决策文档。
- 构建状态：未构建；按授权未执行 WebRTC 构建，且本机无 JDK/SDK。
- 未完成项：尚未固定上游 WebRTC SHA；补丁文件目前是逐文件变更规范而非可直接 `git apply` 的最终行级 diff；没有补丁 AAR 或真实 AAR SHA-256。内部音频运行时明确不可用。
- 新增决策：Manifest 不提前声明音频权限；标准 AAR 使用不可用实现；只有远端确认后进入停传确认态。
- 已知风险：最终 ADM 补丁、恢复发送和 RTP 统计必须在获授权的 Linux 构建及双机环境验证。

## 阶段 6：弱网、TURN、生命周期与清理

- 完成内容：120 秒有限恢复状态机、信令/ICE/媒体/授权/用户停止分类、指数退避抖动、控制门禁、候选路径分类、自建 Coturn/PostgreSQL/Redis Compose 模板、TLS/TURN 带宽文档、后台限制设置入口。
- 修改范围：`recovery/`、`webrtc/IcePath.kt`、`deploy/`、MainActivity/UI、部署与恢复文档。
- 构建状态：未构建；本机无 JDK/SDK/Go/Docker。未运行 `docker compose config`，未启动任何服务。
- 未完成项：恢复状态机尚未接入真实 PeerConnection；getStats 选中候选关联解析未完成；通知质量/路径动态更新和锁屏接收处理未接通。
- 新增决策：用户停止和 projection 停止直接终止；恢复前同步状态；LAN 必须额外确认，host candidate 本身不足以判断。
- 已知风险：Compose 模板尚未经 Docker 解析；Coturn 镜像/端口和目标部署网络需运维审查。

## 阶段 7：构建、打包、文档和验收

- 完成内容：审查指南、阶段 1–7 人工双机验收清单、问题反馈模板、错误码、依赖许可清单和需求条款对照表；静态解析 Android XML；复查无测试/设备钩子及音频权限未提前声明。
- 修改范围：`README.md`、`docs/REVIEW_GUIDE.md`、`ACCEPTANCE_CHECKLIST.md`、`REQUIREMENTS_TRACEABILITY.md`、`ERROR_CODES.md`、`LICENSES.md`、状态/决策/进度。
- 构建状态：未构建。再次执行 `:app:assembleDebug`，仍因 `JAVA_HOME` 未设置且无 Java 在 Wrapper 启动前终止。没有 APK。Go/Docker 不存在，未运行 `go build`、`go vet` 或 `docker compose config`。
- 未完成项：阶段 2–6 对照表列出的端到端接线和服务端路由；WebRTC 音频补丁构建；所有实机验证。
- 新增决策：最终交付以审计真实性为先，缺失链路保持“部分实现/未实现”，不生成空 APK 路径或虚构构建结果。
- 已知风险：Kotlin/Go/Gradle/Compose 均未经编译器验证；依赖可解析性、Docker 模板和运行时 API 可能仍有错误。

## 阶段 7 后续构建与部署修复

- 完成内容：安装 JDK 17、Android SDK、Go 与 Docker；修复 MediaProjection 可空返回的 Kotlin 编译错误；补齐 Go 依赖校验；增加后端容器；服务器部署数据库、Redis 和健康检查服务。
- 修改范围：`android/gradle.properties`、`ScreenShareService.kt`、`server/go.mod`、`server/go.sum`、`server/Dockerfile`、`deploy/docker-compose.yml` 和状态/构建/部署文档。
- 构建状态：Android `:app:assembleDebug` 成功；Go `build`/`vet` 成功；Compose 配置解析成功；服务器健康检查成功。未运行测试、未接触手机。
- 未完成项：端到端远控链路、后端业务路由、WebRTC 音频补丁、域名/TLS、公网 WSS 和 TURN。
- 新增决策：中文路径显式允许；服务器只部署回环健康基础；服务器无法访问 Go 模块代理时使用本机交叉编译的静态二进制。
- 已知风险：APK 虽已编译但核心会话仍未接线；服务器部署不能供客户端真实配对；所有设备行为仍待人工双机验收。

## 阶段 2–7 完整化修复与最终部署（2026-10-13）

- 完成内容：补齐设备注册、凭据发布、固定 ID、WebSocket、单控制者租约、本机确认、确认超时、短期 TURN 凭据；接通 MediaProjection、WebRTC 视频、SDP/ICE、DataChannel、无障碍控制、远端渲染、实际内容区域坐标、方向和基础恢复；部署公网 HTTPS/WSS、Coturn 与 APK 下载。
- 修改范围：`android/app` 会话/UI/服务/状态；`server/internal/api`、配置和入口；`deploy`；状态和审查文档。
- 构建状态：Android `:app:assembleDebug` 成功；Go build/vet 成功；Compose config 成功；公网健康 204。未运行测试，未接触手机。
- 未完成项：AudioPlaybackCapture 补丁 AAR、真实停传音频、H.264 优先/动态质量、完整统计、媒体停滞检测、DataChannel 执行回执、Android 14 单应用范围拒绝。
- 新增决策：TURN 使用 3480 UDP/TCP 和 5350 TLS；连接路径从选中候选对事件生成；废弃 lightremote 项目按用户指示删除，证书续期迁入 SimpleRC。
- 已知风险：全部 Android 行为均未双机验证；WebRTC 音频构建仍受重启后 WSL2 初始化阻塞。
