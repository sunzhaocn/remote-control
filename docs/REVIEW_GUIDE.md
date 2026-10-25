# 0.3.37 设备过载保护与复查

新策略增加设备过载时的30→24fps保护，保留短边1080封顶、全屏比例及稳定采集镜像。持续编码、解码、绘制或显示积压触发保护，严重压力可在一个有效样本内响应；持续健康后缓慢恢复30fps。网络拥塞继续走原有码率保护，设备压力不直接压低码率。

普通设备压力需要至少两个有效样本，间隔至少750ms；超过1500ms的采样间隔中断连续证据。恢复需要至少八个健康样本且健康持续至少8秒，同时核对真实帧节奏、处理余量和网络状态；刚恢复后的普通退让至少驻留3秒，严重压力可提前保护。未知、静态低帧率或单独残留的CPU标记不证明设备过载。

接线：VideoCadenceProtection独立维护帧率证据；InteractiveVideoPolicy只依据当前有效设备信号触发，持续编码队列满会阻止增加码率和画质试升。原始VIDEO_FEEDBACK的1500ms生命周期保持，网络压力期间不把接收帧率缺口单独归为解码/显示处理不足。设备保护本身不改帧尺寸、不直接降低码率。

RTP参数成功应用后，只有实际fps变化才调用SurfaceTextureHelper.setCaptureFrameRate，复用现有采集调度器，在updateTexImage前等待新节奏，按时取最新纹理，不追加历史帧队列。码率或活动状态变化不重置采集节奏。ScreenCapturePipeline在30→24→30过程中只更新输出fps，不重设VirtualDisplay尺寸。接收显示上限仍为30，不生成额外插帧。

新增25项测试：帧率状态机17项、完整策略集成6项、1080采集表面及RTP帧率接线2项；将旧“设备压力只观察”断言改为24fps主动保护。覆盖孤立波动、严重积压、快速/重复/倒序采样、长采样间隔、真实编码队列满、残留CPU标记、连续恢复、驻留时间、会话及路径切换。

Release/Debug/80%/88%四配置各58套件、650项测试通过，零失败/错误/跳过；Release/Debug lint零错误。154项构建输入核对通过，APK v2签名有效且沿用原证书。Manifest权限、WebRTC AAR及四ABI原生库与0.3.36一致，旧交付包未改动。

两端覆盖安装后选择“新策略”并断开重连；诊断中可见24fps设备保护及具体原因。默认策略仍保持原有30fps和库默认码率。未进行双机实测，实际帧率、清晰度和卡顿改善仍需设备验证。没有新增存储权限或视频磁盘缓存，服务器无需更新。

实机验收：同样屏幕和内容对比0.3.36与0.3.37，核对实际采集/发送/解码/显示fps、编码耗时、编码队列、发送排队、QP、丢包与1080实际编码尺寸。过载时应显示原因并切至24fps；网络单独拥塞不能被描述成设备过载，恢复应有连续健康证据，不能快速30/24来回切换。此版本允许过载时降低帧率，不能承诺全程30fps；24fps在不同屏幕刷新率下的节奏与实际改善仍待验证。

OVERLOAD_HANDLING_0.3.36.md是已有方案草稿；本次实现设备帧率保护，未采用其中90%尺寸、硬编尺寸降档或网络自动降fps方案。0.3.36的路径诊断修复继续保留。

构建回执 `../artifacts/BUILD_0.3.37.json`；复核 `python -X utf8 artifacts/verify-build-0337.py`。

# 0.3.36 路径诊断修复与复查

修复候选统计缺失时误标P2P、猜选succeeded+nominated候选对、缺失统计沿用旧路径三类问题。优先使用媒体RTP所属transport的selectedCandidatePairId；兼容唯一且无歧义的legacy selected=true，无法确认时明确显示路径未知。媒体与独立控制分别展示路径、候选类型、本机候选对ID和选择来源。

媒体可交换本机选中候选类型，遇到远端prflx时用新鲜会话反馈辅助判断，揭示中继时注明“对端报告”；缺乏对端证据时保留未知。对端反馈在使用时再次核验原始1500ms生命周期，接收时间不会延长截止时间。

