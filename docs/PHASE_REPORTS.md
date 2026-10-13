# SimpleRC 阶段报告

## 2026-10-13 第二轮实测修复：0.3.3

- 原因：用户实测为 0.3.1；0.3.2 外层固定高度仍挤压。保存依赖网络 ACK 且失败回退随机；SDP 设置回调未触发 Answer；ICE 过早添加；录屏可能早于进入前台。服务器 Ping 不延长读取期限，旧 socket 清理新配对。
- 完成内容：整页滚动/版本显示、本机保存与在线注册分离、一次加密提交、连接 generation/串行状态、操作超时/错误代码、SDP/ICE 顺序、服务拥有启动顺序、服务器心跳及归属清理。
- 修改范围：UI/凭据与存储/会话与信令/两种前台服务/服务器 api/文档，文件见 BUG_FIX_0.3.3。
- 构建状态：Android assembleDebug 成功；Go build/vet 成功；APK 0.3.3 code 6，SHA-256 219f6661225797bb3120f8ee6fe5ff8e392cca6117c0c26d76f3c269053d742d。
- 部署状态：仅重建 server 容器，保留旧二进制和源文件作为回退；Linux amd64 SHA-256 8d107fdbbb2c7734462e1806072463eb609375c2668a6f8cdbde185dd6cf45b0 与服务器一致；Compose config --quiet 成功；正常 TLS 健康检查 204。服务器 0.3.1 APK 校验值仍为 9ee002bf6d85c1299411fcb0b5dfd0ccc885a284b3d94ef21d0c64d990364e5c，新 APK 未上传。
- 未完成项：缺手机本次失败完整代码和阶段；全部修复待原双机复测，不标“我已验证”。
- 新增决策：D-036–D-040，允许后端更新，APK 留本地。
- 已知风险：本机保存不代表服务器占用检查成功；旧版未成功保存的值需重新输入；后端重建暂时掉线。
- 人工复查：顶部 0.3.3、离线保存、冷启动模式、整页滚动、注册、45 秒以上待机、录屏后画面建立、断开再连接。

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

## 2026-10-13 阶段 5 补丁构建与音频接入补充报告

- 完成内容：固定 GetStream WebRTC Android 1.3.10 的提交，生成并应用最小 AudioRecordFactory 行级补丁，在 WSL2 Ubuntu 24.04 构建四 ABI AAR；Android 接入 AudioPlaybackCapture、动态 microphone/mediaProjection 前台服务类型、独立权限、音频轨道、可靠 AUDIO_STATE 命令与 ACK、发送端脱轨并释放采集的真实静音流程。
- 修改范围：`webrtc-patch/`、本地 AAR、Android Gradle/Manifest、`WebRtcEngine`、`SessionMedia`、`MainActivity`、`ScreenShareService`、控制端声音按钮及交付文档。
- 构建状态：WebRTC AAR `assembleRelease` 成功（7 分 49 秒）；Android 0.3.0 `:app:assembleDebug` 成功。未运行测试，未接触手机。
- 未完成项：真实设备的捕获兼容性、音画同步和静音 outbound-rtp 统计需双机验收；平台禁止捕获的音源不会被绕过。
- 新增决策：AAR 直接保存于仓库且不用 LFS/付费制品服务；复用 ScreenCapturerAndroid 创建的同一 MediaProjection；音频拒绝不阻断视频；只有实际启用音频时才以 microphone FGS 类型启动共享服务。
- 已知风险：不同厂商对 AudioPlaybackCapture 和后台 FGS 的实现可能不同；恢复轨道无需重新协商的行为仍需双机验证。

## 2026-10-13 实测反馈 A–C 修复报告

