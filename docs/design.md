# 设计：moo-monitor-android

> 上报契约以 `moo-scaffold-cloud/docs/app-monitor.md` 为准（数据表、指纹、count 语义、端点与能力）。本文只写 SDK 侧的采集、落盘、上报与边界。

## 1. 定位

一个可以塞进任何 Android 应用的异常采集库。与 `moo-go-monitor` 的取舍一致：**只做采集与可靠上报，不做工作台、不做页面**；云端负责聚合、通知、诊断。

不做的事：

- 不替换宿主的崩溃处理。记录完必须把异常交还给原 handler。
- 不采集 native 崩溃（本库没有 NDK 组件，宿主若带 native 库需自行扩展，本文不承诺）。
- 不做实时通道。进程被杀之后的数据只能等下次启动。

## 2. 前提与约束

| 项 | 值 | 影响 |
|---|---|---|
| `minSdk` | 24 | 不用 `java.time` 之外的新 API；追加写文件足够 |
| 语言 | Kotlin | 无 JNI |
| 依赖 | AndroidX（core / lifecycle / work） | 不引 OkHttp、Gson、Room |
| 进程 | 假定单进程 | 多进程宿主需在每个进程各装一次，本库不做 IPC 汇总 |
| minify | 与宿主一致 | 关闭时堆栈本身可读，符号化不是前置条件 |

## 3. 模块结构

单个 Gradle library module `monitor/`，包名 `com.mooeen.monitor`，与宿主包名无关。

```
monitor/
  build.gradle.kts
  src/main/java/com/mooeen/monitor/
    MooMonitor.kt        门面：install / capture / breadcrumb / screen / flush
    MonitorConfig.kt     端点、token、采样、上限；缺 token 时整体禁用
    CrashHandler.kt      默认未捕获异常 + WebView 渲染进程
    AnrWatchdog.kt       主线程卡死（M2）
    Event.kt             记录模型 + toJson（手写 JSON，不引序列化库）
    EventQueue.kt        JSONL 追加写、轮转、上限、丢弃计数
    Uploader.kt          HttpURLConnection，批量、退避、状态码语义
    FlushWorker.kt       WorkManager 唯一任务
    Breadcrumbs.kt       内存环形缓冲（30 条）
    Redactor.kt          路径 / URL / 键名脱敏
    DeviceInfo.kt        Build、屏幕、堆、存储、网络
    SessionTracker.kt    进程级 session_id + 前台状态
  src/test/java/com/mooeen/monitor/   纯 JVM 单测
```

`MooMonitor` 之外的类型默认 `internal`，对外只暴露门面 + `MonitorConfig` + `Event`（只读）。宿主不应依赖内部结构。

## 4. 采集点

| 来源 | 钩子 | `kind` | 备注 |
|---|---|---|---|
| 未捕获异常 | `Thread.setDefaultUncaughtExceptionHandler` | `crash` | 记录后必须交给原 handler |
| WebView 渲染进程死亡 | `WebViewClient.onRenderProcessGone` | `webview_gone` | 宿主在阅读器/内嵌页里接 |
| 低内存 | `Application.onTrimMemory(TRIM_MEMORY_COMPLETE)` | `low_memory` | 1 小时 1 条，只在前台记 |
| 业务主动上报 | `MooMonitor.capture(e, extras)` | `handled` | 默认关闭，调用点显式开；每 hash 每天上限 20 条 |
| 主线程卡死 | `AnrWatchdog` | `anr` | M2；8 秒无 pong 记一条，带主线程栈 |

### 4.1 崩溃处理链

```kotlin
private val previous = Thread.getDefaultUncaughtExceptionHandler()

Thread.setDefaultUncaughtExceptionHandler { thread, error ->
    runCatching { recorder.recordCrash(thread, error) }   // 自身异常绝不影响原链
    previous?.uncaughtException(thread, error)            // 系统默认：写 tombstone + 弹崩溃框 + 杀进程
}
```

三条纪律：

1. 整个记录过程包在 `runCatching` 里——采集失败不能让崩溃变成「静默退出」。
2. 不调用 `exitProcess`，交回系统默认 handler，保留崩溃对话框。
3. 落盘用同步写（不是协程），进程随时会被杀；写完再交还。

### 4.2 WebView 渲染进程

```kotlin
override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
    MooMonitor.capture(
        kind = "webview_gone",
        extras = mapOf("did_crash" to detail.didCrash(),
                       "priority" to detail.rendererPriorityAtExit())
    )
    return false   // 交回宿主决定重建还是退出
}
```

SDK 不重建 WebView，那是宿主的界面职责。这条对以 WebView 渲染正文的阅读器尤其重要——渲染进程被杀时宿主只看到白屏，没有这条记录就等于没发生过。

## 5. 记录构造

一条 `crash` 在进程内组装（约 2ms）：

- `exception.name` = `error.javaClass.name`；`message` 截 8KB；`stack` = `Log.getStackTraceString(error)` 截 32KB。
- `frames`：解析栈文本，跳过 `java.*` / `android.*` / `kotlin.*` / `kotlinx.*` 帧，保留应用与三方帧（≤60），`app = true` 按宿主 `packageName` 前缀匹配。
- `breadcrumbs`：环形缓冲快照（最近 30 条）。
- `context`：`session_id`、`screen`（`ActivityLifecycleCallbacks` 记录的当前 Activity 类名）、`foreground`、`network`（`ConnectivityManager` 只读 `activeNetworkInfo.type`）、`heap_used/max`、`storage_free`（`StatFs(filesDir)`）。
- `payload`：`thread`、`uptime_ms`、`is_low_ram`、`dropped`（本地丢弃计数）。

