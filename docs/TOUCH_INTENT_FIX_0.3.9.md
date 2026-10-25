# 点击和滑动误长按修复（0.3.9）

用户反馈点击或滑动变成长按。旧控制界面在手指刚按下时就建立远端持续触点，松手包延迟、过期或缓冲拒绝可能把短操作拖成长按；这是根据源码确定的风险路径，本轮没有取得故障手机的运行日志。

0.3.9 / versionCode 12 在控制手机本地按系统触摸阈值分类：短点击在松手后发送一次 TAP，被控端执行 50ms、不续接的完整手势；超过移动阈值才开始连续滑动，并在首段移动到当前目标；超过本机长按阈值才明确发送 HOLD。真正长按的首段在被控端原点停留至其系统长按阈值加 50ms，之后保留长按拖动。这使真正长按还包含网络和被控端识别等待，不能将其响应时间等同于短点击。

UP/CANCEL 允许比普通控制消息更多的有限缓冲容量（64KiB，对比普通可靠触控 8KiB、MOVE 2KiB）。过期或旧几何版本的结束包只能释放已知同 ID 触点，不能创建旧操作或采用过期坐标。连续触控原有序号、会话和超时检查保留。单次点击与连续手势串行执行；待执行点击最多 16 个，超过 2 秒丢弃，断线清空队列。独立触控连接、最新 MOVE 通道、0.3.8 的显示与坐标修复保持。

Android 编译和 57 项确定性单元测试通过，包括本地短点击/滑动/长按分类、慢网络下固定短手势、快速连点顺序、过期结束只释放、显式长按原点、断线清空和阻塞后丢弃旧点击。测试不代表手机实际触控已经验收。WebRTC AAR 沿用 0.3.8，四 ABI 原生库与上一 APK 一致。

安装两端同为 0.3.9 后重新连接；旧控制端仍会立即发送 DOWN，不能只更新被控端。电脑本轮没有已连接手机，未覆盖安装或实际观察触控结果；产品运行仍通过屏幕共享和无障碍，无 ADB/root/Shizuku 依赖。

构建、签名和源文件校验见 `artifacts/BUILD_0.3.9.json`；公网文件与本地完整下载校验见 `artifacts/PUBLICATION_0.3.9.json`；下载链接和具体哈希见 STATE。保留 0.3.8 下载用于回退。

系统阈值依据：[Android ViewConfiguration](https://developer.android.com/reference/android/view/ViewConfiguration)；本地手势超时使用 [Compose AwaitPointerEventScope](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/AwaitPointerEventScope)。