候选对选择对显式悬空引用、错误类型、失败状态、transport归属冲突、多条传输路径歧义均返回未知。只有实际本地接口前缀匹配的host/host UDP对才标LAN。已知relay证据优先，远端candidate.protocol=udp不能证明远端至TURN的接入协议；该段未确认时保留待确认。路径身份包含候选ID、类型和协议，证据变化会失效旧反馈。会话建立、结束和采样缺失均清理旧路径证据。

新增39项测试：候选对选择20项、分类新增9项、媒体/控制诊断9项、反馈原始截止1项。二次审查修正多transport下legacy候选归类歧义，以及临期反馈被接收时间延长使用的问题。

Release/Debug/80%/88%四配置各57套件、625项测试通过，零失败/错误/跳过；Release/Debug lint零错误。152项构建输入核对通过，APK v2签名有效且沿用原证书。权限、WebRTC AAR和四ABI原生库与上次交付一致，旧包保持原样。

保留0.3.35短边1080封顶、全屏比例、30fps目标及原有码率策略。两端覆盖安装后断开重连，分别查看媒体路径与独立控制路径。尚未双机实测；本次修复诊断准确性，不代表已经确认真实公网路径或解决所有卡顿。服务器无需更新。

实机验收：两端同版本重连，分别核对媒体与独立控制的候选类型、候选对ID及选择来源；重连或候选对暂缺时不应残留P2P。远端prflx且无新鲜自报时应显示未知；收到本机候选relay自报后应注明对端报告。需要服务器侧取证时，TURN allocation只能证明分配存在，还须结合对应会话与流量，不能单凭分配日志认定媒体正在使用中继。RTT差值也不能单独证明路径。

项目中的OVERLOAD_HANDLING_0.3.36.md属于方案草稿；其90%尺寸、24fps及硬编尺寸降档未纳入此交付，当前交付仍遵循用户要求的1080p口径。