**不进记录**：用户内容、文件路径（除私有目录相对路径）、账号凭据、任何业务数据。`screen` 只放类名，不放书名这类业务字段。

## 6. 落盘

`filesDir/monitor/queue.jsonl`，一行一条 JSON：

- 追加写，`synchronized` 锁保护；崩溃路径用同一把锁，避免写出半行。
- 上限 200 条 / 2MB。超限时读全文、保留最后 150 条、重写，差额累加到 `dropped`。
- 单条写失败（磁盘满、权限）→ 静默丢弃，`dropped++`，不再抛。

**不用 Room**：崩溃发生时数据库可能正处于未提交的 WAL 状态，追加写文件比事务安全得多，也不给宿主启动路径多挂一个 DB 依赖。

## 7. 上报

- `FlushWorker`：`OneTimeWorkRequest`，`NetworkType.CONNECTED`，唯一任务名 `moo-monitor-flush`，`ExistingWorkPolicy.KEEP`。
- 触发：启动后 10 秒、`onStop`（退后台）、手动 `flush()`。
- 批次 ≤50 条 / ≤512KB，先到先发。
- 发送前按指纹分组：同 hash 合并 `count`，只发一条——减少体积，也让服务端增量语义更准。
- 状态码：`2xx` 删批；`401/403` 清队列并持久化 `disabled`，之后 `install()` 直接返回；`422` 丢批；`429` 读 `Retry-After`；`5xx`/网络错误指数退避（30s、2m、8m、30m），最多 6 次。
- `HttpURLConnection` 而非 OkHttp：为 200 行上报引一个网络库不划算。连接/读取超时各 10 秒。

## 8. 脱敏

客户端先做一遍，服务端还会再做一遍：

| 目标 | 规则 |
|---|---|
| 路径 | `/data/user/0/<pkg>/…` → `~/…`；`/storage/emulated/0/…` → `~/sdcard/…` |
| URL query | 键名命中 `password|pwd|token|secret|api_key|authorization|sig` → `***` |
| 消息 | `https?://\S+` → `[url]`；32 位以上 hex/base64 串 → `[blob]` |
| 设备标识 | `install_id` = 本地随机 UUID 的 SHA-256；**不用** `ANDROID_ID`，避免与其它上报口径可关联 |

## 9. 接入

```kotlin
class HostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MooMonitor.install(this, MonitorConfig(
            endpoint = "https://c.mooeen.com",
            token = BuildConfig.MOO_MONITOR_TOKEN,
            release = BuildConfig.VERSION_NAME,
            build = BuildConfig.VERSION_CODE.toString(),
            env = if (BuildConfig.DEBUG) "debug" else "release"
        ))
    }
}
```

界面追踪：宿主在关键 Activity 的 `onResume` 调 `MooMonitor.screen("ReaderActivity")`；不调也行，SDK 会从 `ActivityLifecycleCallbacks` 自动取类名。

构建注入 token（不入库）：

```kotlin
buildConfigField("String", "MOO_MONITOR_TOKEN",
    "\"${project.findProperty("mooMonitorToken") ?: ""}\"")
```

```bash
./gradlew assembleRelease -PmooMonitorToken="$(cat /Volumes/dev/build_tokens/shuhe-monitor)"
```

## 10. 分发

M1 阶段用 **composite build**，宿主 `settings.gradle.kts` 里：

```kotlin
includeBuild("../moo-monitor-android")
```

不改宿主的依赖发布流程，也不要求先发 maven。等第二个宿主出现再谈发布（`mavenLocal` 或私有仓库）。

## 11. 测试

| 层 | 内容 |
|---|---|
| 单元 | `EventTest`：JSON 形状、截断、`Redactor` 规则 |
| 单元 | `EventQueueTest`：追加、轮转、`dropped` 计数、半行容错 |
| 单元 | `UploaderTest`：`Transport` 接口注入假实现，覆盖 200/401/422/500/超时 |
| 定向 | `CrashHandlerTest`：装 handler 后抛异常，断言队列里有一条 `crash` 且原 handler 被调用 |
| 真机 | 宿主里制造崩溃，确认后台 30 秒内出现；断网 3 次启动后恢复上报 |

单测证明不了「进程被杀之前写进去了」，真机必跑。

## 12. 分阶段

- **M1**：崩溃 + WebView + 低内存采集，落盘、上报、cloud 建表与后台。
- **M2**：`AnrWatchdog`、`capture()` 业务接线示例（导入失败、解析失败、同步异常）。
- **M3**：`mapping.txt` 上传（等宿主真的开 minify 再做，否则是无用功）。

## 13. 边界

- 采不到：`ContentProvider` 初始化崩溃（早于 `Application.onCreate`）、系统 ANR 弹窗、被 `SIGKILL`、native 崩溃。
- 首次启动就崩的包可能一条都发不出去——队列在本地，用户不再打开就永远不发。
- 墨水屏设备 `onTrimMemory` 触发频繁，所以 `low_memory` 做了 1 小时 1 条的闸，否则会淹掉真正的崩溃。
- 采集会让崩溃进程多活几十毫秒，极端情况下可能改变系统 tombstone 的时序；这是进程内采集的共性代价，不是本库的缺陷。
- 多进程宿主：本库不做跨进程汇总，每个进程各发各的（`session_id` 不同，云端会分开聚合）。
