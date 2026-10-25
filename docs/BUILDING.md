# 当前构建说明（0.3.37 1080p设备过载保护）

APK `SimpleRC-0.3.37-1080p-release.apk`，versionCode40，65,117,705字节，SHA-256 `7f6652a269a06624fae46684adbfb9422d8d45cce1b824df1b57f3333a77ffce`。

Release/Debug/80%/88%四配置各58套件、650项测试通过，零失败/错误/跳过；Release/Debug lint零错误。154项构建输入核对通过，APK v2签名有效且沿用原证书。Manifest权限、WebRTC AAR及四ABI原生库与0.3.36一致，旧交付包未改动。

构建命令（android目录，沿用WSL JDK17/SDK）：

`./gradlew :app:testReleaseUnitTest :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleRelease :app:lintRelease :app:lintDebug --no-daemon --console=plain`

仅交付主包Release，DEBUG=false，80%/88%对照开关关闭，沿用原开发签名以便覆盖安装。

两端覆盖安装后选择“新策略”并断开重连；诊断中可见24fps设备保护及具体原因。默认策略仍保持原有30fps和库默认码率。未进行双机实测，实际帧率、清晰度和卡顿改善仍需设备验证。没有新增存储权限或视频磁盘缓存，服务器无需更新。

构建回执 `../artifacts/BUILD_0.3.37.json`；复核 `python -X utf8 artifacts/verify-build-0337.py`。

# 0.3.36 历史构建说明（路径诊断修复）

APK `SimpleRC-0.3.36-1080p-release.apk`，versionCode39，65,117,705字节，SHA-256 `b633caaf022f7b88e648107809e9a36e5eb070899a984b3a80205f1da9d11289`。

Release/Debug/80%/88%四配置各57套件、625项测试通过，零失败/错误/跳过；Release/Debug lint零错误。152项构建输入核对通过，APK v2签名有效且沿用原证书。权限、WebRTC AAR和四ABI原生库与上次交付一致，旧包保持原样。

构建命令（android目录，沿用WSL JDK17/SDK）：

`./gradlew :app:testReleaseUnitTest :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleRelease :app:lintRelease :app:lintDebug --no-daemon --console=plain`

交付主包Release，DEBUG=false，80%/88%对照开关关闭，沿用原开发签名以支持覆盖安装。

保留0.3.35短边1080封顶、全屏比例、30fps目标及原有码率策略。两端覆盖安装后断开重连，分别查看媒体路径与独立控制路径。尚未双机实测；本次修复诊断准确性，不代表已经确认真实公网路径或解决所有卡顿。服务器无需更新。

构建回执 `../artifacts/BUILD_0.3.36.json`；复核 `python -X utf8 artifacts/verify-build-0336.py`。

# 0.3.35 历史构建说明（双端1080p）

APK `SimpleRC-0.3.35-1080p-release.apk`，versionCode38，65,101,321字节，SHA-256 `47bdddd906cc02639dbdaf44b84b4478daf0be532ffcac7c35aaed0804edc4f5`。

Release/Debug/80%/88%四配置各55套件、586项测试通过，零失败/错误/跳过；Release/Debug lint零错误，APK v2签名有效且与0.3.34一致。149项构建输入冻结核对通过，无新增权限，WebRTC AAR和四ABI原生库保持，旧安装包未覆盖。

构建命令（android目录，沿用WSL JDK17/SDK）：

`./gradlew :app:testReleaseUnitTest :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleRelease :app:lintRelease :app:lintDebug --no-daemon --console=plain`

仅交付主包Release，DEBUG=false，80%/88%对照开关均关闭。旧对照构建也先受1080短边上限约束，再应用自身百分比，仅用于回归，不交付新对照包。保留原开发签名证书以便覆盖安装。

两端覆盖安装此版本后断开重连。原始屏幕坐标保持，发送画面按比例缩小；帧率目标仍为30fps，码率自适应。尚未双机实测，不保证设备持续过载或窄带时的实际帧率与画质。本次未操作手机、未更新服务器。

构建回执 `../artifacts/BUILD_0.3.35.json`；复核 `python -X utf8 artifacts/verify-build-0335.py`。

# 0.3.34 历史构建说明（原分辨率）

APK `SimpleRC-0.3.34-native-release.apk`，versionCode37，65,101,321字节，SHA-256 `caad65d5ceb9391c87e05c0f7bd3bb3d3ca5c7ed973855ddb898d08abdf3c3d2`。

Release/Debug/80%/88%四配置各54套件、572项测试通过，零失败/错误/跳过；Release/Debug lint零错误。Go后端22项测试、vet和build通过，后端无需更新。APK签名有效并与0.3.33一致，可同包名覆盖安装；无新增权限，AAR及四ABI WebRTC库未改变。

在android目录，通过WSL的JDK17/SDK运行：

`./gradlew :app:testReleaseUnitTest :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleRelease :app:lintRelease :app:lintDebug --no-daemon --console=plain`

交付Release构建，`DEBUG=false`，80%/88%开关均关闭；沿用项目现有开发证书以保持覆盖安装，不是新建生产签名密钥。80%/88%仅回归测试，不交付缩小尺寸的新APK。

原屏尺寸按编码要求向下偶数对齐，奇数宽高最多各差1像素；保留30fps目标、码率自适应和有界队列，不代表无损画质或设备必然达到30fps。两端安装此包后重新连接。未安装/操作手机、未双机或网络注入实测、未部署服务器。

