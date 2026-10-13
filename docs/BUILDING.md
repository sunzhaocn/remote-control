# SimpleRC 构建说明

## 当前 0.3.3 产物

Android assembleDebug 在 WSL2 Ubuntu/JDK17/SDK36 完成；仅编译，不自动测试或设备操作。
本地命名产物：android/app/build/outputs/apk/debug/SimpleRC-0.3.3-debug.apk，SHA-256 以 STATE.md 为准。原 app-debug.apk 也保留，同一构建。
Go build/vet 和 Linux amd64 交叉编译已完成，服务器仅接收后端二进制/源文件；本轮不上传 Android APK。

## 阶段 1 工具链

| 组件 | 固定版本 |
| --- | --- |
| Android Gradle Plugin | 8.10.1 |
| Gradle Wrapper | 8.11.1 |
| JDK | 17 |
| Kotlin / Compose Compiler 插件 | 2.2.10 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 29（Android 10） |
| Compose BOM | 2025.06.01 |
| 标准 WebRTC AAR | `io.getstream:stream-webrtc-android:1.3.10` |

AGP 8.10 官方兼容信息要求 Gradle 8.11.1、JDK 17，并支持最高 API 36。Kotlin 2.2.10 的官方兼容范围覆盖 AGP 8.10 和 Gradle 8.11.1；Kotlin 2.x 使用 Compose Compiler Gradle 插件。

依赖仓库为 Google Maven、Maven Central 和 Gradle Plugin Portal。若所在网络无法访问这些仓库，应在 Gradle 全局配置中使用可信镜像；不要把仓库账号或密钥写入工程。

## 本机构建

1. 安装 JDK 17，并设置 `JAVA_HOME`。
2. 安装 Android SDK Platform 36 和 Build Tools 35.0.0 或 AGP 自动选择的兼容版本。
3. 如 SDK 不在默认目录，在不提交的 `android/local.properties` 中配置 `sdk.dir`。
4. 在 `android/` 中执行明确任务：

   Windows：`gradlew.bat :app:assembleDebug`

   Linux/macOS：`./gradlew :app:assembleDebug`

该命令是构建验证，不代表功能测试。工程没有自定义测试、设备安装、ADB 或模拟器钩子。

## 当前环境结果

2026-10-13 已成功执行 `:app:assembleDebug`。产物为 `android/app/build/outputs/apk/debug/app-debug.apk`，版本 `0.2.0`、versionCode 2、debug 变体，SHA-256 为 `0bd49322b889fe4c4aae8791f9c2fbda66a6b2e5551e254ccc22256fcfa917a3`。该结果只代表构建验证。

当前桌面运行环境需把 `TEMP`、`TMP`、`java.io.tmpdir` 和 `jdk.net.unixdomain.tmpdir` 指向短的 Windows 本地路径（本次使用 `C:\Temp\codex-java`），否则 Java NIO 可能无法建立 Gradle 所需的本地套接字。项目中文路径通过 `android.overridePathCheck=true` 显式允许。

后端已用 Go 1.27 执行 `go build ./...`、`go vet ./...`，并交叉编译 Linux amd64 静态二进制用于服务器容器。

## WebRTC 音频补丁边界

阶段 1–4 从 Maven Central 使用标准预编译 AAR。用户已授权 WebRTC 源码构建，但 WSL2 安装后系统要求重启，当前尚不能初始化 Ubuntu；因此本轮没有补丁 AAR。重启后按 `webrtc-patch/BUILD_LINUX.md` 继续。

未来的补丁 AAR 不使用付费制品服务。计划保存可复现补丁和构建说明，并在本地 `android/app/libs/` 放置 AAR（该二进制默认被 `.gitignore` 排除）或使用明确免费的发布附件，同时记录 SHA-256。

## 2026-10-13 音频补丁构建结果

补丁 AAR 已在 WSL2 Ubuntu 24.04 上构建，实际首次耗时 7 分 49 秒；源码 258 MiB、Gradle 缓存 2.9 GiB、SDK 754 MiB。Android 应用改用仓库内免费保存的本地 AAR，版本 0.3.0，APK SHA-256 为 `1a5e329683c72bda7fcc47340fe004df586e2c2649aea039ba00a5edb8d237de`。Windows 本机 Gradle 在重启后遇到 Java 回环连接阻断，本次应用构建改由 WSL2 完成并成功；这只代表构建验证。
