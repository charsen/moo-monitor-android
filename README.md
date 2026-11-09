# moo-monitor-android

Android 端异常采集 SDK：崩溃、渲染进程死亡、低内存、主线程卡死，落盘后批量上报到 [Moo Scaffold Cloud](https://c.mooeen.com)。

与 `moo-monitor-laravel`（PHP）、`moo-monitor-go`（Go）、`moo-monitor-vue`（浏览器）、`moo-monitor-ios`（iOS）同属一套监控体系，共用 cloud 的 intake 契约，不重复造云端。

## 状态

**M1 已实现：46 条单测通过，已在 BOOX 真机跑通「崩溃 → 落盘 → 上报 → 云端 2xx」闭环，宿主 `shuhe` 已接入。**
**P0 体积守卫已落地**：`exception` 按 UTF-8 字节截断（message 8KB / stack 32KB）、批次按 50 条与 512KB 先到者切批；
崩溃路径只读启动时冻结的设备快照，不在崩溃线程上做磁盘 IO / Binder 调用。本轮同一台 BOOX 复验闭环通过。
崩溃接管链有独立守卫（记录落盘 / 原 handler 必被调用 / 采集抛异常不影响原链路 / 空 token 不接管 / 风暴闸），
时间戳走 ThreadLocal formatter——`SimpleDateFormat` 非线程安全，而写时间戳的线程天然不止一个。
接手与排障先读 [`HANDOFF.md`](HANDOFF.md)；设计与边界见 [`docs/design.md`](docs/design.md)，实施顺序见 [`TODOS.md`](TODOS.md)。

> `docs/design.md` 是设计稿，仍有差异的条目（本地合并、401 是否清队列、模块文件名）见 `HANDOFF.md` §4、§8；
> 栈截断与批次字节上限本轮已按设计落地。

## 定位

- 独立库：`com.mooeen.monitor`，宿主只需在 `Application.onCreate` 调一次 `install`。
- 不依赖任何三方崩溃库（不引 Sentry / Bugly / ACRA），只用 AndroidX 与平台 API。
- 采集失败不影响宿主业务：所有采集路径包在 `runCatching` 里，绝不改变原崩溃处理链。

## 快速接入（宿主视角）

```kotlin
// settings.gradle.kts（M1 用复合构建，不需要先发 maven）
includeBuild("../moo-monitor-android")

// app/build.gradle.kts
implementation("com.mooeen.monitor:monitor:0.1.0")
buildConfigField("String", "MOO_MONITOR_TOKEN",
    "\"${project.findProperty("mooMonitorToken") ?: ""}\"")
```

```kotlin
class ShuheApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MooMonitor.install(this, MonitorConfig(
            endpoint = "https://c.mooeen.com",
            token = BuildConfig.MOO_MONITOR_TOKEN,
            app = "shuhe",
            release = BuildConfig.VERSION_NAME,
            build = BuildConfig.VERSION_CODE.toString(),
            env = if (BuildConfig.DEBUG) "debug" else "release",
        ))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MooMonitor.onTrimMemory(level)
    }
}
```

```bash
./gradlew assembleDebug -PmooMonitorToken=<token>
```

token 为空时 SDK 整体禁用，不发任何请求。宿主还需要一枚只勾 `app_errors` 能力的云端 token。

## 文档

| 文档 | 内容 |
|---|---|
| [`HANDOFF.md`](HANDOFF.md) | **接手首选**：现状快照、代码地图、跨仓依赖、真机验证证据、待办优先级、环境坑 |
| [`docs/design.md`](docs/design.md) | 采集点、记录格式、落盘、上报、脱敏、测试、边界（设计意图） |
| [`AGENTS.md`](AGENTS.md) | 本仓协作与编码约束 |
| [`NOTES.md`](NOTES.md) | 环境、真机验证结论、已知坑 |
| [`TODOS.md`](TODOS.md) | 分阶段实施清单 |

云端契约（数据表、指纹规则、count 语义、端点与能力）以 `moo-scaffold-cloud/docs/app-monitor.md` 为唯一来源，本仓不复制。

## 首个接入方

书禾 Android（`/Volumes/dev/wwwroot/shuhe`）。

## 仓库与许可

双远端：gitee `charsen/moo-monitor-android`（主）、GitHub `charsen/moo-monitor-android`（镜像）。

MIT，见 [`LICENSE`](LICENSE)。