构建输入148项已冻结核对，测试、lint、签名、哈希及权限回执见 `../artifacts/BUILD_0.3.34.json`，复核 `python -X utf8 artifacts/verify-build-0334.py`。

# 0.3.33 历史构建说明

文本两次失败暂停只统计已应用后的验证超时；拥塞、设备压力、参数未应用及用户取消不计入。恢复基准改用失败时最近1500ms以内的BWE，过期按未知处理，不沿用试验开始值。新增近60秒编码耗时区间均值P50/P95、样本数和编码队列丢帧增量/采样时长，明确非逐帧分位数。固定像素/30fps、仅观测设备压力、既有QP前置判断保持，不增加性能档。详见 `REFINEMENT_FAILURES_0.3.33.md`。

正式90%包 `../artifacts/SimpleRC-0.3.33-debug.apk`，versionCode36，72,411,058字节，SHA-256 `6b722ca241d75550b8533431a6bd7fe1bdf3608db0083e87d8b025d855cd203d`；独立88%包 `../artifacts/SimpleRC-0.3.33-compare88.apk`，70,443,498字节，SHA-256 `b8c326578ad3103bc0b831666d7208ed4a17676673b9c83abba2976e8fa2a62a`。

普通/80%/88%三配置各52套件、559项测试，零失败/错误/跳过；两份APK lint零错误（各26/19项提示）。签名、165项冻结输入、AAR和四ABI原生库核对通过；权限及七份旧APK保持。回执 `../artifacts/BUILD_0.3.33.json`。

两端覆盖同包名版本，选择新策略后断开重连；对照时使用同一种包，只保持一组连接并停止另一应用待机。无新增权限，未测试手机、未做网络注入、未部署后端。设备持续过载仍可能丢帧，新增分布不能替代双机性能实测。

构建：android目录运行 `./gradlew :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleDebug :app:assembleCompare88 :app:lintDebug :app:lintCompare88 --no-daemon`。复核 `python -X utf8 artifacts/verify-build-0333.py`。旧回执记录交付时快照，不用旧校验脚本复制当前构建输出。

# 0.3.32 历史构建说明

精修不再主动降低已有发送上限；增加静止阶段H.264 QP/可用预算前置判断及两次失败后文本专用暂停，带宽翻倍/路径变化可重新开放，不锁住正常视频恢复；QP=0显示未知，状态两端768字符，删除文本虚假原生尺寸目标并保留真实接收校验。设备侧选“仅观测”并明确文案，尚未新增自动性能降档。保持固定30fps及90%/88%/80%尺寸规则。详见 `STATIC_REFINEMENT_0.3.32.md`。

正式90%包 `../artifacts/SimpleRC-0.3.32-debug.apk`，versionCode35，72,394,678字节，SHA-256 `5c6b46f9d51265ff1d06deb6a429030d15bbfd54182277f16f71d319cad555ca`；独立88%包 `../artifacts/SimpleRC-0.3.32-compare88.apk`，70,427,114字节，SHA-256 `f309e77759c6c9e9895ecf3e1efdcb166c986caf904a2f2b4f09d39e6fa46193`。

普通/80%/88%三种配置各49套件、541项测试，零失败/错误/跳过；两份APK lint零错误（各26/19项提示）。签名及160项冻结输入核对通过，AAR、四ABI原生库、权限及五份旧APK保持。回执 `../artifacts/BUILD_0.3.32.json`。

两端覆盖同包名版本，选择新策略后断开重连；88%包仍可与普通包并排安装，只保持一组连接并停止另一应用待机。无新增权限，未安装/操作手机，未双机实测，未部署后端。设备过载仍可能丢帧，不把文案修正当作主动性能调节已完成。

构建：android目录运行 `./gradlew :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleDebug :app:assembleCompare88 :app:lintDebug :app:lintCompare88 --no-daemon`。复核 `python -X utf8 artifacts/verify-build-0332.py`。旧回执记录原交付快照，不要用旧校验脚本复制当前构建输出。

# 0.3.31 历史构建说明

已核对优化清单15项，取消正式版交互时改尺寸及固定操作降码率；静止精修预算参考BWE、最多12次并完成即停；应用刷新请求合并/滚动限额、压力暂停，严重压力允许500ms再次退降，普通压力保留迟滞；新增QP、bpp、关键帧和两类尺寸变更诊断。保持30fps，正式版固定90%宽高，88%/80%构建仍固定各自比例。逐项采用/拒绝理由见 `OPTIMIZATION_REVIEW_0.3.31.md`。

正式包 `../artifacts/SimpleRC-0.3.31-debug.apk`，versionCode34，72,378,290字节，SHA-256 `5c92adbfe9df1928b27c3aada92c46aebe11c062e13622c1e2d91d79eb64ded0`；88%包 `../artifacts/SimpleRC-0.3.31-compare88.apk`，70,427,114字节，SHA-256 `427654d8b4f8737fc1c3f699987be941fe344dba37119b95adb34297aeced77c`。

普通/80%/88%三种配置各517项测试通过，正式与88%包lint零错误（各26/19项提示）；签名与154项冻结输入核对通过，权限、AAR及四ABI原生库保持，三个旧0.3.30 APK保持。回执 `../artifacts/BUILD_0.3.31.json`。

