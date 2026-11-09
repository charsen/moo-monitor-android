# Moo Android Monitor 协作与编码要求

> 规则来源：通用协作、授权、Git 与测试规则见 `~/.agents/AGENTS.md`（每轮自动加载）。本文件只写本仓特有约束，不复制全局规则；冲突时以本文件为准。

## 项目职责

- Android 端异常采集 SDK：崩溃、渲染进程死亡、低内存、主线程卡死，落盘后批量上报到 Moo Scaffold Cloud。
- 与 `moo-monitor-laravel` / `moo-go-monitor` / `moo-monitor-vue` 同属一套体系，共用 cloud 的 intake 契约；**不在本仓另写一份契约**，上报格式以 `moo-scaffold-cloud/docs/app-monitor.md` 为准。
- 独立库，不导入宿主业务代码；宿主只通过 `MooMonitor` 门面与 `MonitorConfig` 交互。

## 采集红线

- 采集失败不能改变宿主行为：所有采集路径包在 `runCatching` 里，崩溃路径必须把异常交还原 handler，不得 `exitProcess`、不得吞掉崩溃。
- 崩溃时用同步写落盘，不用协程、不用 Room、不等待网络。
- 不上报用户内容、书籍/文件路径、账号凭据、任何业务字段；`screen` 只放类名。
- `install_id` 用本地随机 UUID 的 SHA-256，不用 `ANDROID_ID`、不用广告标识。
- token 从构建参数注入，**不进源码、不进 git、不进日志**。

## 编码与阅读体验

- 优先按业务顺序展开：输入 → 判断 → 落盘/上报 → 输出。读主函数能看清失败、重试与清理条件。
- 不为压缩行数拆成一串只转发参数的小函数；提取要有理由（独立规则、事务边界、真实复用、外部适配）。
- 对外只暴露门面与配置；内部类型 `internal`，不为了测试方便把实现细节变成公开 API。
- 注释解释业务原因与协议限制，不复述代码。
- 不引新依赖解决已有平台能力能解决的问题（HTTP 用 `HttpURLConnection`，JSON 手写）。

## 变更与验收

- 开工先读 `docs/design.md`、`NOTES.md`、`TODOS.md`；实现与文档冲突时，先确认哪边是当前意图，再改另一边。
- 任何采集能力变更都要同步 `docs/design.md` 的采集点表与边界章节；边界只写「已知采不到什么」，不写「应该能采到」。
- 单测必须能在纯 JVM 跑（Robolectric 仅在确需 Android 类时使用）；真机验证结论写进 `NOTES.md`，不写进 `design.md`。
- 提交前跑 `./gradlew :monitor:test`。
