# Linux / WSL2 可复现构建方案（未执行）

## 资源预估

- Ubuntu 22.04 或 24.04 x86_64；Windows 可使用 WSL2 Ubuntu，源码必须放在 WSL2 的 Linux 文件系统中。
- 8 核 CPU、16 GB 内存为建议下限；并行构建更适合 32 GB。
- 预留 80 GB；源码与依赖常见约 25–40 GB，单一 Android 构建目录和缓存约 15–30 GB，保留回滚空间后按 80 GB 规划。
- 首次同步及单 ABI/通用 AAR 构建通常 1–4 小时；慢速网络或低配置可能 4–8 小时。增量构建通常 10–60 分钟。以上是规划值，固定修订后需记录实际数据。

## 固定和验证

1. 安装 Git、Python 3、curl、ca-certificates、unzip、build-essential。
2. 克隆 Chromium `depot_tools` 并固定其提交；把目录加入 PATH。
3. `fetch --nohooks webrtc_android`，进入 `src` 后 checkout 与标准 AAR 对应的上游提交，并在 `UPSTREAM_REVISION` 记录完整 SHA。
4. `gclient sync -D --revision src@<full-sha>`，保存 `.gclient` 和 sync 输出摘要。
5. `git apply --check ../patches/0001-audioplaybackcapture-factory.patch` 后才应用补丁。
6. 用上游 Android AAR 构建脚本和固定 GN 参数构建 arm64-v8a、armeabi-v7a、x86_64；命令必须在固定修订后按该修订的 `tools_webrtc/android/build_aar.py --help` 核对，不能把随版本变化的参数写死为已验证命令。
7. 复制 AAR 到 `android/app/libs/stream-webrtc-android-simplerc.aar`，生成 SHA-256、列出 AAR 内容、保存构建提交和 GN args。
8. 将 Gradle 依赖切换放在单独提交中；标准和补丁 AAR 必须实现同一应用层接口。

当前未获 WebRTC 构建授权，因此 `UPSTREAM_REVISION` 和 AAR SHA-256 保持“未生成”。
