# moo-monitor-android

Android 端异常采集 SDK：崩溃、渲染进程死亡、低内存、主线程卡死，落盘后批量上报到 [Moo Scaffold Cloud](https://c.mooeen.com)。

与 `moo-monitor-laravel`（PHP）、`moo-go-monitor`（Go）、`moo-monitor-vue`（浏览器）同属一套监控体系，共用 cloud 的 intake 契约，不重复造云端。

## 状态

**设计完成，代码未开工。** 设计与边界见 [`docs/design.md`](docs/design.md)，实施顺序见 [`TODOS.md`](TODOS.md)。

## 定位

- 独立库：`com.mooeen.monitor`，宿主只需在 `Application.onCreate` 调一次 `install`。
- 不依赖任何三方崩溃库（不引 Sentry / Bugly / ACRA），只用 AndroidX 与平台 API。
- 采集失败不影响宿主业务：所有采集路径包在 `runCatching` 里，绝不改变原崩溃处理链。

## 快速接入（宿主视角）

```kotlin
class ShuheApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MooMonitor.install(this, MonitorConfig.fromBuildConfig())
    }
}
```

```kotlin
// app/build.gradle.kts
buildConfigField("String", "MOO_MONITOR_TOKEN",
    "\"${project.findProperty("mooMonitorToken") ?: ""}\"")
```

token 为空时 SDK 整体禁用，不发任何请求。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/design.md`](docs/design.md) | 采集点、记录格式、落盘、上报、脱敏、测试、边界 |
| [`AGENTS.md`](AGENTS.md) | 本仓协作与编码约束 |
| [`NOTES.md`](NOTES.md) | 环境、验证边界、已知坑 |
| [`TODOS.md`](TODOS.md) | 分阶段实施清单 |

云端契约（数据表、指纹规则、count 语义、端点与能力）以 `moo-scaffold-cloud/docs/app-monitor.md` 为唯一来源，本仓不复制。

## 首个接入方

书禾 Android（`/Volumes/dev/wwwroot/shuhe`）。

## 仓库与许可

双远端：gitee `charsen/moo-monitor-android`（主）、GitHub `charsen/moo-monitor-android`（镜像）。

MIT，见 [`LICENSE`](LICENSE)。
