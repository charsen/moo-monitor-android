# HANDOFF — moo-monitor-android

> 交接快照：2026-11-09 17:45（GMT+8），`main` 最新提交为「P0 截断与崩溃路径瘦身」；hash 用 `git log -1` 读，不写死。
> 面向「没参与过这段开发、现在要接着改这个库」的人。先读这一份，再读 `docs/design.md`。
> 本文写**已经发生的事实**和**下一步怎么做**；计划进 `TODOS.md`，设计意图进 `docs/design.md`，真机结论进 `NOTES.md`。

## 1. 一句话现状

Android 端崩溃采集 SDK 的 M1 已完整落地：能编译、24 条单测全过、已在 BOOX 真机上跑通
「崩溃 → 落盘 → 下次启动合并 → 批量上报 → 生产返回 2xx → 队列清空」的闭环。
云端 `moo-scaffold-cloud` 已部署上线，宿主 `shuhe` 已接入。**没有阻塞上线的已知问题**。
体积截断、批次字节封顶与崩溃路径瘦身（P0 两项）已落地并在 BOOX 复验，剩下的是补强测试与 M2 能力，见 §8。

## 2. 状态快照

| 项 | 值 |
|---|---|
| 仓库 | `/Volumes/dev/wwwroot/moo-monitor-android` |
| 分支 | 只有 `main`（新仓未分线）；`origin` = gitee，`github` = GitHub，两边同步 |
| HEAD | 本轮 P0 收口提交；上一版 `5a0b056` 是后台补发 / 主动上报节流 / 禁用状态持久化 |
| 公开 | MIT（[`LICENSE`](LICENSE)），gitee + GitHub 双远端，推两端 |
| 代码 | `monitor/` 单个 library module，12 个主源文件 + 8 个测试类，共 1522 行 |
| 测试 | `./gradlew :monitor:testDebugUnitTest` → **39 条全过**（8 个测试类，`CrashSnapshotTest` 用 Robolectric） |
| 坐标 | `com.mooeen.monitor:monitor:0.1.0`（`group`/`version` 在 `monitor/build.gradle.kts`） |
| 云端 | 已部署 `https://c.mooeen.com`，`app_errors` 表已建，路由已上线 |
| 宿主 | `shuhe` 已接线（`1775931` 所在分支含 `7fa7049` 接入提交） |
| 生产数据 | 留有冒烟与真机测试记录（含 `SmokeTest`、`CrashedByAdbException`），确认后可在后台删除 |

## 3. 五分钟跑起来

```bash
cd /Volumes/dev/wwwroot/moo-monitor-android

# JDK 17 是硬要求；系统默认 JDK 不对会直接报 AGP/JDK 错
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# local.properties 已在机器上（sdk.dir=$HOME/Library/Android/sdk），被 .gitignore 忽略
./gradlew :monitor:testDebugUnitTest          # 24 条
./gradlew :monitor:compileDebugKotlin         # 只编译
```

**真机 / 宿主联调**（本仓自己跑不出上报，必须有宿主）：

```bash
cd /Volumes/dev/wwwroot/shuhe
T=$(awk '/^## 书禾-安卓 app 客户端异常上报/{f=1;next} f&&NF{print;exit}' /Volumes/dev/build_tokens)
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:assembleDebug -PmooMonitorToken="$T"
```

token 来源只有两处：`/Volumes/dev/build_tokens` 的 §「书禾-安卓 app 客户端异常上报」，
以及后台「项目 → 接入 Token」。**token 不进源码、不进 git、不进日志、不写进任何文档**。
缺省 `-PmooMonitorToken` 时注入空串，SDK 整体禁用、不发任何请求。

## 4. 代码地图（真实文件，与 `docs/design.md` 的示意不同）

`docs/design.md` §3 的模块清单是设计阶段的示意，实际实现把几处合并了：