两端选择同一种包覆盖安装、使用新策略并重连。88%包继续使用独立包名，可覆盖旧88%版；与普通包并排安装时，只保持一组连接，停止另一应用待机。没有新增权限；未测试手机、未部署后端，不宣称端到端P95或实际字体可读性已经达标。

构建：在android目录运行 `./gradlew :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleDebug :app:assembleCompare88 :app:lintDebug :app:lintCompare88 --no-daemon`。复核 `python -X utf8 artifacts/verify-build-0331.py`。旧回执保留原交付快照，请勿用旧校验脚本复制当前构建输出。

# 0.3.30 历史构建说明

## 独立88%临时对照版

独立临时版 `0.3.30-compare88`，包名 `com.simplerc.app.compare88`，应用名“SimpleRC 88% 对照”。操作、静止和视频统一保留原屏宽高88%，不再随操作降低分辨率；码率和队列保护保留。普通版规则及已交付90% APK保持，主版本仍为0.3.30。

APK `../artifacts/SimpleRC-0.3.30-compare88.apk`，70,410,734字节，SHA-256 `1ca9036784d597983ab37ed4068d994988495e853072bd358f00a7d4c97ec880`。普通/80%/88%配置各496项测试通过，普通/88%配置lint零错误，临时版19项提示；签名、包内三个前台服务类型、146项冻结输入及原版APK保持检查通过。回执 `../artifacts/BUILD_0.3.30-compare88.json`。

两端安装并打开“SimpleRC 88% 对照”，重新授权，选择新策略并使用新生成的ID；原版先断开并停止待机。可并排安装，不继承原版权限/数据。未测试手机、未部署后端。详见 `COMPARE_88_0.3.30.md`。

构建：在android目录运行 `./gradlew :app:testDebugUnitTest :app:testCompare80UnitTest :app:testCompare88UnitTest :app:assembleCompare88 :app:lintCompare88 :app:lintDebug --no-daemon`。校验：`python -X utf8 artifacts/verify-compare88.py`。普通版旧回执记录原交付快照，不用于重新复制当前构建输出。

## 独立80%临时对照版

独立临时版 `0.3.30-compare80`，包名 `com.simplerc.app.compare80`，应用名“SimpleRC 80% 对照”。操作、静止和视频统一保留原屏宽高80%，不再随操作降低分辨率；码率和队列保护保留。普通版规则及已交付90% APK保持，主版本仍为0.3.30。

APK `../artifacts/SimpleRC-0.3.30-compare80.apk`，70,410,734字节，SHA-256 `377257b553d1217a050af57533320d2c0d0ca6904a8ec5f2947f2e4439fde3b2`。普通/对照配置各491项测试通过，两种配置lint零错误，临时版19项提示；签名、包内三个前台服务类型、143项冻结输入及原版APK保持检查通过。回执 `../artifacts/BUILD_0.3.30-compare80.json`。

两端安装并打开“SimpleRC 80% 对照”，重新授权，选择新策略并使用新生成的ID；原版先断开并停止待机。可并排安装，不继承原版权限/数据。未测试手机、未部署后端。详见 `COMPARE_80_0.3.30.md`。

构建：在android目录运行 `./gradlew :app:testDebugUnitTest :app:testCompare80UnitTest :app:assembleCompare80 :app:lintCompare80 :app:lintDebug --no-daemon`。校验：`python -X utf8 artifacts/verify-compare80.py`。普通版旧回执记录原交付快照，不用于重新复制当前构建输出。

APK `artifacts/SimpleRC-0.3.30-debug.apk`，versionCode33，72,378,294字节，SHA-256 `9a246a905e7283cd082fdc3b91567342a33f2064f2f38476eb80f5c87b9c5799`。486项单元测试通过，lint 0错误、26项提示，v2签名与0.3.29一致，140项冻结输入核对通过。AAR及原生库与0.3.29一致，未重建库；沿用相同AAR的0.3.20库回执。回执 `artifacts/BUILD_0.3.30.json`，复核 `python -X utf8 artifacts/verify-build-0330.py`。

两端覆盖安装0.3.30，选择新策略后断开重连，无新增权限。10%按宽高分辨率计算，非固定码率/主观画质百分比；原屏90%不保证窄带时编码细节或过载时帧率。未安装或操作用户的OPPO Android11/vivo Android16，未双机实测，未部署后端。说明见 `UNATTENDED_90_0.3.30.md`。

# 0.3.29 历史构建记录

APK `artifacts/SimpleRC-0.3.29-debug.apk`，versionCode32，72,378,294字节，SHA-256 `e95433d4bc541c886a1587aea18f9b80d100ee7da83cff44111136ad4245476a`。483项单元测试通过，lint 0错误、26项提示，v2签名与0.3.28一致，140项冻结输入核对通过。AAR及原生库与0.3.28一致，未重建库；沿用相同AAR的0.3.20库回执。回执 `artifacts/BUILD_0.3.29.json`，复核 `python -X utf8 artifacts/verify-build-0329.py`。

两端覆盖安装0.3.29，选择新策略后断开重连，无新增权限。未安装或操作用户的OPPO Android11/vivo Android16；自动验证不代表实际字体可读性或双机延迟实测。未部署服务器。说明见 `STILL_TEXT_0.3.29.md`。

