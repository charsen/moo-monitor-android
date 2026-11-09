# NOTES

已验证的事实、环境要求与坑。**只写已经发生的事**，计划写进 `TODOS.md`，设计写进 `docs/design.md`。

## 环境

- JDK 17（AGP 要求），Android SDK `compileSdk 34`。
- 单测在纯 JVM 跑；需要 `android.jar` 桩方法返回默认值时才开 Robolectric。
- 真机验证用墨水屏设备（BOOX）与常规手机各一台——低内存与渲染进程行为差别很大。

## 已验证（2026-11-09 P0 本轮）

- 链式 handler 不吞崩溃：BOOX 上 `am crash` 后系统仍 `Showing crash dialog for package com.mooeen.shuhe.debug`，
  `crash.jsonl` 同时落盘 2800 字节。别把「弹了崩溃框」当成 SDK 没接管。
- 崩溃闭包只读启动快照：落盘记录的 `device` 七项与 `context` 八项（含 `network` / `storage_free` / `heap_max` / `heap_used`）
  与改前一致，说明 `DeviceInfo.Snapshot` 没丢字段；冷启动后 `intake ok: 1 条` + `FlushWorker SUCCESS` + 队列清空完成闭环。
- 39 条单测含 1 个 Robolectric 类；四组变异（不截断 / 不按字节切批 / build 忽略快照 / context 不读缓存）
  分别转红 5 / 4 / 1 / 1 条，还原后全绿。

## 坑（已证实）

- **崩溃对话框会挡住「重启」**：`am start` 复用仍在崩溃态的同一 PID，`Application.onCreate` 不重跑，
  `crash.jsonl` 不会被合并。先 `input keyevent KEYCODE_BACK` + `am force-stop`，再冷启动。
- 宿主若在 `Application.onCreate` 之前（如 `ContentProvider`）崩溃，本库采不到。
- `applicationIdSuffix = ".debug"` 的宿主，debug 与 release 的 `install_id` 不同，云端会当成两个安装。

## 待验证

- 崩溃时同步写盘在低端机上是否稳定（写入耗时、是否有 ANR 风险）。
- `onTrimMemory` 在墨水屏设备上的实际触发频率。
- WorkManager 在国产 ROM 后台限制下的补发成功率。
