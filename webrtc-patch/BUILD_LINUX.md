# Linux / WSL2 可复现构建方案（已验证）

0.3.19最新AAR：三补丁及七个helper按下述流程重建，构建17秒，20,623,170字节，SHA-256 `f1e6ef5644bd703da3b3ba932781bf5c917fd0fb0cd9173e3b127aff6f501d7b`。新增完整尺寸/编码对齐/多帧QP精修判定；原生四ABI和25个音频类与0.3.18一致，16项调度、10项采集及8项队列检查通过。回执 `VIDEO_BUILD_0.3.19.json`、`SCHEDULER_TEST_0.3.19.json`、`CAPTURE_TEST_0.3.19.json`、`QUEUE_TEST_0.3.19.json`，不替代手机实测。

0.3.18最新AAR：按下述相同流程重放三补丁，视频补丁现包含SurfaceTextureHelper，共七个helper须复制。构建16秒，20,622,753字节，SHA-256 `37debb5e61692ab6b1641a1ba903dbe455d954b159609914e2151be4cd61821d`。原生四ABI与25个内部音频类和0.3.17一致；16项构建AAR调度器行为桩、10项采集及8项队列回归通过，未测真实手机/GPU。回执 `VIDEO_BUILD_0.3.18.json`、`SCHEDULER_TEST_0.3.18.json`、`CAPTURE_TEST_0.3.18.json`、`QUEUE_TEST_0.3.18.json`。

0.3.17最新构建：仍使用下述固定上游和补丁流程，包含新增 `SimpleRcFrameRefresh.java`（共四个helper）。AAR构建17秒，20,618,010字节，SHA-256 `16154938bbddb6030fb4fb4b097e2b0d125e8ec59037abfdc670f2ca08bb7464`。三份补丁重放逐字节一致，四ABI及25个音频类不变；10项capturer行为桩与8项实际AAR队列检查通过。回执 `VIDEO_BUILD_0.3.17.json`、`CAPTURE_TEST_0.3.17.json`、`QUEUE_TEST_0.3.17.json`，不替代真实手机编解码或显示验证。

## 实际环境与资源

- Ubuntu 24.04 WSL2、OpenJDK 17、Android SDK Platform/Build Tools 34。
- 上游：`GetStream/webrtc-android` tag `1.3.10`，提交见 `UPSTREAM_REVISION`。
- 该仓库已经包含 Java 源码和四个 ABI 的官方原生库，不需要下载 Chromium/depot_tools 全量源码。
- 本次首次构建耗时 7 分 49 秒；AAR 约 19.7 MiB。首次依赖缓存后，源码仓库、SDK 和缓存合计建议预留 20 GiB。不同网络和机器会变化。

## 命令

```bash
sudo apt-get update
sudo apt-get install -y git openjdk-17-jdk unzip curl
git clone --branch 1.3.10 --depth 1 https://github.com/GetStream/webrtc-android.git
cd webrtc-android
test "$(git rev-parse HEAD)" = "5d388e533d87f857e7de4a729630200cf9766369"
git apply --check /path/to/webrtc-patch/patches/0001-audioplaybackcapture-factory.patch
git apply /path/to/webrtc-patch/patches/0001-audioplaybackcapture-factory.patch
python3 /path/to/webrtc-patch/scripts/apply-realtime-patch.py "$PWD"
python3 /path/to/webrtc-patch/scripts/apply-capture-resize-patch.py "$PWD"
python3 /path/to/webrtc-patch/scripts/verify-realtime-patch.py "$PWD"
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export ANDROID_SDK_ROOT=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :stream-webrtc-android:assembleRelease --no-daemon
sha256sum stream-webrtc-android/build/outputs/aar/stream-webrtc-android-release.aar
```

将产物复制为 `android/app/libs/stream-webrtc-android-simplerc-1.3.10.aar`，并核对 `SHA256SUMS`。AAR 直接随 Git 仓库保存，不使用 Git LFS 或付费制品服务，因此不产生存放费用。

替换前保留旧 AAR，运行 `python3 scripts/verify-video-build.py <旧AAR> <新AAR> <回执JSON>` 核对四 ABI 原生库和音频 Java 字节码不变、内部音频和视频补丁类存在，以及硬编已不设置静默丢输入的 `max-fps-to-encoder`。0.3.13 当前产物 20,617,618 字节，SHA-256 为 `b72094a0fb502b82d2d945d0255a48b6ca56b60d162152f9f4195a97cf7992f6`。旧 0.3.12 AAR 备份在 `artifacts/stream-webrtc-android-simplerc-0.3.12.aar`，SHA-256 为 `20a2098f8efbfdceac4ad8ac800c07c9d017ae0b86ccba5b849c5e600f13c95d`。所有三份补丁已在固定修订的干净检出上重放，并与构建 SDK 的 Java 源码逐字节核对；最终增量 AAR 构建 16 秒。Android 12+ resize 后必须创建新的 Surface 包装并重新绑定，成功后释放旧包装；传同一对象会被系统忽略。Android 10/11 保持重建显示路径。

运行 `python3 scripts/test-capture-surface.py <上游根目录> <回执JSON>` 可验证实际 capturer Java 的 10 项资源/重绑定行为测试。覆盖 API 29、30、31、32、34、35，保留 Android 对象身份短路语义，并通过注回旧调用确认测试能够检出本次缺陷。该测试使用 Java 行为桩，不声称覆盖设备 GPU 或厂商投屏实现。

## 已验证内容

- 0.3.14 增量 AAR 构建 17 秒成功，产物 20,617,154 字节，SHA-256 `35a27f1e208deec6ff784805c47cf50195cded85412d476f66e8578b8e18bdb9`。0.3.13 AAR 备份仍保留在 `artifacts/stream-webrtc-android-simplerc-0.3.13.aar`。三份补丁重放、10 项投屏行为回归及 8 项真实 AAR helper 行为测试通过；原生四 ABI 与 25 个音频类保持不变。此版将原始输入上限固定为上游的 3 帧，并保留经过长时间等待的最后一个解码画面，不影响 0.3.13 Surface 绑定修复。

- 补丁在固定提交上通过 `git apply --check` 和 `git diff --check`。
- 0.3.7 视频脚本在固定修订的独立干净检出上重新应用，生成的视频 diff 与实际 SDK 源码一致；音频源码不被重置。三个 helper 随脚本复制。Java 包装补丁最后增量构建成功，17 秒；这是缓存后耗时。
- `:stream-webrtc-android:assembleRelease` 成功。
- AAR 包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 的原生库。
- 0.3.7 四 ABI 原生库与 0.3.6 逐字节一致；内部音频工厂与新增视频类已核对。AAR 20616858 字节，SHA-256 `bebba5496bf529ca0c785c331332d20af6d7d6bb0f2e574ca5131ab554116f06`。
- Android 应用使用该本地 AAR 完成 `:app:assembleDebug`。这些均为构建验证，不代表双机功能验证。