# 0.3.28 历史构建记录

APK `artifacts/SimpleRC-0.3.28-debug.apk`，versionCode31，72,378,294字节，SHA-256 `57568f6698f6d87779e70e0be6d7aa68190af7b62d2cdb9111d5597398d27315`。474项单元测试通过，lint 0错误、26项提示，v2签名与0.3.27一致，140项冻结输入核对通过。AAR及原生库与0.3.27一致，未重建库；沿用相同AAR的0.3.20库回执。回执 `artifacts/BUILD_0.3.28.json`，复核 `python -X utf8 artifacts/verify-build-0328.py`。

两端均覆盖安装0.3.28，选择新策略并断开重连，无新增权限。未安装或操作用户的OPPO Android11/vivo Android16；纹理和接收器模型、软件检查不等于双机画质实测。用户反馈上版仍模糊，不能将旧版参数检查视为修复成功。未部署服务器。说明见 `EXACT_VIDEO_OUTPUT_0.3.28.md`。

# 0.3.27 历史构建记录

APK `artifacts/SimpleRC-0.3.27-debug.apk`，versionCode30，72,378,294字节，SHA-256 `4aa0d5c11b41622d1d21ac75205d20f62a6c805625b99bd7dabee04b3f882fa0`。454项单元测试通过，lint 0错误、26项提示，v2签名与0.3.26一致，136项冻结输入核对通过。AAR及原生库与0.3.26一致；相同AAR的0.3.20库回执沿用，本次未重建库。回执 `artifacts/BUILD_0.3.27.json`，复核 `python -X utf8 artifacts/verify-build-0327.py`。

两端覆盖安装0.3.27、选择新策略后重连，无新增权限。未安装或操作用户的OPPO Android11/vivo Android16手机；自动化验证不等于实际字体可读性和厂商手势实测。未部署服务器。说明见 `NAVIGATION_RECOVERY_0.3.27.md`。

# 0.3.26 历史构建记录

APK `artifacts/SimpleRC-0.3.26-debug.apk`，versionCode29，72,361,906字节，SHA-256 `bcee2049bb255f653adadc4aaca1463c96ec53a0d3ba85b5dfcdf25bcd65316c`。最终构建1m 22s，434项单元测试通过（亮屏策略28项、资源驱动24项），lint 0错误、26项提示，v2签名与0.3.25一致，133项冻结输入核对通过。APK内确认新的亮屏资源驱动；AAR和原生库与0.3.25一致，沿用相同AAR的0.3.20库回执。回执 `artifacts/BUILD_0.3.26.json`，复核 `python -X utf8 artifacts/verify-build-0326.py`。

两端覆盖安装0.3.26后重连，无新增权限要求。模拟时钟覆盖24小时后台续期，以及两端分别2小时后断开和多种系统操作失败后的恢复；这些不是手机长时间实测。未安装或操作用户的OPPO Android 11/vivo Android 16手机，仍须验证实际持续亮屏和断开后的系统超时息屏。未部署服务器。说明见 `SCREEN_AWAKE_LIFETIME_0.3.26.md`。

# 0.3.25 历史构建记录

APK `artifacts/SimpleRC-0.3.25-debug.apk`，versionCode28，72,329,142字节，SHA-256 `305175a0d568914bb4c2a5834d936dad834bafa7b345a4b752d0ebb0876f733d`。最终构建1m 31s，406项单元测试通过（新增24项亮屏策略/服务归属检查），lint 0错误、26项提示，v2签名与0.3.24一致，131项冻结输入核对通过。APK内确认新增悬浮权限和亮屏实现。AAR和原生库与0.3.24一致，沿用相同AAR的0.3.20库回执。回执 `artifacts/BUILD_0.3.25.json`，复核 `python -X utf8 artifacts/verify-build-0325.py`。

两端覆盖安装0.3.25后重连。控制端点“后台运行设置 → 允许悬浮显示以保持后台亮屏”，按Android系统要求手动允许SimpleRC（Android 11可能先显示应用列表）；被控端可复用已连接的无障碍服务。后台出现不接收触摸的小紫点，断开移除。未在用户的OPPO Android 11/vivo Android 16实测，窗口挂载或显示锁持有不等于物理面板验证；未部署服务器。说明见 `BACKGROUND_SCREEN_0.3.25.md`。

# 0.3.24 历史构建记录

APK `artifacts/SimpleRC-0.3.24-debug.apk`，versionCode27，72,296,338字节，SHA-256 `65558730173a89b7160a53d101631759295354c57acf83f0769a9715a18c8754`。最终构建1m 18s，382项单元测试通过，lint 0错误、23项提示，v2签名与0.3.23一致，128项冻结输入核对通过。AAR和原生库与0.3.23一致，沿用相同AAR的0.3.20库回执；本次未重建库。回执 `artifacts/BUILD_0.3.24.json`，复核 `python -X utf8 artifacts/verify-build-0324.py`。

两端覆盖安装0.3.24，选择“新策略”后重连。未安装或测试手机，真实画质、流畅度与端到端延迟仍需双机验证；服务端无需部署。持续压力或完全缺少接收反馈时仍有限重试并显示原因，不无条件拉满。说明见 `RECOVERY_STABILITY_0.3.24.md`。

# 0.3.23 历史构建记录

