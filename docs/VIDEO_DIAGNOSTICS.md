# 视频卡顿分析与对照诊断（0.3.5）

用户已结束手机测试。以下分清静态事实、既有统计和待验证假设；不以新参数已经编译声称卡顿解决。

## 已确认

- 旧代码没有 PeerConnection.setBitrate、发送端码率上限、明确降级偏好或 H.264 codec 顺序。工厂同时有硬件和软件，默认广告中软件列表在前；不能从工厂配置证明最终 VP8/H.264 或硬件/软件。
- 视频源 createVideoSource(true) 已是屏幕共享；VirtualDisplay直接使用比例正确的缩小尺寸，SurfaceTextureHelper纹理在线程中传递，不在主线程逐帧转 CPU Bitmap/I420。所选 AAR 的 startCapture 帧率形参未使用；新版本在 VideoSource 输出适配及 sender 参数中限制30fps。
- 已有实机统计多次 RTT 5–11ms、丢包0.0%，也观察到91/111ms尖峰；这只是采样，不证明全程无丢包、不足以确定编码或 Wi-Fi 唯一原因。P2P 表示直连路径，不保证 Wi-Fi 排队、设备调度和帧处理延迟恒定；ICE RTT也不是完整画面延迟。
- 触控原来走 DataChannel、视频走 RTP，但共用 PeerConnection。0.3.5将控制连接独立，避免共享 SCTP/媒体连接层的排队；仍共享网络、CPU和内存。
- 当前滑动按完整手势在松手后发送，远端以600ms合成执行。它不等同商业软件的连续拖动输入；此限制可能影响主观操作体验，不拿网络 RTT 掩盖它。

## 尚未确认的假设

码率爬升不足、带宽降级、软件编码/CPU、接收缓冲/解码、Wi-Fi拥塞、省电限制各自都有可能。未取得 encode/decode/limitation/freeze 统计，不能按猜测给确定概率或认定 VP8必然软件。没有调整接收缓冲、关闭重传或加入 undocumented field trials。

## 新策略与默认策略

在两端同一0.3.5 APK中，控制端打开“诊断与策略”，选择默认/新策略，断开后重新连接；新的本机确认和录屏授权仍必须每次完成。服务器将选择绑定会话发给双方。当前连接不热切换。默认保留库编解码和码率；新策略见 DECISIONS D-041：2Mbps起始、0.3Mbps最小、不设置应用视频上限（覆盖初始6Mbps）、30fps、保帧率，H.264可用硬件优先并保留 VP8回退。默认数值是工程起点，未经实机证明最优。

H.264仅改变真实能力列表顺序，原有双方 profile 匹配交给 libwebrtc；不能伪造对端 profile，不能删掉所有回退。实际 codec/profile 来自 RTP stats关联codecId，编码/解码实现来自对应实现字段。通知和主动打开的面板均如实显示，缺失是未知。高效编码标志不能单独证明硬件。

## 用户下次手工对照

1. 同两机同网络、相同屏幕内容、同音频开关，默认策略先观察静止10秒，再连续滚动20秒；新策略按同样流程。不要在测试期间在活动被控端执行普通uiautomator dump。
2. 发送记录：采集/发送FPS、实际kbps/目标kbps、编码分辨率、codec/profile、encoderImplementation、powerEfficientEncoder、平均编码ms、qualityLimitationReason及none/cpu/bandwidth/other累计秒、发送丢帧（库无此字段则未知）。
3. 接收记录：接收/解码/渲染FPS（不相互冒充）、码率、codec/profile/decoderImplementation、高效解码、接收丢帧、冻结次数/累计时长、区间平均抖动缓冲ms、平均解码ms。
4. 网络记录：媒体和独立控制的RTT/实际候选类型、丢包、估计可用带宽。持续变化时发送低帧率+cpu限制才支持编码/采集瓶颈判断；bandwidth限制支持带宽分配判断；发送正常但接收冻结多继续查解码/缓冲/丢包。
5. 第一份无差分基线、计数重置、缺字段均未知。静止FPS低属正常可能行为；不能以总freeze计数跨会话比较，应比较同窗口增加量。发送丢帧缺失不计算capture-encode差值冒充。

控制画面平时不显示传输数据；系统通知保留统计，详细面板仅主动打开。复杂诊断应以调试日志为补充，通知可能因厂商布局省略长文。日志只含聚合指标，无凭据、SDP、地址或录音。

达到商业软件级体验需再验证编码硬件、帧处理/排队、实际端到端延迟、旋转/弱网、热限制及连续手势。现有数据不能承诺达到 ToDesk 的流畅度。

依据：[W3C Stats](https://www.w3.org/TR/webrtc-stats/)、[WebRTC RtpParameters](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/RtpParameters.java)、[默认编码工厂](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/DefaultVideoEncoderFactory.java)、[ScreenCapturerAndroid](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/ScreenCapturerAndroid.java)。本地AAR API/关键字节码另已核对，以上默认值不是规范要求。
