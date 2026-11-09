# TODOS

> 2026-11-09 收口。本文件只记「还没做」；已验证的事实进 `NOTES.md`，接手与排障看 `HANDOFF.md`。

## 明天从这里开始（2026-11-10）

- [ ] **推送待授权**：`main` 本地 ahead 2（`3ea5f27` P0 体积守卫 / 崩溃路径瘦身、`765a742` 崩溃链守卫 / 时间戳线程安全）。
      推 gitee `origin` 与 `github` 两端后回读引用核对（命令见 `HANDOFF.md` §7）。
- [ ] **P1 第一步：`onTrimMemory`**。现在只认 `TRIM_MEMORY_COMPLETE`，墨水屏上更常来的是 `RUNNING_CRITICAL`；
      注释写了「只在前台记」但代码没判 `tracker.foreground`——两者一起改，附一条 Robolectric 守卫（发一次记一条、同小时第二次不记）。
- [ ] **M2 起头：`AnrWatchdog`**（8 秒无 pong → kind=`anr`，带主线程栈）。
- [ ] 手上没有阻塞项：P0 与崩溃链都已收口，上面三条是「增强」，不是「修复」。

## 已完成（M1，2026-11-09）

- [x] 建仓：README / AGENTS / NOTES / design / LICENSE；gitee + GitHub 双远端并推送
- [x] Gradle wrapper + `monitor/` library module，`./gradlew :monitor:testDebugUnitTest` 通过（现 46 条）
- [x] `MonitorConfig` + `MooMonitor.install` 门面，缺 token 时整体禁用
- [x] 默认未捕获异常链式接管（`runCatching` 包裹、交还原 handler）
- [x] `crash.jsonl` / `queue.jsonl` 双文件双锁、轮转、坏行跳过、`dropped`
- [x] `AppEvent` 手写 JSON + 帧解析，字段对齐 cloud 契约 §4.2
- [x] `Uploader` + `FlushWorker`：批量 50、退避、401/403 禁用、4xx 丢批、429/5xx 重试
- [x] `Redactor` / `DeviceInfo` / `SessionTracker` / `Breadcrumbs` / `Throttle`
- [x] 崩溃风暴闸（同指纹 10 条/小时）、主动上报按天闸（20 条）、禁用状态持久化
- [x] 退到后台立即补发；上报成功留 `intake ok` 日志
- [x] 宿主 `shuhe` 接入：`includeBuild`、`ShuheApp.onCreate`、`onTrimMemory`、`onRenderProcessGone`
- [x] 依赖的 cloud M1 已落地并部署上线（`app_errors` 表 / intake / 后台 / 通知 / 配额 60）
- [x] 真机闭环：BOOX 上 `am crash` → 落盘 → 重启合并 → 生产 2xx → 队列清空

## 已完成（P0，2026-11-09 本轮）

- [x] `err_stack` 截 32KB、`message` 截 8KB（按 UTF-8 字节、不切坏代理对）；批次按累计 512KB 封顶。
      `PayloadLimits.kt` + `AppEvent.exception()` + `Uploader`，第一条再大也单独发，避免卡死队列。
- [x] 崩溃路径瘦身：`install()` 冻结 `DeviceInfo.Snapshot`，崩溃线程不再跑 `StatFs` / `ConnectivityManager` / `resources`。
- [x] 15 条新单测（含 Robolectric 快照守卫），共 39 条；四组变异分别转红 5 / 4 / 1 / 1 条，还原后全绿。
- [x] BOOX 冷启动闭环复验：`crash.jsonl` 2800B、字段齐全 → `intake ok: 1 条` → 队列清空。

## 已完成（2026-11-09 第二轮：崩溃链守卫 + 时间戳线程安全）

- [x] `CrashHandlerTest`（5 条）：记录落盘、原 handler 必被调用、采集抛异常不影响原链路、空 token 不接管、同指纹每小时上限。
      为此把链式接管抽成 `chainedCrashHandler(previous, onCrash)`，并加了仅测试用的 `MooMonitor.resetForTests()`。
- [x] 时间戳线程安全：`AppEvent` / `Breadcrumbs` 的共享 `SimpleDateFormat` 换成 `IsoTime`（ThreadLocal）。
      原实现里 `Breadcrumbs.add` 还在锁外格式化，并发会写出错乱日期或抛异常，再被 `runCatching` 吞成「少一条记录」。
- [x] 五组变异转红 1 / 2 / 1 / 1 / 1 条，还原后 46 条全绿；**并发用例因复现不出竞态而删除**（不 bite 的守卫不留）。

## P0 — 影响「崩溃能否送达」

（暂无）

## P1 — 体验与一致性

- [ ] `onRenderProcessGone` 改成先保存阅读进度再重建、或只重建 WebView（现在 `recreate()` 整页重绘 500–800ms）。
- [ ] `onTrimMemory` 补 `TRIM_MEMORY_RUNNING_CRITICAL`（比 `COMPLETE` 更常触发）；注释写了「前台」但代码没判 `tracker.foreground`。
- [ ] 对齐 `docs/design.md` 与实现：本地合并（设计写了、实现决定不做）、401 是否清队列、`onTrimMemory` 的「只在前台」表述（见 `HANDOFF.md` §4、§8）。
      模块文件清单与测试清单已对齐；批次字节上限、栈截断已对齐。

## 真机待验（人工）

- [ ] 断网 3 次启动后恢复上报
- [ ] WebView 渲染进程被系统真杀（`adb` 触发不了，只能等真实场景或改代码模拟）
- [ ] 后台「数据 → 客户端异常」列表可见性（SDK 侧无读接口）
- [ ] 国产 ROM 后台限制下的补发成功率
- [ ] 装 handler 后系统崩溃对话框仍正常弹出

## M2

- [ ] `AnrWatchdog`：8 秒无 pong 记 `anr`，带主线程栈
- [ ] `capture()` 在宿主三处接线：导入失败、解析失败、同步异常
- [ ] 宿主侧接入文档补到 README（含 composite build 步骤）

## M3

- [ ] `mapping.txt` 上传（等宿主开启 minify 再做，现在 `isMinifyEnabled = false`）
- [ ] 多进程宿主支持评估（是否需要在每个进程各装一次并共享队列）
