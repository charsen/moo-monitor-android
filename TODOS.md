# TODOS

## 建仓

- [x] 目录、README、AGENTS、NOTES、设计文档（2026-11-09）
- [ ] 建远端仓库（gitee `charsen/moo-monitor-android`，与其它 monitor 仓一致）并推送首个提交
- [x] 生成 Gradle wrapper 与 `monitor/` module 骨架，`./gradlew :monitor:testDebugUnitTest` 通过（18 条）

## M1（未开工）

- [x] `MonitorConfig` + `MooMonitor.install` 门面，缺 token 时整体禁用
- [x] `CrashHandler`：默认未捕获异常链式接管，`runCatching` 包裹，交还原 handler
- [ ] `WebViewClient.onRenderProcessGone` 接入示例与 `webview_gone` 记录
- [x] `onTrimMemory` → `low_memory`，1 小时 1 条
- [x] `Event` 模型与手写 JSON，字段与 cloud 契约 §4.2 对齐
- [x] `EventQueue`：JSONL 追加写、200 条 / 2MB 轮转、`dropped` 计数
- [x] `Uploader` + `FlushWorker`：批量、退避、401/403 禁用、429 退避
- [x] `Redactor`、`DeviceInfo`、`SessionTracker`、`Breadcrumbs`
- [x] 单测：Event / EventQueue / Uploader / CrashHandler
- [ ] 真机：崩溃上报闭环、断网恢复、渲染进程被杀
- [ ] 依赖 cloud 侧 M1（`moo-scaffold-cloud/docs/app-monitor.md` §11）先落地

## M2

- [ ] `AnrWatchdog`：8 秒无 pong 记 `anr`，带主线程栈
- [ ] `capture()` 在宿主三处接线：导入失败、解析失败、同步异常
- [ ] 宿主侧接入文档补到 README（含 composite build 步骤）

## M3

- [ ] `mapping.txt` 上传（等宿主开启 minify 再做）
- [ ] 多进程宿主支持评估（是否需要在每个进程各装一次并共享队列）