- 完成内容：修复自定义模式仍发布随机凭据；重构验证失败限流；增加服务端等待秒数、客户端倒计时、有限抖动重连、建连/发布超时、重试/停止按钮；统一并实时显示 ID/验证码规则。
- 修改范围：凭据规则、会话协调器、信令客户端、被控 UI、服务端注册/验证/限流、状态与交付文档。
- 构建状态：Android 0.3.1 `:app:assembleDebug` 成功；`go build ./...`、`go vet ./...` 成功。未运行自动测试、模拟器或 ADB。
- 未完成项：第十位显示问题的具体厂商渲染原因缺少屏幕/日志证据；新版显示切换与横向编辑需原设备复测。限流命中接口的历史请求因旧服务没有结构化消息日志无法追溯。
- 新增决策：D-034。
- 已知风险：切换固定 ID 的服务器事务和倒计时 UI 已编译但未实机验证；旧客户端仍不会理解新的等待秒数字段。

## 2026-10-13 0.3.2 布局修复报告

- 完成内容：控制端连接表单、被控端设置表单增加垂直滚动；远程画面保持独立手势区域。
- 修改范围：`MainScreen.kt`、版本号和状态文档。
- 构建状态：Android 0.3.2 `:app:assembleDebug` 成功；未运行测试、模拟器或 ADB。
- 未完成项：等待用户在原设备验证小屏、字体缩放和键盘弹出时所有控件均可到达。
- 新增决策：D-035，用户明确调整原单页不可滚动约束。
- 已知风险：滚动仅用于设置表单；远程画面手势边界未改变。

## 2026-10-13 阶段1–7修复/双机基线与0.3.5交付报告

- 完成内容：按完整需求和原设备反馈先测试0.3.3并区分原因/推测；修复控制墙钟时效、限流角色隔离、租约确认续期、撤销回环、恢复门禁与停止清理；改简约二次元、自适应页面、选项卡无障碍门禁、通知许可消失、音频本机OFF双端同步、前台服务/通知统计/亮屏。用户之后结束测试，0.3.5移除常驻控制画面统计，补完整诊断、同APK默认/新策略下一会话生效，视频参数和H.264能力/profile兼容回退，控制独立PeerConnection/ICE/SCTP与有限队列。
- 修改范围：Android版本/Manifest、Activity/ViewModel、accessibility/、session/、state/、ui/、config/model/、webrtc/、service/、capture/；Go api/config/protocol；需求、状态、决策、可行性、回滚/协议/权限/构建、双机报告、视频诊断、审查指南、逐条对照及验收清单。
- 构建状态：0.3.5 assembleDebug成功；Go build/vet、Linux交叉编译成功；Compose解析/服务器仅后端更新成功、健康204。服务器APK哈希保持0.3.1，无上传APK。WebRTC AAR不重建。没有单元/仪器/go test/模拟器。
- 实机边界：0.3.3、0.3.4已按专项授权完成部分双向控制、3/10秒恢复、超过15秒失败/清理、音频OFF RTP计数/同步、通知、主动锁屏和32位保存检查；用户结束后不再访问手机。0.3.5新连接/策略/高级诊断/界面仅编译，不能沿用0.3.4结果声称通过。报告详见DEVICE_TEST_0.3.3/0.3.4。
- 未完成项：动态旋转/单应用范围识别、完整PeerConnection重建/停滞、成熟迁移/180天回收/敏感词、完整指纹绑定、连续实时手势与所有平台/路径/缓存组合仍有缺口；卡顿根因、听感及商业级流畅度未确认。
- 新增决策：D-036–D-042及最大码率修订；15秒独立截止、单调校准、权威音频许可、2秒通知采样、屏幕源true、目标30fps、起始2Mbps/最小0.3Mbps/max=null（用户覆盖6Mbps），原生profile协商保留VP8、策略会话绑定、触控与媒体隔离。
- 已知风险：两条连接增加协商/资源成本且仍共享Wi-Fi/CPU；高RTT时控制校准可能无法在20秒内就绪而明确失败；厂商可杀进程/限制废弃亮锁；通知长诊断由系统折叠，缺统计字段未知；新策略无应用最大码率会增加高带宽场景资源消耗，仍由拥塞/编码器调节。
- 需要用户复查：同两机两端0.3.5，静止/持续滑动分别做默认与新策略对照；先记录真实codec/profile/实现、编码/解码/冻结/缓冲/限制，再判断卡顿。完整人工清单和模块审查路径已更新。按用户“全部搞定后再提交”本轮统一提交，保留此前阶段提交以便回退。
