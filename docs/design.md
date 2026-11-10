# 设计：moo-monitor-android

> 上报契约以 `moo-scaffold-cloud/docs/app-monitor.md` 为准（数据表、指纹、count 语义、端点与能力）。本文只写 SDK 侧的采集、落盘、上报与边界。

## 1. 定位

一个可以塞进任何 Android 应用的异常采集库。与 `moo-monitor-go` 的取舍一致：**只做采集与可靠上报，不做工作台、不做页面**；云端负责聚合、通知、诊断。

不做的事：

- 不替换宿主的崩溃处理。记录完必须把异常交还给原 handler。
- 不采集 native 崩溃（本库没有 NDK 组件，宿主若带 native 库需自行扩展，本文不承诺）。
- 不做实时通道。进程被杀之后的数据只能等下次启动。

## 2. 前提与约束

| 项 | 值 | 影响 |
|---|---|---|
| `minSdk` | 24 | 只使用该 API 级别支持的平台能力 |
| 语言 | Kotlin | 无 JNI |
| 依赖 | AndroidX（core / lifecycle / work） | 不引 OkHttp、Gson、Room |
| 进程 | 假定单进程 | 仅在主进程安装；文件锁是进程内锁，不支持多进程共写 |
| minify | 与宿主一致 | 关闭时堆栈本身可读，符号化不是前置条件 |

## 3. 模块结构

单个 Gradle library module `monitor/`，包名 `com.mooeen.monitor`，与宿主包名无关。

初稿是一个职责一个文件；实现时为了少几层跳转合并了（2026-11-09 对齐为下表，`HANDOFF.md` §4 同步）：

```
monitor/
  build.gradle.kts
  src/main/java/com/mooeen/monitor/
    MooMonitor.kt        门面（install / capture / screen / flush）+ 崩溃接管链 + FlushWorker
    MonitorConfig.kt     端点、token、上限；缺 token 时整体禁用
    AppEvent.kt          记录组装 + exception() 脱敏截断 + frames() 栈解析（手写 JSON）
    MonitorStore.kt      queue.jsonl / crash.jsonl 双文件双锁、轮转、丢弃计数
    Uploader.kt          批量与状态码语义（批次数、退避在 Worker 侧）
    Transport.kt         接口 + HttpURLConnection 实现（可注入假实现做单测）
    PayloadLimits.kt     UTF-8 字节截断、按字节切批
    DeviceInfo.kt        Build / 屏幕 / 堆 / 存储 / 网络；Snapshot 供崩溃路径只读
    IsoTime.kt           ISO-8601 UTC 时间戳（ThreadLocal formatter）
    Breadcrumbs.kt       内存环形缓冲（30 条）
    Redactor.kt          路径 / URL / 键名脱敏
    SessionTracker.kt    进程级 session_id + 前台状态
    Throttle.kt          纯函数计数（崩溃小时闸 / 主动上报天闸）
  src/test/java/com/mooeen/monitor/   纯 JVM 单测 + 2 个 Robolectric 类
```

`AnrWatchdog.kt`（M2）尚未存在。`MooMonitor` 之外的类型默认 `internal`，对外只暴露门面 + `MonitorConfig`。
宿主不应依赖内部结构。

## 4. 采集点

