# SimpleRC

Android 控制 Android 的远程控制项目。当前版本 `0.2.0`（versionCode 2）已生成 debug APK，配对、视频与控制链路已完成代码接线并部署，尚未经过双机实测。内部音频补丁 AAR 尚未构建，因此内部音频明确不可用。

目录：`android/` 客户端；`server/` Go 服务与迁移；`deploy/` PostgreSQL/Redis/Coturn 模板；`webrtc-patch/` 未构建的内部音频补丁资料；`docs/` 状态、决策、审查和验收资料。

APK：`android/app/build/outputs/apk/debug/app-debug.apk`。服务器下载：`https://49.233.43.167:9443/simplerc-download/SimpleRC-0.2.0-debug.apk`。

从 `docs/STATE.md`、`docs/REVIEW_GUIDE.md` 和 `docs/REQUIREMENTS_TRACEABILITY.md` 开始审查。
