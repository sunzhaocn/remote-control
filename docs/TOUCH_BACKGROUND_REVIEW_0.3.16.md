# 0.3.16 触控与后台运行复查

本轮按“输入归属 → 发送 → 校准与坐标校验 → 手势调度 → Android 派发 → 生命周期与后台资源”检查。保留0.3.15的同像素空续笔修复、防休眠及0.3.14的视频处理，不更换编解码器或修改后端。

## 发现与处理

1. **经过悬浮导航会截断滑动。** 原控制端在 MOVE 进入导航矩形时取消手势，菜单重新布局也会重启输入监听。现在仅 DOWN 判断导航区域，已经归属远控层的手指持续处理到结束；真正操作导航仍会取消远端手势。
2. **窗口失焦后心跳可能继续维持旧触点。** Activity 暂停或失焦不一定销毁渲染器。现在生命周期和窗口焦点事件立即结束本地触控心跳并发送 CANCEL；用代际标记阻止恢复时继续旧 MOVE、UP 或长按计时。会话与前台服务保持运行。
3. **到达时有效的输入可能在等待期间过期。** 将校准后的原始剩余寿命传入 TouchSample。尚未注入的过期动作直接丢弃；已按下的过期 MOVE 在最后注入位置结束，不继续移动到旧目标。已知触点的 UP/CANCEL 仍能释放，不能因过期阻塞抬手。
4. **抬手派发失败可能丢失唯一恢复信息。** 系统派发桥接增加有限抬手重试，并区分明确取消回调、拒绝、异常和无回调。只派发终止续笔，不用新点击清理；未确认释放时保留恢复位置。每次派发单独隔离回调，防止旧清理回调干扰新触点。
5. **旧整段滑动可绕过统一调度。** 当前界面已全部使用实时 TOUCH，删除未使用的旧派发入口；旧 SWIPE 消息返回失败 ACK，避免与实时手势互相取消。
6. **后台资源查询随每次诊断更新重复执行。** 服务先提取 CPU/Wi-Fi/屏幕资源需求，再过滤相同需求，避免每个触控回执和视频统计都在主线程查询系统服务。网络事件、亮灭屏事件和定期锁续租仍独立处理；旧会话撤销和角色切换仍及时释放资源。
7. **旧无障碍服务仍可能派发迟到清理。** 服务替换或销毁时关闭旧执行器、取消定时任务并隔离旧回调。旧实例在新实例接管后销毁，只停止自身任务；不会再向系统发可能取消新手势的迟到终止。正常销毁至多做一次即时抬手，不留下延迟重试。

## 验证与边界

最终构建1分19秒成功：217项单元测试全部通过，lint 0错误、23项提示；签名与0.3.15一致，89个输入哈希与交付APK逐一核对。

自动测试覆盖导航穿越、布局变化、暂停与快速焦点恢复，排队动作过期、过期 MOVE、UP 收尾，清理拒绝/异常/丢回调与旧回调隔离，以及高频统计去重和旧会话资源释放。最终构建、签名、测试、lint、源码哈希记录在 `../artifacts/BUILD_0.3.16.json`；输入清单为 `../artifacts/TOUCH_BACKGROUND_INPUTS_0.3.16.json`。

源码审查和行为测试不能代替真机触控、厂商省电限制及真实网络测试。本轮未连接或操控手机。终止消息同时发送失败时仍由独立触点超时收尾；未添加跨会话重发或无限重试。前台服务和唤醒锁不能绕过系统强停、录屏撤权或温控。

两端覆盖安装0.3.16后重新连接，建议依次测试：长距离滑动穿过悬浮导航；连续快速上下划；手指停住再继续；打开通知栏或切后台再回来；被控端主动滑动；断网恢复后重新操作。正常停止控制后应释放后台锁。

## 平台依据

- [Compose 触点归属](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)：首次命中确定后续同一触点的接收链。
- [AccessibilityService.dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler))：新手势派发会取消正在执行的手势，不能绕开统一所有权。
- [Android 10 MotionEventInjector](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java)：184–191行及314–328行，无效续笔先取消现有注入，再回报失败。厂商实现还需双机实测。
