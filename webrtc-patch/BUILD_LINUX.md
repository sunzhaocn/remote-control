# Linux / WSL2 可复现构建方案（已验证）

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
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export ANDROID_SDK_ROOT=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :stream-webrtc-android:assembleRelease --no-daemon
sha256sum stream-webrtc-android/build/outputs/aar/stream-webrtc-android-release.aar
```

将产物复制为 `android/app/libs/stream-webrtc-android-simplerc-1.3.10.aar`，并核对 `SHA256SUMS`。AAR 直接随 Git 仓库保存，不使用 Git LFS 或付费制品服务，因此不产生存放费用。

## 已验证内容

- 补丁在固定提交上通过 `git apply --check` 和 `git diff --check`。
- `:stream-webrtc-android:assembleRelease` 成功。
- AAR 包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 的原生库。
- Android 应用使用该本地 AAR 完成 `:app:assembleDebug`。这些均为构建验证，不代表双机功能验证。