| 文件 | 职责 |
|---|---|
| `MooMonitor.kt` | 门面 + 崩溃处理器 + `FlushWorker` + 三个节流闸；`install()` 冻结设备快照并交给崩溃闭包 |
| `MonitorConfig.kt` | 配置；`enabled = token 非空 && endpoint 非空` |
| `AppEvent.kt` | 记录组装、`exception()` 脱敏与截断、`frames()` 栈解析（手写 JSON，无序列化库） |
| `MonitorStore.kt` | `queue.jsonl` / `crash.jsonl` 双文件双锁、轮转、坏行跳过、`dropped` |
| `Uploader.kt` / `Transport.kt` | 批量 50 条 × 最多 3 批；`HttpURLConnection`；状态码分流 |
| `SessionTracker.kt` | `ActivityLifecycleCallbacks`：session_id、前台状态、`onBackground` 回调 |
| `PayloadLimits.kt` | UTF-8 字节截断、按字节切批（纯函数，崩溃路径零分配） |
| `DeviceInfo.kt` | 设备上下文；`Snapshot` 是启动时冻结的静态部分，崩溃路径只读它 |
| `Breadcrumbs.kt` / `Redactor.kt` / `Throttle.kt` | 环形面包屑、脱敏、纯函数节流计数 |
| `src/test/…` | `AppEventFramesTest`(4) `AppEventLimitsTest`(3) `CrashSnapshotTest`(2) `MonitorStoreTest`(5) `PayloadLimitsTest`(8) `RedactorTest`(4) `ThrottleTest`(6) `UploaderTest`(7) |

对外只暴露 `MooMonitor` + `MonitorConfig`；其余一律 `internal`。

## 5. 数据流（排查时按这个顺序看）

```
未捕获异常
  → Thread.setDefaultUncaughtExceptionHandler（链式，runCatching 包住）
  → 崩溃风暴闸（同指纹 10 条/小时，SharedPreferences 计数）
  → AppEvent.build（exception() 脱敏+按字节截断；device/context 只读冻结的 Snapshot）
  → MonitorStore.appendCrash → filesDir/monitor/crash.jsonl（独立锁）
  → previous?.uncaughtException(...)   ← 必须交还，系统照常出 tombstone / 崩溃框

下次启动 Application.onCreate
  → MooMonitor.install：mergeCrashIntoQueue() 先合入 queue.jsonl 再装 handler
  → 触发发送：启动后 10s / 退到后台立即 / capture 后 5s / 手动 flush()
  → WorkManager 唯一任务 moo-monitor-flush（要求联网，指数退避，最多重试 6 次）
  → Uploader 按 50 条 / 512KB 先到者切批
  → POST /api/v1/app-errors/intake，token 走 X-Project-Token 头
  → 2xx → dropFirst；401/403 → 持久化 disabled；其它 4xx → 丢批；429/5xx/IO → retry
```

**关键：崩溃写 `crash.jsonl` 而不是 `queue.jsonl`**。原因是复盘发现的死锁：崩溃线程可能正持有队列锁，
或另一线程正在重写 2MB 队列，共用锁会让处理器一直等到进程被杀，记录直接丢。这条不能「优化」掉。

## 6. 已经拍板的决策（别推翻，要改先读理由）

| 决策 | 理由 |
|---|---|
| 指纹由**服务端**重算，客户端不算 | 服务端已按「平台+类型+异常名+归一化消息+前 3 帧 file:function」聚合；客户端再算一份只会多一处能算错的地方。因此队列内同 hash 不合并、批次只按 50 条切 |
| 不做本地聚合、不采样 | 崩溃本来就稀少，采样纯丢信息 |
| 崩溃信息落文件，不用 Room | 崩溃时 DB 可能处于未提交 WAL；追加写更安全，也不给宿主启动路径挂 DB 依赖 |
| 复合构建 `includeBuild`（不发 maven） | 目前只有一个宿主，等第二个出现再谈发布流程 |
| token 能力 `app_errors` 必须单独一枚 | token 会打进 APK，公开可见，不能带其它权限 |
| 采集失败绝不影响宿主 | 所有路径 `runCatching`，不 `exitProcess`，崩溃必须交还原 handler |
| 401/403 之后**保留队列**但持久化禁用 | 撤权不是格式问题，数据不该被清掉；禁用持久化避免每次启动撞一次无效 token |
| 崩溃按小时闸 / `capture` 按天闸（20 条） | 前者防「启动即崩」写爆磁盘，后者防业务 catch 循环把 200 条队列灌满挤掉真正的崩溃 |
| 截断按 UTF-8 **字节**而不是字符数 | 中文 3 字节、emoji 4 字节；按字符截仍会超契约上限，按 char 截还会切坏代理对 |
| 体积在组装时定、批次再按字节切 | 云端 422 是「整批不入库」，等上传时才量，一条坏记录会拖掉同批 50 条 |
| 崩溃路径只读启动快照 | `StatFs` 是磁盘 IO、`ConnectivityManager` 是 Binder，进程随时被杀；session / screen / foreground / heap_used 仍当次读 |

## 7. 跨仓依赖与唯一契约来源

