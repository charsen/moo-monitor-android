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

## 已验证（2026-11-09 第二轮：崩溃链 + 时间戳）

- 崩溃接管链的五条契约都有守卫（`CrashHandlerTest`，Robolectric）：记录落盘、原 handler 被调用、
  采集自身抛异常不影响原链路、无前任 handler 不炸、空 token 不接管。五组变异分别转红 1 / 2 / 1 / 1 / 1 条。
- **`SimpleDateFormat` 的竞态在 JVM 上复现不出来**：8 线程 × 3000 轮 `CyclicBarrier` 对齐后同时 `format`，
  换成共享实例仍是全绿（亚毫秒的窗口太窄）。所以这条只能靠结构守卫——反射断言 `IsoTime` / `AppEvent` /
  `Breadcrumbs` 都不持有 `SimpleDateFormat` 字段；变异（改回共享）立即转红。
  **结论：并发 bug 的守卫不要指望压测，要压成「不许有这个字段」。**
- **BOOX 真机复验（17:52–17:53，本轮改动后重装）**：`am crash` → `crash.jsonl` 2800 字节；
  `occurred_at=2026-11-09T09:52:22.970Z`（UTC，格式合契约）、`breadcrumbs` 3 条时间戳全部合契约、
  `device` 七键、`context` 含 `network=wifi` / `storage_free` / `heap_max` / `heap_used` / `screen` / `foreground`。
  冷启动 `17:53:34 D MooMonitor: intake ok: 1 条` + `Worker result SUCCESS` + `files/monitor` 清空。
- **崩溃后如果在 `logcat -c` 之后再冷启动，会把 `intake ok` 冲掉**：WorkManager 常在崩溃后 1–2 秒就
  自动拉起进程并完成上报。要么崩溃前就清 buffer、之后一直不清，要么接受「只能靠队列清空间接证明」。
- `MooMonitor` 是进程内单例、`install()` 幂等，而 Robolectric 会跨测试类复用 sandbox 类加载器，
  所以同一 JVM 里要覆盖多种安装场景必须先 `resetForTests()`；默认 handler 也要在 `@After` 还原，否则污染后续测试类。

## 坑（已证实）

- **崩溃对话框会挡住「重启」**：`am start` 复用仍在崩溃态的同一 PID，`Application.onCreate` 不重跑，
  `crash.jsonl` 不会被合并。先 `input keyevent KEYCODE_BACK` + `am force-stop`，再冷启动。
- 宿主若在 `Application.onCreate` 之前（如 `ContentProvider`）崩溃，本库采不到。
- `applicationIdSuffix = ".debug"` 的宿主，debug 与 release 的 `install_id` 不同，云端会当成两个安装。

## 待验证

- 崩溃时同步写盘在低端机上是否稳定（写入耗时、是否有 ANR 风险）。
- `onTrimMemory` 在墨水屏设备上的实际触发频率。
- WorkManager 在国产 ROM 后台限制下的补发成功率。

## 队列与采集边界

- 2026-11-10：先截断再识别凭据会残留JWT，键名本身也可能含凭据；本地队列前的清洗必须覆盖键和值。JVM对长引号值的分支重复正则会栈溢出，采用占有式量词；超扫描预算只保留完整行，不切开一行中的凭据。跨SDK假数据位于测试resources。

- 2026-11-10：发送确认必须对应发送时的原始行；坏行跳过后按条数删文件前缀、或上传期间先轮转再删前缀，都会造成误删/重发。队列重写须原子替换，崩溃合并先提交目标再清源；目标不可写的目录占位可在 JVM 稳定复现源丢失。
- 2026-11-10：脱敏需要覆盖真实的 exception → scrub → frames 链路；直接测 frames(rawStack) 会漏掉长类名/源码名被当成长凭据的损坏。长凭据匹配须保留点号连接的 Java 标识符。
- 2026-11-10：旋转时 Activity 的 stop 仍需平衡 started 计数，只跳过后台通知；整个 stop 提前 return 会让 foreground 永久为 true。

- 2026-11-10：仅限制递归深度和“每个容器 30 项”不能防止重复引用图按扇出展开；同一次 extras 清洗必须共享总节点预算。用共享 Map 和读取计数可在爆内存前稳定捕获旧实现的无界遍历。
- 2026-11-10：保留点号连接的类名后，通用 blob 正则不会清理独立 JWT；JWT 必须在保留源码标识符的同时用专门规则识别。
- 2026-11-10：BOOX 上真实 Java 未捕获异常同步落盘并交还系统后进程退出，重启成功补发；真实 chrome://crash 的 renderer 回调可采集并维持宿主存活。设备场景、一次性耗时采样和隔离 APK 证据见 HANDOFF 的第二轮记录；不等于真实 OOM 或国产 ROM 长期休眠验证。
