# 0.3.7 双机安装记录

日期：2026-10-13。用户明确要求“两台手机在电脑上直接adb安装”。该授权仅用于本次电脑覆盖安装和包版本核对；产品的手机控制手机路线仍不依赖 ADB/root/Shizuku。

安装文件 `artifacts/SimpleRC-0.3.7-debug.apk`，SHA-256 `2d59288e9eb802e2e8979e0743d9a48d9ad43585a4b12d93fa54c33154b0280c`，安装前已核对。

| 设备 | ADB覆盖安装 | 安装后版本 |
| --- | --- | --- |
| OPPO PLQ110 | Success | 0.3.7 / versionCode10 |
| 小米 21121119SC | Success | 0.3.7 / versionCode10 |

两台均使用 install -r 保留应用数据，没有卸载或清除数据；通过 dumpsys package com.simplerc.app 读取版本。没有启动远控、修改权限或恢复双机测试。安装及版本确认不证明真实流畅度或新策略已验收。
