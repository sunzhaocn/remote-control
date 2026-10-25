# 0.3.15 两端滑动半截停住与防休眠

用户补充：0.3.14 仍会滑动卡住，而且被控手机本机也停在同一半截页面。这个证据把排查重点从视频帧传输转到系统触点是否正确释放。0.3.14 的视频修正保留，但不能据此声称已解决本次手势缺陷。

## 已定位的手势缺陷

旧 TouchGesturePump 在每段完成后立即发送下一段，即使没有新的移动目标，也发送 `willContinue=true` 的静止续笔。Android 10 的 MotionEventInjector 会先接管下一 stroke ID，再生成实际事件；静止续笔没有 DOWN、MOVE、UP，事件列表为空，系统返回取消但没有释放原来的指针。旧桥接收到取消就删除 stroke，逻辑层也关闭该手势，之后收到 UP 无法再释放它。被控页面停在拖动中间与这条缺陷链路吻合。

Android 的 GestureDescription 对位置执行像素取整，因此浮点坐标变化但落在同一整数像素也会触发该问题。初始静止长按的 continuing stroke 则只发出 DOWN，完成回调可能早于声明的保持时长，不能用回调代替真实经过的时间。

## 实现

- 非终止续笔只有在整数像素位置变化后才注入。静止心跳只刷新手势存活时间，不重复派发空手势。
- 独立定时器在没有新数据时将拖动（240ms）或长按（700ms）标记为超时取消，不再依赖持续空续笔驱动过期检查。没有派发在途时立即发送抬手；在途派发由下面的回调超时与清理逻辑处理。
- 显式长按用本机单调时钟保证最低保持时长；正常结束等待该时间，取消、换指、权限撤销不强制继续长按。
- 提取 ContinuedGestureDispatcher。平台接管续笔后即保留其 stroke ID；失败或回调超时，先尝试一次只含抬手的续接终止，再通知逻辑层结束。普通派发超时为声明时长加500ms，清理最多再等待500ms；晚到、重复、跨阶段回调不能改变新手势。清理不通过新的普通点击实现。
- 当前会话的 UP/CANCEL 在控制暂不可用时仍只能释放已有触点；权限关闭时转为取消，不能借此注入新坐标或新按下。
- 释放旁路遇到仅排队、尚未注入的手势时直接丢弃，即使它已收到普通 UP 也不重新执行；过期抬手不能启动一个新的长按或滑动。
- 保留窗口不息屏、前台服务、CPU 唤醒锁续期与会话显示锁。修复同时使用流量/VPN和局域网 Wi-Fi 时，Wi-Fi 不是默认网络而漏持性能锁的问题；监测所有可访问 Wi-Fi 的存在，服务结束后释放。

防休眠与强制最高频率不是同一能力。普通应用可请求 CPU 保持唤醒、屏幕保持和 Wi-Fi 性能锁，不能承诺所有机型永远最高 CPU/GPU 频率或关闭温控。没有采用忙循环、修改系统温控或强行解除锁屏。

## 验证范围

触控测试驱动新增独立时钟与可取消任务，覆盖静止心跳、同像素移动、正常抬手、失联、真实长按时间和取消。平台派发测试模拟 Android 的空续笔取消但指针仍按下、提前完成、丢回调、清理失败与迟到回调行为。自动验证结果与源码冻结清单记录在 `../artifacts/BUILD_0.3.15.json` 和 `../artifacts/TOUCH_RELEASE_INPUTS_0.3.15.json`。

这些是源码核对和自动行为验证，没有连接或操控真实手机。两端覆盖安装0.3.15并重新连接，先在列表中慢拖、停住不松手、继续拖动、松手，再快速连续上下滑动；被控本机页面应正常结束拖动，不留在半截。随后检查点击、长按、旋转、断线和亮屏。没有修改后端协议，服务器无需为此次修复更新。

## 官方源码依据

以下均固定 Android 10 的 android-10.0.0_r1 tag；新系统与厂商实现还需实际设备验证。

- [MotionEventInjector](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java)：空事件分支184–205行、续笔身份更新241–276行、位置变化与抬手事件376–420行。
- [GestureDescription](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/accessibilityservice/GestureDescription.java)：124–139行的像素取整及开始/结束标记。
- [AccessibilityService](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java)：732–742行采样，763–795行完成/取消回调分发。
- [Android 唤醒锁](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)、[固定性能模式的限制](https://developer.android.com/games/optimize/adpf/fixed-performance-mode)。