APK `artifacts/SimpleRC-0.3.23-debug.apk`，versionCode26，72,296,338字节，SHA-256 `5d6072c23eda4e82cf3f853d419e6669a6195ff0bb769a57740ecf289fba311c`。最终构建1分3秒，359项单元测试通过，lint 0错误、23项提示，v2签名与0.3.22一致，128项冻结输入核对通过。AAR、原生库和内部音频保持不变，沿用相同AAR的0.3.20调度/采集桩/队列回执。回执 `artifacts/BUILD_0.3.23.json`，复核 `python -X utf8 artifacts/verify-build-0323.py`。

两端覆盖安装0.3.23，选择“新策略”后重连。未安装或测试手机，真实字体、流畅度与端到端延迟仍需双机验证；服务端无需部署。完全没有接收反馈时只能有限试验并回退，不无条件拉满画质。说明见 `RECOVERY_PROBE_0.3.23.md`。

# 0.3.22 历史构建记录

APK `artifacts/SimpleRC-0.3.22-debug.apk`，versionCode25，72,263,570字节，SHA-256 `32fdc06ec76fb02b2282b00d42bc62b11187e7c85cbe82eaf29872be3c49442c`。最终构建1m 15s，316项单元测试通过，lint 0错误、23项提示，v2签名与0.3.21一致，124项冻结输入核对通过。WebRTC AAR与原生库不变，沿用相同AAR的0.3.20调度/采集桩/队列回执；本次未重建库或重跑这些库检查。回执 `artifacts/BUILD_0.3.22.json`，复核 `python -X utf8 artifacts/verify-build-0322.py`。

两端覆盖安装0.3.22，选择“新策略”后重连。未安装或测试手机，真实画质、帧率与端到端延迟仍需双机验证；服务端无需部署。说明见 `ADAPTIVE_VIDEO_0.3.22.md`。

# 0.3.21 历史构建记录

APK `artifacts/SimpleRC-0.3.21-debug.apk`，versionCode24，72,263,570字节，SHA-256 `d04609b838c63237880f2edfef1a71f3d495846f6560d540898bbeb1755524b2`。301项单元测试通过，lint 0错误、23项提示，v2签名与0.3.20一致，124项冻结输入核对通过。WebRTC AAR和原生库保持不变，未重建AAR，沿用0.3.20相同库的行为回执。回执 `artifacts/BUILD_0.3.21.json`，复核 `python -X utf8 artifacts/verify-build-0321.py`。

两端覆盖安装0.3.21，选择“新策略”后重新连接。没有安装或测试手机，可见切换时机、文字和延迟待双机确认；服务端无需部署。详见 `CONTROLLER_TRIGGER_0.3.21.md`。

# 0.3.20 历史构建记录

APK `artifacts/SimpleRC-0.3.20-debug.apk`，versionCode23，72,247,186字节，SHA-256 `35f5295f459ad07be3ee139d5d083dfcb55c1e49d39235949409cd0939a90cd7`。最终构建55秒，296项单元测试通过，lint 0错误、23项提示；16项调度、10项采集行为桩和8项队列回归通过。v2签名与0.3.19一致，124项构建输入核对通过，沿用上版AAR且四ABI原生库和25个音频类不变。回执 `artifacts/BUILD_0.3.20.json`，复核 `python -X utf8 artifacts/verify-build-0320.py`。

两端覆盖安装0.3.20，选择“新策略”后重新连接。没有安装或测试手机，字体可读性与真实延迟待双机验证。新增两端DataChannel提示，服务端未变更，无需部署。说明见 `MOTION_CLASSIFICATION_0.3.20.md`。

# 0.3.19 历史构建记录

APK `artifacts/SimpleRC-0.3.19-debug.apk`，versionCode22，72,247,186字节，SHA-256 `8ae03e139f8b09401aaf48f544eec210a0119b5e9be18885402c799f91c1b8a5`。最终构建59秒，280项单元测试全部通过，lint 0错误、23项提示；16项调度、10项采集行为桩及8项队列回归通过。v2签名与0.3.18一致，122个构建输入逐一核对，原生四ABI及25个音频类保持不变。回执 `artifacts/BUILD_0.3.19.json`，复核 `python -X utf8 artifacts/verify-build-0319.py`。

AAR构建17秒，20,623,170字节，SHA-256 `f1e6ef5644bd703da3b3ba932781bf5c917fd0fb0cd9173e3b127aff6f501d7b`；三补丁重放一致，内部音频和原生库不变，新增完整尺寸精修判定。最后构建前保留旧打包缓存并使用新缓存封装，源文件/补丁/脚本及AAR均冻结核对。未部署或安装手机。详见 `TEXT_DETAIL_0.3.19.md`。

## 0.3.18 历史构建记录

APK `artifacts/SimpleRC-0.3.18-debug.apk`，versionCode21，72,247,186字节，SHA-256 `f84f765280a73d92bb6c6a229a207a6980c8e7d780a820d9b2edfc944d3e3720`。最终构建1分8秒，263项单元测试全部通过，lint 0错误、23项提示；16项调度器行为检查、10项capturer行为桩和8项实际AAR队列回归通过。v2签名有效且与0.3.17一致，120个构建输入逐一核对。四ABI原生库和25个内部音频类不变，回执 `artifacts/BUILD_0.3.18.json`，复核 `python -X utf8 artifacts/verify-build-0318.py`。

