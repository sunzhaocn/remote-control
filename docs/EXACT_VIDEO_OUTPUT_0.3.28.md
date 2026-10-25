# 0.3.28 实际输出尺寸与恢复流程

用户反馈0.3.27按导航键后仍持续模糊，静止和视频均未恢复。上版新增的补帧和超时检查没有覆盖底层尺寸转换，不应将参数检查通过视为手机画质已恢复。

## 查到的代码缺口

`VideoSource.adaptOutputFormat(width,height,fps)`将目标宽高转换为最大像素数和宽高比；原生适配器按比例阶梯选择输出，接口不保证请求宽高就是实际宽高。旧流程把任意小步计算出的尺寸直接作为编码关键帧和接收确认的精确目标，容差仅16像素。一旦底层将小步取回旧尺寸，试升就无法被确认并反复回退。即使应用scale已为1，原生资源适配还可能叠加另一层缩小。

源码依据：[SDK VideoSource](https://github.com/GetStream/webrtc-android/blob/1.3.10/stream-webrtc-android/src/main/java/org/webrtc/VideoSource.java)、[WebRTC比例阶梯](https://webrtc.googlesource.com/src/+/cbf5122333ce2e34572dfba1c3c0e32304362adc/media/base/video_adapter.cc)、[SDK RTP参数](https://github.com/GetStream/webrtc-android/blob/1.3.10/stream-webrtc-android/src/main/java/org/webrtc/RtpParameters.java)。这是源代码和接口契约的缺陷分析，尚无用户手机日志证明它是所有实测模糊的唯一原因。

## 本次修改

新策略在CaptureActivityObserver中对每个原始纹理调用cropAndScale，明确生成目标宽高，再交给VideoSource。质量变化始终以原始纹理为来源，不对已经模糊的小图放大；旋转90/270度转换物理宽高；同尺寸借用原纹理，缩放引用在异常时也释放。屏幕采集Surface保持原屏尺寸，画质切换不重建MediaProjection。纹理路径不调用toI420读取像素。

新策略不再对VideoSource设置目标像素上限，并使用RTP DegradationPreference.DISABLED关闭第二层自动分辨率/帧率限制。分辨率只由现有应用实测网络和设备保护策略控制；WebRTC的带宽估计、编码码率控制、拥塞控制、丢帧和发包节奏保留。30fps采集时隙、原始编码队列上限、最新帧显示和VSync调度保留。编码队列满或本次间隔出现新丢输入，配合真实源/发送吞吐不足，作为应用设备保护依据；历史累计丢帧不永久充当压力。

恢复成功后逐步解除3%/6%的历史试升惩罚；两次连续画面试升都经过目标尺寸、新解码及显示进度验证后，才解除旧失败对普通恢复的禁用。成功后的稳定停留和迟到压力回退仍保留，新失败重新退避。静止精修和缺反馈不能解除视频验证门槛。

诊断与发送端反馈同时显示目标输出、最近编码和对端解码宽高；等待错误尺寸时显示目标与实际尺寸，避免用“编码精修中”覆盖尺寸不匹配的原因。宽高仅反映提交/编解码元数据，不等于实机字体主观可读性。

控制端本地返回100次退出规则、息屏/断开释放、被控端无障碍及悬浮导航命令没有修改。不增加权限，不部署服务器，不重建AAR/原生库。默认兼容策略保持原先采集路径。

## 验证边界与安装

新增测试直接核对CaptureActivityObserver交给下游的Buffer宽高、引用释放和旋转，而非只断言请求参数；覆盖任意小步、低清恢复到原始纹理、静止重放不误判媒体等。两个流程测试串联生产采集管线、纹理观察器、恢复确认和真实AAR的编码精修契约，接收器及纹理为模型，不能称为硬件编解码或双机实测。另有实际RtpParameters应用检查、编码队列新旧证据和恢复惩罚解除测试。

完整构建、签名、单元测试和冻结输入回执见 `../artifacts/BUILD_0.3.28.json`，APK为 `../artifacts/SimpleRC-0.3.28-debug.apk`。两端均覆盖安装0.3.28，选择新策略并断开重连。未安装或操作用户的OPPO Android11/vivo Android16，仍须检查按导航后静止字体、持续视频和带宽/设备真实压力下的表现。
