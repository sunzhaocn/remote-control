# 0.3.13 完整画面与后台运行修复

用户反馈：持续滑动后，远程画面逐渐只剩放大的局部；希望控制端与被控端切到后台后保持运行。本轮按现有 Android 10 及以上支持范围修复通用链路，不依赖特定手机型号。截图与采集尺寸切换错误相符；没有连接手机复现，以下区分代码缺陷、自动验证与待实机确认内容。

## 画面范围

旧 `applyVideoSenderPolicy` 在拥塞、编码或渲染压力增大时，直接改变 `ScreenCapturerAndroid` 的 VirtualDisplay 和 SurfaceTexture 尺寸。现在 `ScreenCapturePipeline` 保持长边至多 1280 的完整屏幕采集，仅调整 WebRTC 输出尺寸、帧率与码率。画质调整不调用系统投屏 resize；只有旋转或真实屏幕尺寸变化需要调整基础采集。触控仍使用完整物理屏幕的几何信息，发送画面像素不改触控坐标。

同时修复 WebRTC Java 包装中的真实缺陷：Android 12+ 原代码 resize 后把同一个 Surface 对象传回 `setSurface`，Android 会忽略该对象未变的调用。现在创建新包装进行重绑定，成功后释放旧包装，失败则释放新包装并保留原资源所有权。Android 10/11 仍走显示重建分支；Android 12+ 保留原显示对象，符合 Android 14+ 同一录屏授权只创建一次显示的限制。控制端关闭有设备兼容风险的 SurfaceView 固定尺寸硬件缩放，使用 EGL 与等比完整显示。

视频适应仍保留，较弱设备或网络可降低输出清晰度和帧率；为了保持画面范围稳定，底层采集缓冲区不会再随网络一起缩小。四种 CPU 架构继续保留，没有裁掉旧设备架构或更换原生编解码库。

## 后台与恢复

- 两端继续以可见通知的前台服务运行，CPU 唤醒锁限定到对应服务职责，并有超时和续期；会话按当前网络请求 Wi-Fi 性能锁，结束、切换角色或服务销毁时释放。
- 控制端锁屏不再主动结束会话；仅应用可见且会话活动时请求保持屏幕亮起。被控共享为避免自然熄屏持有会话显示锁，不唤醒或解锁设备。
- 被控待机允许系统通过 STICKY 重建并重新发布凭据；录屏授权和远程会话不重放。待机断网持续退避重试，网络恢复后重新上线。
- 活动会话仍遵守原 15 秒恢复窗口和租约。超时或被控锁屏等可恢复情况先结束录屏、撤销授权、清理旧连接，再回到待机；新的共享仍需被控端确认。TLS、设备鉴权和权限错误不会无限自动重试。
- 两种角色和控制画面的导航菜单都有“后台运行设置”，显示省电限制状态，用户点击后才发起电池豁免申请，并提供通知、应用信息入口。

Android 和厂商仍控制后台进程、录屏、Wi-Fi 锁与电池策略。Android 14+ Wi-Fi HIGH_PERF 已映射至 LOW_LATENCY，不能据此保证后台 Wi-Fi 一定获得该优先级。手动强行停止应用后需重新打开；被控锁屏或系统收回录屏授权后需重新授权，不能承诺所有手机永不被杀后台。

## 验证与测试交接

WebRTC Java 包装构建成功，三份补丁在固定上游重放通过；直接编译实际 capturer 源码的 10 项 Android 行为桩测试覆盖 API 29/30 的显示重建，31/32/34/35 的 Surface 重绑定，以及创建、resize、绑定失败的资源释放。注回旧同对象绑定后测试失败，确认用例能够检出原问题。四 ABI 原生库及 25 个音频 Java 类保持逐字节一致。记录：`../webrtc-patch/VIDEO_BUILD_0.3.13.json`、`../artifacts/CAPTURE_SURFACE_TEST_0.3.13.json`。

主应用构建 1 分 33 秒通过，155 项单元测试零失败/错误/跳过，lint 零错误、23 项提示。APK `../artifacts/SimpleRC-0.3.13-debug.apk`，versionCode 16，72,165,266 字节，SHA-256 `d2e9d5193fb00c62fa6fc52f5789e4865f38b808118de25b654abc2765ab81cc`；签名与 0.3.12 一致。自动检查与最终 APK 信息见 `../artifacts/BUILD_0.3.13.json`。新增回归覆盖连续画质变化不 resize、旋转与恢复、重复配置、失败重试、资源归属、后台恢复和用户停止边界。自动测试不等于厂商系统的图形与后台实测；本轮未安装或操作手机，未修改或部署服务器，继续使用已经部署的 0.3.12 配套后端。

两端覆盖安装 0.3.13 后重新连接，建议检查：

1. 连续滑动列表与桌面，再静置恢复；画质可以变化，但屏幕四角和完整范围不能丢失，点击应仍对齐。
2. 横竖屏往返多次；完整画面恢复，导航和边缘滑动仍正确。
3. 两端分别允许后台运行并按厂商设置允许自启动、锁定最近任务；切换其他应用后返回，观察会话和通知。控制端锁屏后解锁观察恢复；被控端锁屏后应停止共享并返回待机，再次共享需确认。
4. 待机时断网超过 30 秒再恢复，观察自动上线；用户主动停止后不应自行恢复。活动会话长时间断网后不能继续使用旧授权。

实现依据：[Android 录屏与尺寸变化](https://developer.android.com/media/grow/media-projection)、[Android 省电限制](https://developer.android.com/training/monitoring-device-state/doze-standby)、[AOSP VirtualDisplay](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/display/VirtualDisplay.java)、[WebRTC ScreenCapturerAndroid](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/ScreenCapturerAndroid.java)、[WebRTC SurfaceViewRenderer](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/SurfaceViewRenderer.java)。