AAR最新增量构建16秒，20,622,753字节，SHA-256 `37debb5e61692ab6b1641a1ba903dbe455d954b159609914e2151be4cd61821d`。三份补丁在固定上游重放一致，包含SurfaceTextureHelper调度改动及七个helper；原生/音频不变。最后构建前保留旧打包缓存并从新包缓存封装，消除增量ZIP空洞，全部源码与固定输入一致。未安装手机、未部署后端。详见 `FRAME_PACING_0.3.18.md`。

## 0.3.17 历史构建记录

APK `artifacts/SimpleRC-0.3.17-debug.apk`，versionCode20，72,457,364字节，SHA-256 `ddf861a200cd9ce33d99b32c413a7229d7f3e609fa7b58ec22f9104ec104a5e9`。最终构建59秒，248项单元测试全部通过，lint 0错误、23项提示。v2签名有效且与0.3.16一致，99个构建输入逐一核对；四ABI原生库及25个音频类不变。回执 `artifacts/BUILD_0.3.17.json`，复核 `python -X utf8 artifacts/verify-build-0317.py`。

WebRTC Java AAR已重建，SHA-256 `16154938bbddb6030fb4fb4b097e2b0d125e8ec59037abfdc670f2ca08bb7464`。三份固定上游补丁重放一致，10项投屏行为桩与8项实际AAR队列检查通过；新增31项应用单元测试覆盖状态与刷新。未进行手机安装或硬件测试，后端未改。详见 `CONTENT_VIDEO_0.3.17.md`。

## 0.3.16 历史构建记录

0.3.16 / versionCode 19：WSL SimpleRC-WebRTC、JDK17、Android SDK36，最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain` 1分19秒成功。217项单元测试零失败/错误/跳过，lint零错误、23项提示；触控pump 50项、平台派发桥接21项、输入归属6项、前台资源策略12项。

APK `artifacts/SimpleRC-0.3.16-debug.apk`，72,214,418字节，SHA-256 `78c53888bb62f9d554c8be55cf8fa47e3c3e2148968dc5811ff12ace31d68772`。v2签名有效，与0.3.15相同。89个构建输入在最终构建前冻结并逐一复核；回执 `artifacts/BUILD_0.3.16.json`，脚本 `python -X utf8 artifacts/verify-build-0316.py`。

沿用0.3.14 WebRTC AAR，SHA-256 `35a27f1e208deec6ff784805c47cf50195cded85412d476f66e8578b8e18bdb9`；四ABI原生库与上版字节一致。本轮未重建AAR、未修改后端、未安装或测试手机。详见 `TOUCH_BACKGROUND_REVIEW_0.3.16.md`。

## 0.3.15 历史构建记录

0.3.15 / versionCode 18：WSL SimpleRC-WebRTC、JDK17、Android SDK36，最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain` 56秒成功。192项单元测试零失败/错误/跳过，lint零错误、23项提示；触控pump 45项、平台派发桥接10项、前台资源策略9项。

APK `artifacts/SimpleRC-0.3.15-debug.apk`，72,198,034字节，SHA-256 `e285fa54c86daa9f4fd564e9add6870c1a559b85a049c85822701df0c66b8610`。v2签名有效，与0.3.14相同。87个构建输入在最终构建前冻结并逐一复核；回执 `artifacts/BUILD_0.3.15.json`，脚本 `python -X utf8 artifacts/verify-build-0315.py`。

沿用0.3.14 WebRTC AAR，SHA-256 `35a27f1e208deec6ff784805c47cf50195cded85412d476f66e8578b8e18bdb9`；四ABI原生库与上版字节一致。本轮未重建AAR、未修改后端、未安装或测试手机。详见 `TOUCH_RELEASE_0.3.15.md`。

## 0.3.14 历史构建记录

0.3.14 / versionCode 17：WSL SimpleRC-WebRTC、JDK17、Android SDK36，最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain` 1分24秒成功。171项单元测试零失败/错误/跳过，lint零错误、23项提示。

APK `artifacts/SimpleRC-0.3.14-debug.apk`，72,165,266字节，SHA-256 `c302b314fb6e10c122aed8563c4ac81b9d34a2b623fbc821c428d97797fedafe`。v2签名有效，与0.3.13相同。85个构建输入在构建前冻结并逐一复核。回执 `artifacts/BUILD_0.3.14.json`；脚本 `python -X utf8 artifacts/verify-build-0314.py`。

WebRTC AAR重建17秒，20,617,154字节，SHA-256 `35a27f1e208deec6ff784805c47cf50195cded85412d476f66e8578b8e18bdb9`。三补丁重放一致；10项capturer行为桩及8项实际AAR helper测试通过，旧AAR被同一吞吐测试检出。四ABI、25个音频类和capturer字节码与0.3.13一致。未安装或测试手机，后端未改。详见 `SCROLL_AWAKE_0.3.14.md`。

## 0.3.13 历史构建记录

0.3.13 / versionCode 16：WSL SimpleRC-WebRTC、JDK17、Android SDK36，最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain` 1 分 33 秒成功。155 项单元测试零失败/错误/跳过，lint 零错误、23 项提示。新增电池豁免申请触发 BatteryLife 提示：远控后台连接是核心用途，申请由用户点击设置后发起，没有静默更改设置或禁用该检查。

