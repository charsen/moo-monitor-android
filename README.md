# moo-monitor-android

Android 端异常采集 SDK：崩溃、渲染进程死亡与低内存信号，落盘后批量上报到 [Moo Scaffold Cloud](https://c.mooeen.com)。

与 `moo-monitor-laravel`（PHP）、`moo-monitor-go`（Go）、`moo-monitor-vue`（浏览器）、`moo-monitor-ios`（iOS）同属一套监控体系，共用 cloud 的 intake 契约，不重复造云端。

## 状态

M1 已接入书禾 Android。崩溃、WebView 渲染进程死亡与旧系统低内存信号已实现；ANR watchdog 尚未实现。

可靠性实现包括双日志、原子合并/重写、发送收据确认、逐条回执、队列条数/字节预算、脱敏和退避。
崩溃仍交还系统处理链；同步写盘和极端 OOM 的实机边界见 [`docs/design.md`](docs/design.md)。
本轮审核与定向验证见 [`HANDOFF.md`](HANDOFF.md)，后续任务见 [`TODOS.md`](TODOS.md)。

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
            inAppPackage = "com.mooeen.shuhe", // 源码 namespace，不含 debug applicationId 后缀
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

token 为空时 SDK 整体禁用，不发任何请求。宿主需要只具备客户端公开能力的云端 token；安装身份和队列不得参与系统备份，配置见设计 §13。

## 文档

| 文档 | 内容 |
|---|---|
| [`HANDOFF.md`](HANDOFF.md) | **接手首选**：现状快照、代码地图、跨仓依赖、真机验证证据、待办优先级、环境坑 |
| [`docs/design.md`](docs/design.md) | 采集点、记录格式、落盘、上报、脱敏、测试、边界（设计意图） |
| [`AGENTS.md`](AGENTS.md) | 本仓协作与编码约束 |
| [`NOTES.md`](NOTES.md) | 环境、真机验证结论、已知坑 |
| [`TODOS.md`](TODOS.md) | 分阶段实施清单 |

云端契约（数据表、指纹规则、count 语义、端点与能力）以 `moo-scaffold-cloud/docs/app-monitor.md` 为唯一来源，本仓不复制。


## 隔离设备测试

显式构建测试夹具：`./gradlew -PmonitorDeviceQa :device-qa:assembleDebug`，再执行
`python3 tools/device-qa/run.py --serial <设备序列号>`。默认构建不会加载该模块。

脚本仅操作 `com.mooeen.monitor.deviceqa` 和本机临时 SQLite，使用固定的本地 fixture token，不读取生产 token。
它会安装/清理该测试包数据，设置并最终移除设备端 tcp:18764 的 adb reverse；请勿把该端口用于其他任务。
需要同级 moo-scaffold-cloud、PHP、JDK17、Android SDK 与已授权设备。结果保存在 ignored 的 `build/monitor-deviceqa-*/`。

## 首个接入方

书禾 Android（`/Volumes/dev/wwwroot/shuhe`）。

## 仓库与许可

双远端：gitee `charsen/moo-monitor-android`（主）、GitHub `charsen/moo-monitor-android`（镜像）。

MIT，见 [`LICENSE`](LICENSE)。