| 来源 | 钩子 | `kind` | 备注 |
|---|---|---|---|
| 未捕获异常 | `Thread.setDefaultUncaughtExceptionHandler` | `crash` | 记录后必须交给原 handler |
| WebView 渲染进程死亡 | `WebViewClient.onRenderProcessGone` | `webview_gone` | 宿主在阅读器/内嵌页里接 |
| 低内存 | `Application.onTrimMemory` | `low_memory` | 前台 `RUNNING_CRITICAL` 或后台 `COMPLETE`，共享 1 小时 1 条的闸；不是任意 `>=` 等级 |
| 业务主动上报 | `MooMonitor.capture(e, extras)` | `handled` | 默认关闭，调用点显式开；按异常类型与调用点每天上限 20 条；非异常事件按 kind，每天合计上限 100 条 |
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
    // 宿主必须使旧回调失效，并清理已死亡的实例，再恢复页面。
    (view.parent as? ViewGroup)?.removeView(view)
    view.destroy()
    recreate()
    return true
}
```

SDK 不重建 WebView，那是宿主的界面职责。这条对以 WebView 渲染正文的阅读器尤其重要——渲染进程被杀时宿主只看到白屏，没有这条记录就等于没发生过。

## 5. 记录构造

> 2026-11-09 已落地：`AppEvent.exception()` 在脱敏后按 UTF-8 字节截断（预算含结尾标记、不切坏代理对），
> `frames` 与 `stack` 用同一份文本；`device` / `context` 的静态部分由 `DeviceInfo.Snapshot` 在 `install()` 冻结，
> 崩溃线程不再查 `StatFs` 与 `ConnectivityManager`。

一条 `crash` 在进程内组装（没有实机耗时保证）：

- `exception.name` = `error.javaClass.name`；`message` 截 8KB；`stack` = `Log.getStackTraceString(error)` 截 32KB。
- `frames`：解析同一份脱敏/截断后的栈文本，保留应用、三方与系统帧（≤60）；`app = true` 匹配配置 `inAppPackage + "."`，未填写时回退 `applicationId + "."`，不误认相似包名；debug/QA 后缀与源码 namespace 不同时必须显式填写。
- `breadcrumbs`：环形缓冲快照（最近 30 条）。
- `context`：`session_id`、`screen`（`ActivityLifecycleCallbacks` 记录的当前 Activity 类名）、`foreground`、`network`（`ConnectivityManager` 只读 `activeNetworkInfo.type`）、`heap_used/max`、`storage_free`（`StatFs(filesDir)`）。
- `exception.thread` 为脱敏线程名；`payload.uptime_ms` 是从本次 SDK 安装开始的单调时钟间隔，不是设备开机时长。`dropped` 是当前进程观察到的容量/坏行/写入失败丢弃计数，不是永久累计值。
- `payload` 递归脱敏敏感键/字符串，有深度、共享总节点/字符串预算和 8KB 最终字节预算；未知对象不调用 `toString()`。业务调用点仍不得传入书名、正文、账号等字段。
- 文本扫描最多65,536个UTF-16单元；超限只保留预算内完整行，截口所在整行省略，无完整行则用占位符。预算内先识别完整凭据，再按字段字节数截断；extras键名也清洗。引号值支持转义引号，JVM使用占有式量词避免长值导致递归回溯栈溢出。
- 崩溃记录中的 `network` / `storage_free` 来自启动快照，并非崩溃瞬间测量；`heap_used` / 前后台 / 界面为采集时读取。快照失败时缺字段，不在崩溃线程回退系统查询。

**不进记录**：用户内容、文件路径中的书名/文件名、账号凭据、任何业务数据。`screen` 只放类名，不放书名这类业务字段。

## 6. 落盘

`filesDir/monitor/`，两个文件：

| 文件 | 写入方 | 说明 |
|---|---|---|
| `queue.jsonl` | 常规路径（`capture`、低内存、ANR） | 追加写，`synchronized` 锁保护 |
| `crash.jsonl` | 崩溃处理器 | **不参与队列锁**，见下 |

- 崩溃路径单独一个文件、单独一把锁，而且这把锁只被崩溃处理器用。原因：如果崩溃线程恰好正持有队列锁（比如崩溃就发生在写队列时），或另一线程正在重写 2MB 的队列，崩溃处理器会一直等——进程随时被系统杀掉，记录就丢了，最坏情况是 ANR。
- 下次启动时把 `crash.jsonl` 并入队列；先以同目录临时文件 + `fsync` + rename 提交目标，再清空源。目标失败保留源；中途退出后按原始行去重重放，不生成新 event_id。
- 常规队列上限 200 条 / 2MB；崩溃文件上限 50 条，也受同一字节预算约束。超限保留最近最多 3/4 条数，并继续裁到字节限额；单条超过本地总字节限额直接丢弃。
- 计数在启动扫描、追加及确认时维护，正常追加不再为每条事件重扫全文。启动修复旧文件超限及半行；重写与确认均用原子替换。
- 读队列时逐行 try-parse，坏行清理并计入 `dropped`，不占收据位置。发送时保留原始行收据，确认只删除对应行；不能按有效记录数删原始前 N 行，否则坏行/上传期间轮转会误删或重发。
- 单条写失败（磁盘满、权限）→ 静默丢弃，`dropped++`，不再抛。
- 崩溃风暴闸：`crash.jsonl` 上限 50 条；同一指纹 1 小时内最多写 10 条。坏版本上线时应用会「启动即崩」，没有这个闸会在一小时内写出上千条同一条崩溃。

**不用 Room**：崩溃发生时数据库可能正处于未提交的 WAL 状态，追加写文件比事务安全得多，也不给宿主启动路径多挂一个 DB 依赖。

## 7. 上报

- `FlushWorker`：`OneTimeWorkRequest`，`NetworkType.CONNECTED`，唯一任务名 `moo-monitor-flush`，`APPEND_OR_REPLACE`。进程内合并尚未开始的唤醒；Worker 开始时释放唤醒标记，运行中到来的事件可以排一个后继，避免 KEEP 在完成窗口吞掉唤醒。
- 触发：启动后 10 秒、采集后 5 秒、退后台与手动 `flush()`。已有待运行任务时合并唤醒，不承诺退后台一定绕过已有初始延迟。每轮最多 3 批，有余量会接续一个新 Worker。
- 批次 ≤50 条 / ≤512KB，先到先发（2026-11-09 已落地：`PayloadLimits.batch` 按累计字节切，超过服务端 256KB 单条预算的旧记录单独发送，避免卡死队列或拖掉邻近记录）。
- 不做本地指纹聚合，不改 event_id 或 count；指纹计算与聚合由 cloud 负责。
- 2xx 必须先验证 `ok=true` 与完整、唯一的逐条 index 回执。saved/created/updated/duplicate/filtered 及明确不可重试的 skipped 才删除；可重试/未知记录保留。坏 JSON、ok=false、缺失/重复 index 均不确认。
- `401/403` 保留队列、停止采集与发送；禁用标记绑定 endpoint/app/token 的 SHA-256，更换发送身份后可恢复。旧版无身份的布尔禁用标记不再永久封死新版本。
- `400/413/415/422` 丢当前发送批次；408、其它非成功状态与网络错误保留。禁止自动跳转，以免携带项目凭据跨地址。
- `429` 解析秒数或 HTTP 日期格式的 Retry-After，持久化最早发送时间；到期前 Worker 可被唤起，但不联网。WorkManager 从 30 秒起按指数退避，6 次重试后保留到下次触发；它不保证精确按秒执行。
- `HttpURLConnection` 而非 OkHttp：为 200 行上报引一个网络库不划算。连接/读取超时各 10 秒。

## 8. 脱敏

客户端先做一遍，服务端还会再做一遍：

| 目标 | 规则 |
|---|---|
| 路径 | `/data/`、`/storage/`、`/sdcard/`、`/mnt/` 的绝对路径折叠为 `~/[path]`，不保留私有文件名 |
| URL query | 键名命中 `password|pwd|token|secret|api_key|authorization|sig` → `***` |
| 消息 | URL → `[url]`；key=value、Bearer、独立 JWT 与长凭据清理；保留长类名/方法名/源码文件名 |
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
            env = if (BuildConfig.DEBUG) "debug" else "release",
            inAppPackage = "com.mooeen.shuhe", // 填宿主源码 namespace
        ))
    }
}
```