| 仓 | 关系 |
|---|---|
| `moo-scaffold-cloud` | **契约唯一来源**：`docs/app-monitor.md`（§4 上报契约、§5 端点与能力、§13 决策）。本仓不复制契约 |
| `shuhe` | 首个宿主。`settings.gradle.kts` 的 `includeBuild("../moo-monitor-android")` + `applicationIdSuffix=".debug"` |
| `moo-monitor-ios` | 兄弟仓，共用同一云端契约与同一 `shuhe` 产品（靠 `platform` 列区分） |
| `moo-monitor-go` / `moo-monitor-laravel` / `moo-monitor-vue` | 同体系其它端 |
| `/Volumes/dev/build_tokens` | 宿主构建 token（明文，别输出、别提交） |
| `/Volumes/dev/git_tokens` | 推 GitHub 用的 token（明文，别输出、别提交） |

**只克隆本仓可以编译单测；只克隆 `shuhe` 会构建失败**——它依赖同级的 `../moo-monitor-android`，
这条已写进 `shuhe/AGENTS.md` 的构建章节。

推两端（本仓没有 `dev` 分支）：

```bash
git push origin main
GH=$(awk '/## github_token/{getline; getline; gsub(/[ \t\r]/,""); print; exit}' /Volumes/dev/git_tokens)
git -c http.https://github.com/.extraheader="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GH" | base64)" push github main
```

## 8. 下一步：按优先级排好的待办

### 本轮已完成（2026-11-09，P0 两项）

1. **`err_stack` / `message` 截断 + 批次按字节封顶**：`PayloadLimits.truncate` 按 UTF-8 字节截（8KB / 32KB，预算含结尾标记、不切坏代理对）；
   `Uploader` 按 50 条 / 512KB 先到者切批，第一条再大也单独发，不会卡死队列。`AppEvent.exception()` 保证 `frames` 与 `stack` 是同一份文本。
2. **崩溃路径瘦身**：`install()` 冻结 `DeviceInfo.Snapshot`（device / network / storage_free / heap_max），崩溃闭包只读它；
   session / screen / foreground / heap_used 仍当次读。BOOX 落盘记录 2800 字节，`device` 七项与 `context` 八项与改前一致。
3. **守卫**：新增 15 条单测（含 1 个 Robolectric 类），共 39 条。四组变异分别转红 5 / 4 / 1 / 1 条
   （不截断 / 不按字节切批 / build 忽略快照 / context 不读缓存），还原后全绿。BOOX 冷启动闭环见 §9。

### P1 — 体验与一致性（待办）

3. **`onRenderProcessGone` 用 `recreate()` 太重**：墨水屏整页重绘 500–800ms，且阅读位置没落盘会被弹回章节开头。
   改为先保存进度再重建，或只重建 WebView（`design.md` §4.2 写的是「返回 false 交回宿主」，实际宿主返回 `true` + `recreate()`）。
4. **补 `CrashHandlerTest`**：`design.md` §11 列了这个测试，实现里没有。断言「装 handler 后抛异常 → 队列有一条 crash 且原 handler 被调用」。
5. `onTrimMemory` 只认 `TRIM_MEMORY_COMPLETE`，`RUNNING_CRITICAL` 更常触发。
6. 对齐 `docs/design.md` 与实现：模块文件名、**本地合并**、401 是否清队列（批次字节上限本轮已对齐）。

### M2 / M3

6. `AnrWatchdog`（8 秒无 pong 记 `anr`，带主线程栈）、MetricKit 对应的 Android 侧能力。
7. `capture()` 在宿主三处业务接线（导入失败、解析失败、同步异常）。
8. `mapping.txt` 上传——**等宿主真的开 minify 再做**，现在 `isMinifyEnabled = false`，堆栈本来就可读。
9. 多进程宿主评估（当前单进程假设，不做 IPC 汇总）。

### 明确不做

- 客户端算指纹做队列内合并（见 §6）
- 采样、SDK 内建工作台/页面
- 引入 OkHttp / Gson / Room / 任何三方崩溃库

## 9. 已验证 / 没法自动验

**已验证（有直接证据，不是推断）**：

- 单测 **39 条**、`compileDebugKotlin` 干净；四组变异均有 bite，不是空绿灯。
- 生产 HTTP 冒烟：写入 `saved:1`、原样重发 `duplicate`、新 `event_id` 累计、iOS token 写入、无权限 token 403。
- 本地 cloud（MySQL + `php artisan serve`）真实闭环：后台列表/详情渲染 200、通知规则可写入。
- BOOX NoteX5Mini 真机（设备 `4db66efd`）：
  ```
  adb shell am crash → crash.jsonl 约 2800 字节（内容完整）
  重启 → crash.jsonl 删除、并入 queue.jsonl
  16 秒后 → D MooMonitor: intake ok: 1 条
           → I WM-WorkerWrapper: Worker result SUCCESS for tags={ com.mooeen.monitor.FlushWorker }
  按 HOME 后 0.85 秒 → 后台回调触发的立即发送（定时任务是 10 秒后，所以这次不是定时器）
  ```
  `intake ok` 只在服务端返回 2xx 时打印；401/403 打「token 无效或撤权」、4xx 打「格式错误，丢弃」、5xx 打「稍后重试」。