规范依据：[transport.selectedCandidatePairId](https://www.w3.org/TR/webrtc-stats/#dom-rtctransportstats-selectedcandidatepairid)、[本地relayProtocol](https://www.w3.org/TR/webrtc-stats/#dom-rtcicecandidatestats-relayprotocol)。candidate-pair.selected只是旧实现扩展，不假定所有WebRTC版本都会提供。

构建回执 `../artifacts/BUILD_0.3.36.json`；复核 `python -X utf8 artifacts/verify-build-0336.py`。

# 0.3.35 双端1080p发送策略与复查

两端使用同一包，默认/新策略统一在编码前缩放至手机1080p口径：短边最多1080，保留全屏比例，1080屏不变、720屏不放大。1440×3200→1080×2400，1220×2712→1080×2400，1240×2772→1080×2414，2160×3840→1080×1920；横屏交换宽高。不裁成16:9，操作、视频、静止精修和网络恢复都不再升回1.5K/2K。

技术接线：ScreenContentVideoPlan按min(width,height)/1080生成固定缩放比例；稳定采集镜像仍按原屏尺寸，CaptureActivityObserver在送入编码器前精确缩放。RTP维持scaleResolutionDownBy=1和DISABLED，避免编码器再叠加一轮尺寸适配。初始、旋转、清晰帧及恢复验证共用同一plan；GEOMETRY继续使用真实屏幕坐标。

新增13项1080流水线测试，覆盖1.5K/2K/4K、四旋转、低分屏、两策略RTP、纹理引用与异常回收、文本和视频尺寸一致；新增1项原屏2K与1080视频的角点/中点映射测试。修正倒数缩放的浮点误差：理论1080可能算成1079.9999999999998，旧代码会误降至1078；现先纠正4个ULP以内的舍入误差，再向下偶数对齐。旧88%回归的理论2112也不再误变2110。

Release/Debug/80%/88%四配置各55套件、586项测试通过，零失败/错误/跳过；Release/Debug lint零错误，APK v2签名有效且与0.3.34一致。149项构建输入冻结核对通过，无新增权限，WebRTC AAR和四ABI原生库保持，旧安装包未覆盖。

两端覆盖安装此版本后断开重连。原始屏幕坐标保持，发送画面按比例缩小；帧率目标仍为30fps，码率自适应。尚未双机实测，不保证设备持续过载或窄带时的实际帧率与画质。本次未操作手机、未更新服务器。

二次代码复查未发现新增打包阻断问题。既有厂商单应用捕获范围兼容边界仍待实机验证；当前输出是手机短边1080口径，长屏可以高于1920，不等于强制1920×1080。

构建回执 `../artifacts/BUILD_0.3.35.json`；复核 `python -X utf8 artifacts/verify-build-0335.py`。

# 0.3.34 优化、重构与二次复查

正式包的默认/新策略统一使用原屏宽高，操作、静止、视频和旋转不再主动缩小；移除默认策略1280长边限制与正式包90%比例。共用精确采集输出与RTP尺寸保护，默认策略保留库码率；诊断文字按500ms刷新，策略变化立即发布。修复旧WebSocket迟到回调、重复终止及创建期间关闭/重连的归属问题。

审查覆盖初始采集、旋转、显式纹理输出、发送器参数、诊断发布、信令连接归属及后端会话路径。二次复查修正默认策略的编码尺寸状态可能一直停留“等待编码”的显示问题；只刷新诊断，不重复提交发送参数。

新增5项原屏流水线测试覆盖4K、压力、横竖屏、旋转纹理复用、默认码率保留与奇数对齐；新增8项信令生命周期测试覆盖旧回调隔离、终止去重、首次回调发送、创建中关闭/重连、UTF-8超限与无效URL。

Release/Debug/80%/88%四配置各54套件、572项测试通过，零失败/错误/跳过；Release/Debug lint零错误。Go后端22项测试、vet和build通过，后端无需更新。APK签名有效并与0.3.33一致，可同包名覆盖安装；无新增权限，AAR及四ABI WebRTC库未改变。

原屏尺寸按编码要求向下偶数对齐，奇数宽高最多各差1像素；保留30fps目标、码率自适应和有界队列，不代表无损画质或设备必然达到30fps。两端安装此包后重新连接。未安装/操作手机、未双机或网络注入实测、未部署服务器。

已知既有边界：若厂商强制把整屏录制改为单应用共享，实际捕获范围与整屏输入映射尚未完成兼容验证，本次未改动该机制。发布原屏尺寸目标不保证所有硬件编码器支持所有屏幕尺寸；请在两端诊断中核对真实编码/解码尺寸。本轮自动检查没有发现新的打包阻断问题，不作全项目无缺陷或双机体验已达标的声明。

构建输入148项已冻结核对，测试、lint、签名、哈希及权限回执见 `../artifacts/BUILD_0.3.34.json`，复核 `python -X utf8 artifacts/verify-build-0334.py`。

# 审查指南（0.3.12）

当前检查与重构结果先读 `REFACTOR_0.3.12.md`、`STATE.md` 和 `../server/REFACTOR_REVIEW.md`。Android 140 项测试、lint 零错误及签名核验通过；后端 22 项本地测试、vet、交叉构建通过。未测试手机、未连接真实数据库、未部署后端，不能将历史设备结果当作当前版本已验证。

当前重点边界为 `SessionAudio/AudioControlState`、`CaptureConsent`、`CredentialRefreshSchedule`、`PeerClockEstimate`、`PendingInputAcks`、`RemoteViewport`，以及后端 `api/pairing.go`、`api/session.go`、`api/validation.go`。这些模块分别有针对已发现缺陷的回归测试；实际接线仍应结合 SessionMedia、SessionCoordinator 和 server.go 审查。

## 历史 0.3.5 检查表

先读 STATE、BUG_FIX_0.3.4、DEVICE_TEST_0.3.3/0.3.4 和 VIDEO_DIAGNOSTICS。0.3.4 有部分双机证据，0.3.5已构建并按后续授权双机覆盖安装，仅核对版本、未继续测试；不要把旧结果移作独立控制连接或新策略通过。

Android 下表简写路径的基目录是 `android/app/src/main/java/com/simplerc/app/`；服务端使用完整仓库路径。

| 模块 | 关键文件 | 审查重点 |
| --- | --- | --- |
| 版本、依赖、权限 | android/app/build.gradle.kts、android/gradle/libs.versions.toml、android/app/src/main/AndroidManifest.xml | 0.3.5/code8、debug签名、锁定AAR、无安装/测试构建钩子 |
| 凭据与模式 | security/DeviceIdentity.kt、security/SecretStore.kt、credentials/Credentials.kt、session/SessionCoordinator.kt | Keystore身份、AES-GCM一次保存、随机计时取消、固定恢复不随机注册、控制角色不发布、6–16/12–32配置化 |
| 凭据注册/限流 | server/internal/api/server.go、server/internal/security/password.go、server/internal/ratelimit/limit.go | 原子唯一归属、Argon2id+pepper、只有CONNECT验证失败计数、多维计数、retryAfterSeconds |
| 租约 | server/internal/lease/lease.go、server/internal/api/server.go | Acquire/Renew/Release归属、等待续租、确认补续、失效终止、旧连接不可删除新连接 |
| 本机授权/前台服务 | MainActivity.kt、capture/ScreenShareService.kt、service/StandbyService.kt、service/ControllerService.kt | 本机确认、每次新录屏授权、Intent单次消费、先startForeground再注册/采集、锁屏及onStop清理 |
| 保活/亮屏/通知 | service/ForegroundSupport.kt、service/NotificationChannels.kt、state/AppStateStore.kt | CPU与显示锁分开、安全期限/释放、无全局timeout修改、2秒通知取样、被杀不假在线/不静默录屏 |
| 无障碍门禁 | accessibility/AccessibilityStatus.kt、accessibility/RemoteControlAccessibilityService.kt、ui/MainScreen.kt | 设置+实际绑定、未满足不进被控、旧实例onDestroy隔离、无节点内容读取 |
| 视频和音频连接 | session/SessionMedia.kt、webrtc/WebRtcEngine.kt | 独立控制以外的媒体PC、SDP/ICE顺序、getStats实际 codec/profile、屏幕源true、直接目标尺寸、source/sender帧率、发送参数返回值 |
| 独立触控连接 | webrtc/ControlTransport.kt、session/SessionMedia.kt、server/internal/api/server.go | 数据专用PeerConnection/ICE/DTLS/SCTP、CONTROL_*类型、原会话归属、独立候选/恢复、停止两端资源，无视频轨道 |
| DataChannel 校验 | session/SessionMedia.kt、control/PeerClock.kt、control/ControlProtocol.kt、docs/PROTOCOL.md | 当前运行校验在SessionMedia；16KiB、队列32KiB、类型/时效/序号/几何/坐标、校准nonce、真实执行回执，旧指令不补发 |
| 坐标 | geometry/GeometryMapper.kt、geometry/OrientationCoordinator.kt、session/SessionMedia.kt、ui/MainScreen.kt | 真实视频内容矩形、留边/越界整段取消、编码与物理像素解耦；旋转采集动态接线仍缺口 |
| 音频静音 | session/SessionMedia.kt、webrtc/WebRtcEngine.kt、webrtc-patch/ | initial muted、SEND_ONLY空轨、START接轨/recording、OFF独立逐步清理、本机权威version/request、ACK5秒失败真实、无麦克风fallback |
| 质量诊断/策略 | webrtc/QualityStats.kt、model/MediaDiagnostics.kt、config/AppConfig.kt、MainViewModel.kt、server/internal/api/server.go | codecId关联实际格式、缺失未知、计数差分/重置、区间编码/解码/缓冲、累计冻结/限制时长、静止与滑动分开；策略下一会话同步 |
| 恢复 | session/SessionCoordinator.kt、webrtc/ControlTransport.kt、session/SessionMedia.kt、recovery/RecoveryStateMachine.kt | 运行协调器15秒独立截止、有限抖动、信令与媒体/控制独立恢复、租约门禁、终止/资源释放；完整重建未实现 |
| UI与菜单 | ui/MainScreen.kt、ui/FloatingNavigation.kt | 普通页面溢出才滚动、控制画面无常驻统计、主动诊断弹窗、菜单音频/断开、菜单横屏溢出滚动、按钮不穿透 |
| 部署 | deploy/docker-compose.yml、deploy/coturn/、server/internal/config/config.go | 自建TURN短期凭据、9443入口、3480/5350、只更新后端、不上传APK |

额外安全审查：独立SDP指纹固定绑定、180天固定ID回收、单应用捕获范围识别与完整输入/旋转组合尚未完成；逐条状态见 REQUIREMENTS_TRACEABILITY。历史辅助状态机不能替代运行接线证据。
