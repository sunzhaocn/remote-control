# 当前构建说明（0.3.5）

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
