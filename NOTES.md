# NOTES

已验证的事实、环境要求与坑。**只写已经发生的事**，计划写进 `TODOS.md`，设计写进 `docs/design.md`。

## 环境

- JDK 17（AGP 要求），Android SDK `compileSdk 34`。
- 单测在纯 JVM 跑；需要 `android.jar` 桩方法返回默认值时才开 Robolectric。
- 真机验证用墨水屏设备（BOOX）与常规手机各一台——低内存与渲染进程行为差别很大。

## 待验证（M1 开工后回填）

- `Thread.setDefaultUncaughtExceptionHandler` 链式调用后，系统崩溃对话框是否仍正常弹出。
- 崩溃时同步写盘在低端机上是否稳定（写入耗时、是否有 ANR 风险）。
- `onTrimMemory` 在墨水屏设备上的实际触发频率。
- WorkManager 在国产 ROM 后台限制下的补发成功率。

## 坑（预判，待证实后转正）

- 宿主若在 `Application.onCreate` 之前（如 `ContentProvider`）崩溃，本库采不到。
- `applicationIdSuffix = ".debug"` 的宿主，debug 与 release 的 `install_id` 不同，云端会当成两个安装。