- **本轮 P0 复验（17:38–17:40，同一台 BOOX）**：`am crash` → `crash.jsonl` 2800 字节，
  `device` 七键含 `model=NoteX5Mini`、`context` 含 `network=wifi` / `storage_free` / `heap_max` / `heap_used` / `session_id` / `install_id` / `screen` / `foreground`；
  `stack` 735B、`message` 19B（正常崩溃远不到截断阈值）。冷启动后 `17:40:47 D MooMonitor: intake ok: 1 条` +
  `WM-WorkerWrapper: Worker result SUCCESS` + `files/monitor` 清空。**记录形状与改前一致**，即快照与截断未改变上报内容。

**没法自动验 / 仍需人工**：

- 后台「数据 → 客户端异常」列表里能否看到 `CrashedByAdbException`（SDK 侧无读接口、无登录态）。
- 断网恢复、渲染进程被系统真杀（`adb` 触发不了）、国产 ROM 后台限制下的补发成功率。
- 加了 handler 之后系统崩溃对话框是否仍正常弹出（代码上已交还原 handler）。
- 崩溃时同步写盘在低端机上的耗时与 ANR 风险。

## 10. 环境坑（踩过的，别再踩）

- **JDK 17**：必须显式 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`，否则 AGP 报错。
- **`local.properties`** 被忽略，新机器要自己写 `sdk.dir=$HOME/Library/Android/sdk`；`gradlew`/wrapper 是从 `shuhe` 拷来的。
- **BOOX 侧载会禁用包**：装完起不来、报 `Activity class does not exist` 时，`adb -s <id> shell pm enable com.mooeen.shuhe.debug` 即可。
- **`adb shell run-as PKG sh -c '...'`** 在 BOOX 镜像上会被 SIGTERM，改成逐条 `run-as PKG cat/ls`。
- **崩溃后 WorkManager 约 2 秒自动拉起进程**，`install()` 一跑就把队列发掉了；想抓「带数据的后台 flush」窗口很难，
  两段证据分开验更现实（后台回调触发 + 上传返回 2xx）。
- **debug 包里带的是生产 token**（构建时注入）。别把 APK 往外传；模拟器/真机测完可卸载：
  `adb -s 4db66efd uninstall com.mooeen.shuhe.debug`。
- `applicationIdSuffix = ".debug"` 导致 debug / release 的 `install_id` 不同，云端会当成两个安装。
- **崩溃后系统弹「应用错误」对话框**：此时 `am start` 会复用仍在崩溃态的同一 PID，`Application.onCreate` 不重跑，
  `crash.jsonl` 不会被合并（看起来像「重启没生效」）。先 `input keyevent KEYCODE_BACK` 关对话框 + `am force-stop`，
  再冷启动；日志窗口要从崩溃前就留着，因为 WorkManager 约 2 秒会自己拉起进程并把队列发掉。
- macOS 没有 `timeout` 命令，别用它包 `git ls-remote`（会误报「仓库不存在」）。
- 本机**没有 Go 工具链**（与 `moo-monitor-go` 相关时才知道），Android/Java 与 Swift 工具链都在。

## 11. 变更纪律

- 开工先读 `docs/design.md`、`NOTES.md`、`TODOS.md`；实现与设计冲突时先确认哪边是当前意图，再改另一边。
- `AGENTS.md` 的「采集红线」是硬约束（不吞崩溃、不进 token、不上报用户内容、崩溃同步写盘）。
- 任何采集能力变更同步 `docs/design.md` 的采集点表与边界章节；真机结论写 `NOTES.md`，不写进设计文档。
- 提交前 `./gradlew :monitor:testDebugUnitTest`；改了运行时行为还要在 BOOX 上复验一次。
- 新增守卫要做变异：改坏实现 → 对应用例转红 → 还原。只报绿灯不算锚定。
- 提交后推 **gitee + GitHub 两个远端**（§7 命令）。
- 云端契约只在 `moo-scaffold-cloud/docs/app-monitor.md` 维护，本仓改上报格式前先改契约并同步云端实现。
