# SimpleRC

Android 控制 Android 的远程控制项目。当前本地候选版本 `0.3.3`（versionCode 6）已编译，修复整页滚动、本机保存/注册分离、SDP/ICE 和采集启动顺序，等待双机复测。

目录：`android/` 客户端；`server/` Go 服务与迁移；`deploy/` PostgreSQL/Redis/Coturn；`webrtc-patch/` 可复现内部音频补丁、固定修订和校验值；`docs/` 状态、决策、审查和验收资料。

APK：`android/app/build/outputs/apk/debug/SimpleRC-0.3.3-debug.apk`。按用户要求仅更新后端，不上传 APK；服务器 APK 仍为 0.3.1。

构建、部署、已知限制和人工验收步骤分别见 `docs/BUILDING.md`、`docs/DEPLOYMENT.md`、`docs/STATE.md` 与 `docs/ACCEPTANCE_CHECKLIST.md`。
