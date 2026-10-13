# SimpleRC

Android 控制 Android 的远程控制项目。当前版本 `0.3.1`（versionCode 4）已生成 debug APK，配对、视频、控制与内部音频代码均已接线和编译，尚未经过双机实测。

目录：`android/` 客户端；`server/` Go 服务与迁移；`deploy/` PostgreSQL/Redis/Coturn；`webrtc-patch/` 可复现内部音频补丁、固定修订和校验值；`docs/` 状态、决策、审查和验收资料。

APK：`android/app/build/outputs/apk/debug/app-debug.apk`。服务器下载：`https://49.233.43.167:9443/simplerc-download/SimpleRC-0.3.1-debug.apk`。

构建、部署、已知限制和人工验收步骤分别见 `docs/BUILDING.md`、`docs/DEPLOYMENT.md`、`docs/STATE.md` 与 `docs/ACCEPTANCE_CHECKLIST.md`。