APK `artifacts/SimpleRC-0.3.13-debug.apk`，72,165,266 字节，SHA-256 `d2e9d5193fb00c62fa6fc52f5789e4865f38b808118de25b654abc2765ab81cc`。v2 签名有效，与 0.3.12 相同。83 个构建输入（含全部应用源码/资源/测试、Manifest、Gradle 配置和 AAR）在构建前冻结，交付时逐一核对；回执 `artifacts/BUILD_0.3.13.json`，复核脚本 `artifacts/verify-build-0313.py`。

WebRTC Java AAR 最后构建 16 秒，20,617,618 字节，SHA-256 `b72094a0fb502b82d2d945d0255a48b6ca56b60d162152f9f4195a97cf7992f6`。三份补丁重放与源码一致，10 项实际 capturer 行为桩测试通过并能够检出旧同 Surface 缺陷，四 ABI 原生库与 25 个音频 Java 类不变。证据 `webrtc-patch/VIDEO_BUILD_0.3.13.json`；本轮未进行手机安装、厂商后台或实际图形测试，后端未修改。

## 0.3.12 历史构建记录

0.3.12 / versionCode 15：最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain` 55 秒成功。140 项单元测试零失败/错误/跳过，lint 零错误、21 项非阻断提示。最终构建前冻结源文件哈希，并在封装后核对，避免测试中修改文件带来的产物不一致。

APK `artifacts/SimpleRC-0.3.12-debug.apk`，72,116,022 字节，SHA-256 `673becbbe8fbed5213886c68d2d7a490e01480f4ff50209185353c7d895c2ce4`。签名有效、与 0.3.11 相同，底层 AAR 与原生库未变。完整回执 `artifacts/BUILD_0.3.12.json`；当前产物复核脚本 `python -X utf8 artifacts/verify-build-0312.py`。未安装手机、未部署后端。

Go 后端配套修复已通过 22 项测试、`go vet` 与 Linux/amd64 交叉构建，本地产物 `server/dist/simplerc-server-linux-amd64`，完整哈希与源文件回执见 `server/dist/BUILD_REVIEW.json`。测试覆盖 Lua/TTL 模拟及本机 WebSocket，没有连接真实数据库；当前 Windows CGO/C 编译器条件不足，未运行 race detector。未部署，兼容与可信代理配置见 `server/REFACTOR_REVIEW.md`。

## 0.3.11 历史构建记录

0.3.11 / versionCode 14：WSL SimpleRC-WebRTC、JDK17、`ANDROID_HOME=/opt/android-sdk`，执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain`，最终 60 秒成功。109 项单元测试零失败/错误/跳过；lint 零错误、20 项非阻断提示。未禁用检查。随后仅重新封装 APK（15 秒，编译任务均复用），移除增量打包留下的空洞，重新通过签名和完整产物校验。

APK `artifacts/SimpleRC-0.3.11-debug.apk`，72,083,254 字节，SHA-256 `5ad347846320baaec907506bc1418d0694ec833ba64c7b7f9a4a278f3a9324ba`。build-tools/35.0.0 验证 v2 签名，与 0.3.10 证书相同，四 ABI 原生库逐字节一致。源文件/测试/签名清单：`artifacts/BUILD_0.3.11.json`；当前构建可通过 `python -X utf8 artifacts/verify-build-0311.py` 复核。

WebRTC Java 补丁 AAR 本轮重建，最终 20,617,476 字节，SHA-256 `20a2098f8efbfdceac4ad8ac800c07c9d017ae0b86ccba5b849c5e600f13c95d`。全部三补丁在固定上游干净重放，与用于构建的源文件逐字节一致；音频接口、编码限帧修复、Android 12 采集兼容分支均经字节码核验。见 `webrtc-patch/BUILD_LINUX.md`。服务器仅只读检查，未安装或测试手机；用户测试步骤见 `LOW_LATENCY_0.3.11.md`。

## 0.3.10 历史构建记录