界面追踪：宿主可在关键 Activity 的 `onResume` 调 `MooMonitor.screen("ReaderActivity")`；不调也行，SDK 会从 `ActivityLifecycleCallbacks` 自动取类名。手动入口同时更新当前 screen 与面包屑；只允许类名形状、最多 64 字符。

构建注入 token（不入库）：

```kotlin
buildConfigField("String", "MOO_MONITOR_TOKEN",
    "\"${project.findProperty("mooMonitorToken") ?: ""}\"")
```

```bash
./gradlew assembleRelease -PmooMonitorToken=<从本机凭据文件按宿主文档读取>
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
| 单元 | `AppEventFramesTest` / `AppEventLimitsTest`：帧解析、脱敏、截断后 `frames` 与 `stack` 同源 |
| 单元 | `MonitorStoreTest`：追加、轮转、`dropped` 计数、半行容错 |
| 单元 | `UploaderTest`：完整/部分/损坏回执、撤权、失败保留、轮转期间确认、条数/字节切批与续传 |
| 定向 | `HttpTransportTest`：本机临时 HTTP 端口，验证响应体、跳转隔离、429/408 与响应体预算 |
| 单元 | `PayloadLimitsTest`：UTF-8 字节截断（不切坏代理对）、按字节切批 |
| 定向 | `CrashHandlerTest`（Robolectric）：装 handler 后抛异常 → 队列有一条 `crash` 且原 handler 被调用；
采集自身抛异常不影响原链；空 token 不接管；同指纹每小时上限 |
| 真机 | 宿主里制造崩溃，确认后台 30 秒内出现；断网 3 次启动后恢复上报 |

单测证明不了「目标设备进程被杀之前写进去了」；真机按用户明确授权的场景另行执行，不由本清单自动启动。

本次定向验证的命令与结果见 `HANDOFF.md`，不把历史测试条数当成当前事实。

## 12. 分阶段

- **M1**：崩溃 + WebView + 低内存采集，落盘、上报、cloud 建表与后台。
- **M2**：`AnrWatchdog`、`capture()` 业务接线示例（导入失败、解析失败、同步异常）。
- **M3**：`mapping.txt` 上传（等宿主真的开 minify 再做，否则是无用功）。

## 13. 边界

- 采不到：`ContentProvider` 初始化崩溃（早于 `Application.onCreate`）、系统 ANR 弹窗、被 `SIGKILL`、native 崩溃。
- 首次启动就崩的包可能一条都发不出去——队列在本地，用户不再打开就永远不发。
- 低内存回调有 1 小时 1 条的闸。Android 14（API 34）起系统不再发送 RUNNING_CRITICAL / COMPLETE，不能靠这条采集覆盖现代系统 OOM；UI_HIDDEN 也不能当低内存。见 [ComponentCallbacks2](https://developer.android.com/reference/android/content/ComponentCallbacks2)。
- 崩溃路径仍需栈序列化、限流计数持久化和同步写盘，无法保证 OOM 时成功，也没有实机耗时上界。原子替换保护进程中断，不承诺突然断电时目录元数据的持久性。
- 多进程宿主不支持共写当前目录，必须只在主进程安装。
- 主动事件目前仍同步组装/落盘；后台系统限制、长时间离线、200 条队列预算与服务端最近 50 个 event_id 去重窗口都限制送达/计数保证。
- 宿主必须从系统备份及换机迁移中排除 `sharedpref/moo-monitor.xml` 与 `file/monitor/`；否则安装标识、禁用状态和旧队列可能在另一安装恢复。书禾已配置排除。