0.3.10 / versionCode 13：WSL SimpleRC-WebRTC、JDK17、`ANDROID_HOME=/opt/android-sdk`，执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain`，最终 51 秒成功。75 项单元测试零失败/错误/跳过；lint 零错误、20 项非阻断提示，主要为依赖版本与代码风格。修复了检查发现的音频创建权限风险、Android 10 前台服务类型兼容及多余约束布局，没有通过禁用检查绕过错误。

APK `artifacts/SimpleRC-0.3.10-debug.apk`，72,327,104 字节，SHA-256 `5ece1797e27d4640d0f58ae84eb43dcb9fd515426b56ae8403a781a2059ecfca`。build-tools/35.0.0 验证 v2 签名与 0.3.9 证书相同，四 ABI 原生库与旧版逐字节一致。源文件/测试/签名记录在 `artifacts/BUILD_0.3.10.json`，可通过 `python -X utf8 artifacts/verify-build-0310.py` 重新校验当前构建产物。本轮没有部署服务器、安装或测试手机；用户在两端同版本后实测，步骤见 `TOUCH_FIX_0.3.10.md`。

## 0.3.9 历史构建记录

0.3.9 / versionCode12：WSL SimpleRC-WebRTC、JDK17、`ANDROID_HOME=/opt/android-sdk`，执行 `:app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain`，最后 31 秒构建成功，57 项单元测试零失败。build-tools/35.0.0 验证 v2 签名有效，与此前安装包同证书。当前交付 `artifacts/SimpleRC-0.3.9-debug.apk`，72092560 字节，SHA-256 `84ad1f4fc3297322d7ba01d829b36856db5529391173ab38f07a91e9261df29a`。

本轮 WebRTC AAR 未重建，沿用 0.3.8，SHA-256 `580224a8f315b042d2b629fe612ed16e271bb1b56698a88e8a6d1e666e7c2e58`，四 ABI 原生库与上一 APK 一致。清单见 `artifacts/BUILD_0.3.9.json`、`webrtc-patch/VIDEO_BUILD_0.3.9.json`。没有已连接手机，未安装或实机测试。两端安装同版重连；修复路径和验证边界见 TOUCH_INTENT_FIX_0.3.9。

## 0.3.7 历史构建记录

0.3.7 / versionCode10：固定 WebRTC Java 包装补丁 AAR 最后增量构建 17 秒成功；Android `:app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain` 最后 34 秒成功，34 项单元测试无失败。JDK17、`ANDROID_HOME=/opt/android-sdk`、WSL SimpleRC-WebRTC。使用 SDK build-tools/35.0.0 验证 APK v2 签名，包名 com.simplerc.app，与 0.3.6/0.3.5 证书一致，可保留应用数据覆盖安装。

交付 `artifacts/SimpleRC-0.3.7-debug.apk`，81318592 字节，SHA-256 `2d59288e9eb802e2e8979e0743d9a48d9ad43585a4b12d93fa54c33154b0280c`。本机与 APK 里四 ABI WebRTC 原生库和旧版逐字节一致，内部音频接口及新增视频类存在。AAR 校验及固定修订干净检出重放结果见 webrtc-patch/VIDEO_BUILD_0.3.7.json、BUILD_LINUX.md。没有实机 MediaCodec/EGL/无障碍或音画同步测试，未使用 ADB/root/Shizuku。两端手动安装同版本，“新策略”重连生效。

## 0.3.6 历史构建记录

0.3.6：使用 WSL 的 JDK17、`ANDROID_HOME=/opt/android-sdk` 和项目 Wrapper 执行 `:app:assembleDebug :app:testDebugUnitTest --no-daemon --console=plain`，最后构建成功。17 项单元测试无失败，手势驱动为确定性模拟。APK v2 签名有效，包名 com.simplerc.app、versionCode 9，与 0.3.5 的签名证书一致，具备保留应用数据覆盖安装的签名条件。本次未操控或安装手机，也未调用 ADB。

使用 SDK build-tools/35.0.0 的 apksigner/aapt2 验证。交付文件 `artifacts/SimpleRC-0.3.6-debug.apk`，大小 72122760 字节，SHA-256 `f23283634064eef44acccf04cac5c9f458bc3486cb4677f9301a7de537ecd1c4`。两端安装 0.3.6 后开启“新策略”并重新连接。

## 0.3.5 历史构建记录

本地APK：`artifacts/SimpleRC-0.3.5-debug.apk`，versionCode8，debug签名，哈希与大小见STATE。用户随后明确授权安装，两台手机现均为0.3.5/code8，仅安装及核对版本，未继续测试。服务器仅接收后端，不上传Android APK。

| 工具/依赖 | 固定配置 |
| --- | --- |
| AGP / Wrapper | 8.10.1 / 8.11.1 |
| Java | JDK17 |
| Kotlin / Compose Compiler | 2.2.10 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 29 |
| Compose BOM | 2025.06.01 |
| WebRTC | 固定GetStream 1.3.10的本地AudioPlaybackCapture补丁AAR，四ABI |
| Go | go.mod最低1.24，当前构建工具1.27；依赖go.mod/go.sum固定 |

当前环境：Windows + WSL2 SimpleRC-WebRTC Ubuntu24.04；JDK17和Android SDK36位于WSL。应用构建在android/执行明确的`:app:assembleDebug --no-daemon --console=plain`，通过项目Wrapper；也可在正确Windows JDK/SDK环境执行gradlew.bat :app:assembleDebug。不运行宽泛check/build，也没有自定义测试、ADB安装或模拟器钩子。

本轮Android最后构建24秒成功（弃用API警告不阻塞）；Go build ./...、go vet ./...成功，并GOOS=linux GOARCH=amd64 CGO_ENABLED=0生成部署二进制。服务器Docker Compose配置解析、仅server镜像/容器更新、健康204；不启动本机容器。

Google Maven、Maven Central、Plugin Portal与Go模块代理需可达；镜像和账号配置放本机构建设置，不提交秘密。Windows中文路径保留android.overridePathCheck=true；历史Java回环/临时目录问题见PHASE_REPORTS，当前使用WSL构建。

WebRTC补丁构建此前已得到单独授权并完成，本轮不重建。固定上游修订、许可证、SHA256、实际7分49秒/约4GiB工作集及可复现说明见webrtc-patch/BUILD_LINUX.md、UPSTREAM_REVISION、SHA256SUMS。AAR用普通Git对象免费保存，不使用LFS或付费仓库。缺补丁AAR的标准接口不得伪装内部音频成功。

安装两端同为0.3.5才能使用独立控制协商。下一次手工测试在同包切默认/新策略，重新连接生效；不把编译称为卡顿改善已验证。
