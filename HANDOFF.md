# HANDOFF — moo-monitor-android

## 当前复核（2026-11-10）

用户随后授权“合并分支”：本轮成果提交后，将同一任务分支分别合入本地 dev 与 main，完整合入后清理任务分支；不推送、不打 tag、不部署。下方“未提交/合并”均为验证时快照，实际交付状态以 Git 引用为准。

第二轮按用户“测试，复核”及连接 BOOX 的指令完成了设备场景；当前定向结果 **SDK 71 / cloud 33 / 书禾 4**，以及 BOOX **9 个独立 SDK 场景 + 1 个真实书禾阅读页场景**。不是全站 E2E，也没有生产部署。

### 第二轮发现与修复

1. 独立 JWT 被保留类名的新边界漏过：新增专门 JWT 清理规则。对应失败用例已先转红后修复。
2. extras 只有“每容器上限/深度”仍会按扇出指数展开共享 Map：改为共享节点/字符串预算；用访问计数提前断言，避免测试本身爆内存。对应失败用例已转红后修复。
3. cloud 的大 payload 回退重新放回未脱敏 context：先清理独立 context，再进入正常/回退分支。对应失败用例先转红后修复。
4. 畸形帧/面包屑及历史截断标记会毒化详情/AI 读取：复用 StructuredList 的 AppErrorRecords cast，覆盖 frames/src_frames/breadcrumbs 的读写类型边界；入站失败用例及原始脏 JSON 的列表/详情/AI 回归通过。
5. applicationId 后缀不同于源码 namespace：新增可选 `MonitorConfig.inAppPackage`，缺省兼容原 applicationId；书禾显式填 `com.mooeen.shuhe`。受影响源码、宿主、测试和文档已核对。未改 wire schema。
6. **公开 token 越权关单**：带 status=resolved 的上报能关闭已有异常，违反 app_errors 只写观察的约定；失败用例已复现。客户端处置输入现被忽略，上报统一为 open 观察，原有处理中/回归守卫照常执行；后台授权 resolve 的回归仍通过。此修复尚未部署到生产。

### BOOX 设备证据（Android13 / NoteX5Mini / 4db66efd）

- 独立 APK 使用本机 fixture token 与 Laravel 的独立 SQLite；HTTP/队列/邮件外呼被隔离，没有生产请求。可重复入口：`./gradlew -PmonitorDeviceQa :device-qa:assembleDebug`，`python3 tools/device-qa/run.py --serial <serial>`。默认宿主构建不加载该模块。
- 原始证据：`build/monitor-deviceqa-a5qtrap8/results.json`、`requests.jsonl`、`summary.json`、`crash-logcat.txt`、`renderer-recovered.png`。9 个场景均通过：普通采集入库与脱敏；180 条按 50/50/50/30 接续；部分回执只保留 1 条再发送；429 最早发送限制（实际间隔约 30.094 秒）；3 次重启保留同一 event_id；401 停采停发与换 token 恢复；真实 Java 崩溃交还系统并重启补发；真实 chrome://crash 后宿主存活；转发两次 CRITICAL 只记录一次。
- “离线”场景为测试端点 503/暂不可达语义，不是关闭整机网络；memory 场景为主动转发等级 15，不是制造实际 OOM。不会据此宣称物理断网约束、真实内存压力或 ROM 长期休眠已验证。
- 单次设备测量：首个主动采集约 **17.516ms**（另两次冷态约21ms）；崩溃从 throw 到 SDK 交还处理器约 **48.824ms**。不是 p95、不是 OOM 上界，没有与旧包的同条件性能对照。原生报文 UTC 与 SQLite 业务时间也回读对齐（11:58:55）。
- 真实书禾隔离包另行使用实际 ReaderActivity/BookImporter：生成 TXT → 第4页 → renderer crash → Activity 重建。0-based page **3→3**、offset **1017→1017**、旧 WebView 已移除，约 **1706ms** 恢复，cloud 收到 did_crash=true 的 webview_gone。截图已查看，正文及底部 4/41 正常。证据在 `../shuhe/build/monitor-deviceqa/`。
- SDK 设备 APK（含本轮脱敏/预算修复）的 SHA 为 `170502d202418c9fd29ad4eb79682e154f26341cc19cf02569e8b2a64976d550`，2,532,618字节，0.1.0/1，com.mooeen.monitor.deviceqa。namespace 配置随后由 JVM 与实际书禾的最新 composite 构建验证。
- 书禾实际验收 APK SHA 为 `cb05d2da698ab6e5c23c5f1f5273c509e02db33a89d05949190503bf0a586b0b`，9,064,527字节，0.3.27/32，com.mooeen.shuhe.monitorqa。两包均为 min24/target34、无 native ABI 限定，v2 签名，证书 SHA `e80b629d18e808ff3f69b2b9f95bcb89e517c85439b1b37f8eee4582e111e087`，设备回拉 SHA 与产物一致。完整构建流水在 `../shuhe/docs/BUILD.md`。
- 首轮设备脚本在 token_b 尚未执行时 force-stop 导致假超时，已用 nonce 完成回执修正，并完整复跑9场景；另一个初始 Gradle overlay 在 projectsEvaluated 改 manifest 太迟，改为 finalizeDsl 后 instrumentation 注册成功。两者均为夹具问题，没有算作 SDK 缺陷或通过结果。
- 验收后已移除本轮两个临时包、tcp:18764 reverse、设备种子文件、本机监听；正式 com.mooeen.shuhe 与既有 .debug 保留。正式版仍0.3.27/32，firstInstallTime=2026-10-19 20:35:57，lastUpdateTime=2026-11-09 17:56:15。

### 第二轮本地验证

- 合并前按仓库要求执行 `./gradlew -I /tmp/moo-monitor-precommit.init.gradle :monitor:test`，临时 init 脚本仅选择下列9个受影响测试类；Debug/Release 各 **71项通过**，XML 均为0失败/错误。没有扩展到未受影响类、宿主全量或 E2E。
- 同首轮9类定向 SDK 测试，增补总节点预算/JWT/并发追加/崩溃不等队列锁/namespace 后，**71项通过**；lintDebug 通过（无 Error，既有同步 commit/依赖建议 Warning 保留）。
- 同首轮4个 PHP Feature 文件，增补 context 回退、诊断列表、历史脏形状及公开 token 处置边界后，**33项 / 202断言通过**。变更的 controller/model/cast/test 定向 Pint 通过，共享 IntakePipeline 的既有格式告警仍按首轮记录保留。
- 书禾 ReaderDocumentCallbackTest **4项通过**；常规 composite 构建编译了最新 SDK 与 ShuheApp 配置。没有递增任何已有版本号，未提交/合并/推送。

---

### 首轮审核记录（同日，设备验收前的快照）

三仓使用 `fix-monitor-reliability`，未提交、未合并、未推送，版本号保持原值。本轮按用户指令直接修复确认的问题；下方 2026-11-09 内容为历史快照，不代表当前缺陷/分支/测试状态。

- SDK：修复字节上限、坏行/轮转确认错位、崩溃先删源、非原子重写；改用原始行收据。正常追加不再逐条重扫文件；UTF-8 计数不分配完整字节数组。新增半行恢复、逐条回执、续传与合并唤醒。
- 采集：普通入口的偏好读写全部纳入失败隔离；旋转计数平衡；前台 CRITICAL/后台 COMPLETE 分开判断；撤权后停止采集与发送，禁用绑定发送身份摘要；uptime 改为安装起点。敏感 extras/线程名与绝对文件名在落盘前清理。
- cloud：已用失败测试复现旧事件回滚时间/版本、现场字段丢失、畸形指纹整批 500。修复后按事件日期记日桶；现场白名单进入 payload.context，消息/栈入库前脱敏并落实预算、severity 推导。
- 书禾：失效 WebView 先移出视图树、destroy、使旧 document 回调失效，再 recreate；备份配置排除监控偏好与日志。没有改变阅读同步协议或引入新业务上报点。
- SDK 的初始缺陷复现：22 项定向测试中 5 项失败，覆盖字节上限、合并失败保留源、短凭据/文件名、轮转确认、坏行确认。cloud 最初新增 3 项用例在旧实现全部失败，末轮 UTC 跨日用例也先复现后修复。修复后的实际结果见下文验证记录。
- 本轮没有出 APK、安装设备、访问生产或跑 E2E。书禾 Gradle 在配置阶段也要求 release 反馈 token；JVM 测试使用 `SHUHE_FEEDBACK_TOKEN=unit-test-only` 占位，未读取或注入真实 token，不代表反馈或监控鉴权已联调。
- cloud / Android 共用的唯一契约已更新；Apple SDK 代码未改，仍使用旧的整批确认策略，其逐条回执适配在 cloud TODOS 中单列。

本轮剩余边界：ANR/native/被杀采集仍未实现；Android 14+ 不再发送旧内存等级；队列 200 条/2MB 与服务端每指纹最近 50 个 event_id 去重均为有界保证；同步捕获仍有写盘/内存开销，没有实机性能百分比。见设计 §13 与 TODOS。

### 本轮验证记录

- SDK：`./gradlew :monitor:testDebugUnitTest`，通过 `--tests` 定向选择 `MonitorStoreTest`、`UploaderTest`、`HttpTransportTest`、`RedactorTest`、`AppEventLimitsTest`、`AppEventFramesTest`、`CrashHandlerTest`、`CrashSnapshotTest`、`PayloadLimitsTest`；9 类 **66 项通过**。读取 `monitor/build/test-results/testDebugUnitTest/TEST-*.xml` 核实 failures/errors 均为 0。未跑未受影响的 IsoTime/Throttle 测试。
- SDK：`:monitor:lintDebug` 通过，报告无 Error，15 条 Warning（3 条 ApplySharedPref 来自刻意同步提交的崩溃限流/Worker 禁用与退避状态；其余为依赖/AGP 版本建议），未升任何依赖或版本号。
- cloud：`vendor/bin/phpunit tests/Feature/AppErrorIntakeTest.php tests/Feature/AppErrorNotificationTest.php tests/Feature/AppErrorPagesTest.php tests/Feature/IntakePipelineTest.php`，**29 项 / 183 断言通过**，SQLite 内存库，队列/HTTP 外呼隔离。新增真实 SDK UTC 跨日用例先复现 8 小时偏差，再修复为上海时区入库。
- cloud：AppErrorIntakeController/AppErrorIntakeTest 的定向 Pint 通过；共享 IntakePipeline `php -l` 通过。该共享文件已有 Pint 格式告警，把 HEAD 原文导出到临时文件复核亦失败（同为 unary_operator_spaces / braces_position / not_operator_with_successor_space / single_line_empty_body / phpdoc_align），未为了检查扩大格式 diff。
- 书禾：`SHUHE_FEEDBACK_TOKEN=unit-test-only ./gradlew :app:testDebugUnitTest --tests com.mooeen.shuhe.ui.reader.ReaderDocumentCallbackTest`，**4 项通过**，通过 composite build 使用本轮 SDK。备份两份 XML 已解析并断言监控偏好/目录均被排除。
- SDK 的 HTTP 传输用例只访问临时 127.0.0.1 ServerSocket，覆盖响应体、302 不跳转、429 Retry-After、408 保留、超大响应保留；没有访问生产。

### 审核结论索引

| 范围 | 确认的问题 | 修复方式 |
|---|---|---|
| P0 送达 | 崩溃先删源、重写先截断原文件 | 先提交目标、原子替换，失败保留源与原队列 |
| P0 送达 | 坏行/上传期间轮转后按行数确认 | 发送时冻结原始行收据，确认时只移除匹配记录 |
| P0 送达 | 见到 200 就删整批 | 完整逐条回执，仅确认终结项，保留 retryable |
| P1 容量/调度 | 字节配置未使用、单轮 150 条后无接续、KEEP 可能吞运行中的唤醒 | 双预算、续传 Worker、合并尚未开始的唤醒并允许后继 |
| P1 诊断 | UTC 写本地列差 8 小时、旧事件回滚版本/时间、现场字段丢失 | 业务时区归一，事件日桶和聚合时间分离，保留诊断白名单 |
| P1 隔离 | 畸形指纹字段整批 500；普通采集的偏好访问可能抛回宿主 | 指纹输入防御和采集失败隔离 |
| P1 隐私/准确性 | 短凭据、嵌套 extras、私有文件名未清理；长类名可能被误脱敏 | 结构化递归清理、有界 payload、保留源码标识符 |
| P1 生命周期 | 旋转导致前台计数不平；撤权仍可采集、换 token 不恢复 | stop 平衡计数；发送身份范围内禁用 |
| P1 宿主 | 死 WebView 仍可被生命周期/回调访问；备份恢复监控安装身份 | 及时移除/销毁和回调失效，排除备份 |
| P2 开销 | 每次追加扫全文、计字节分配完整 byte[] | 维护计数、按码点计 UTF-8 长度 |


---

## 历史快照（2026-11-09）

> 交接快照：2026-11-09 18:05（GMT+8）。`main` 上是「P0 截断与崩溃路径瘦身」（`3ea5f27`）；
> 之后这一轮（崩溃链守卫 + 时间戳线程安全）在工作区，尚未提交。hash 用 `git log -1` 读，不写死。
> 面向「没参与过这段开发、现在要接着改这个库」的人。先读这一份，再读 `docs/design.md`。
> 本文写**已经发生的事实**和**下一步怎么做**；计划进 `TODOS.md`，设计意图进 `docs/design.md`，真机结论进 `NOTES.md`。

## 1. 一句话现状

Android 端崩溃采集 SDK 的 M1 已完整落地：能编译、46 条单测全过、已在 BOOX 真机上跑通
「崩溃 → 落盘 → 下次启动合并 → 批量上报 → 生产返回 2xx → 队列清空」的闭环。
云端 `moo-scaffold-cloud` 已部署上线，宿主 `shuhe` 已接入。**没有阻塞上线的已知问题**。
体积截断、批次字节封顶与崩溃路径瘦身（P0 两项）已落地并在 BOOX 复验；崩溃接管链的测试补上了，
顺手修掉一处跨线程共用 `SimpleDateFormat` 的隐患。剩下的是 P1 一致性项与 M2 能力，见 §8。

## 2. 状态快照

| 项 | 值 |
|---|---|
| 仓库 | `/Volumes/dev/wwwroot/moo-monitor-android` |
| 分支 | 只有 `main`（新仓未分线）；`origin` = gitee，`github` = GitHub，两边同步 |
| HEAD | 本轮 P0 收口提交；上一版 `5a0b056` 是后台补发 / 主动上报节流 / 禁用状态持久化 |
| 公开 | MIT（[`LICENSE`](LICENSE)），gitee + GitHub 双远端，推两端 |
| 代码 | `monitor/` 单个 library module，13 个主源文件 + 10 个测试类，共 1744 行 |
| 测试 | `./gradlew :monitor:testDebugUnitTest` → **46 条全过**（10 个测试类，`CrashSnapshotTest` / `CrashHandlerTest` 用 Robolectric） |
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
./gradlew :monitor:testDebugUnitTest          # 46 条
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
| `IsoTime.kt` | ISO-8601 UTC 时间戳（ThreadLocal formatter：`SimpleDateFormat` 非线程安全，而调用方跨线程） |
| `Breadcrumbs.kt` / `Redactor.kt` / `Throttle.kt` | 环形面包屑、脱敏、纯函数节流计数 |
| `src/test/…` | `AppEventFramesTest`(4) `AppEventLimitsTest`(3) `CrashHandlerTest`(5) `CrashSnapshotTest`(2) `IsoTimeTest`(2) `MonitorStoreTest`(5) `PayloadLimitsTest`(8) `RedactorTest`(4) `ThrottleTest`(6) `UploaderTest`(7) |

对外只暴露 `MooMonitor` + `MonitorConfig`；其余一律 `internal`。

## 5. 数据流（排查时按这个顺序看）

```
未捕获异常
  → chainedCrashHandler：runCatching 包住采集，然后把异常原样交还 previous
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
| 时间戳走 `IsoTime`（ThreadLocal），不放共享 `SimpleDateFormat` | 崩溃线程 / 业务线程 / 主线程都会写时间戳；共享 formatter 的中间态在实例字段里，并发会写出错乱日期或抛异常，再被 `runCatching` 吞成「少一条记录」 |

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
3. **守卫**：新增 15 条单测（含 1 个 Robolectric 类），当时共 39 条。四组变异分别转红 5 / 4 / 1 / 1 条
   （不截断 / 不按字节切批 / build 忽略快照 / context 不读缓存），还原后全绿。BOOX 冷启动闭环见 §9。

### 本轮已完成（2026-11-09，第二轮：崩溃链守卫 + 时间戳线程安全）

1. **补 `CrashHandlerTest`（5 条）**：把链式接管从 `install()` 里抽成 `chainedCrashHandler(previous, onCrash)`，
   钉住三条契约——记录落盘、原 handler 必被调用、采集自身抛异常不得影响原链路；另验「空 token 不接管」与「同指纹每小时上限」。
   `install()` 是进程内幂等入口，为此加了 `internal fun resetForTests()`（仅测试用）。
2. **时间戳线程安全**：`AppEvent` 与 `Breadcrumbs` 原各持一个共享 `SimpleDateFormat`，且 `Breadcrumbs.add` 在锁外格式化——
   崩溃线程与业务线程会同时 `format`，出错又被 `runCatching` 吞掉，表现为「记录莫名少一条」。统一改用 `IsoTime`（ThreadLocal）。
3. **守卫**：新增 7 条单测，共 46 条。五组变异分别转红 1 / 2 / 1 / 1 / 1 条
   （去掉 runCatching / 不交还原 handler / 去掉风暴闸 / 空 token 也接管 / 单例持有共享 formatter）。
   **并发用例按纪律删掉了**：8 线程 × 3000 轮 `CyclicBarrier` 对齐仍复现不出错乱日期，不 bite 的守卫不留（见 `IsoTimeTest` 注释）。

### P1 — 体验与一致性（待办）

1. **`onRenderProcessGone` 用 `recreate()` 太重**：墨水屏整页重绘 500–800ms，且阅读位置没落盘会被弹回章节开头。
   改为先保存进度再重建，或只重建 WebView（`design.md` §4.2 写的是「返回 false 交回宿主」，实际宿主返回 `true` + `recreate()`）。
2. `onTrimMemory` 只认 `TRIM_MEMORY_COMPLETE`，`RUNNING_CRITICAL` 更常触发；注释里写了「前台」但代码没判 `tracker.foreground`。
3. 对齐 `docs/design.md` 与实现：模块文件名、**本地合并**、401 是否清队列（批次字节上限已对齐）。

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

- 单测 **46 条**、`compileDebugKotlin` 干净；前后九组变异均有 bite，不是空绿灯。
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
- **本轮第二轮复验（17:52–17:53，重装本轮包）**：`crash.jsonl` 2800 字节，
  `occurred_at=2026-11-09T09:52:22.970Z`、`breadcrumbs` 3 条 `t` 全部合 ISO 契约（`IsoTime` 换掉共享 formatter 后现场验证）；
  冷启动 `17:53:34 D MooMonitor: intake ok: 1 条` + `Worker result SUCCESS` + `files/monitor` 清空。
  坑：**崩溃后先 `logcat -c` 再冷启动会把 `intake ok` 冲掉**（WorkManager 常在崩溃后 1–2 秒自动拉起并已发完）。

**没法自动验 / 仍需人工**：

- 后台「数据 → 客户端异常」列表里能否看到 `CrashedByAdbException`（SDK 侧无读接口、无登录态）。
- 断网恢复、渲染进程被系统真杀（`adb` 触发不了）、国产 ROM 后台限制下的补发成功率。
- 加了 handler 之后系统崩溃对话框是否仍正常弹出（代码上已交还原 handler；`CrashHandlerTest` 断言了「原 handler 必被调用」这一必要条件，但不是充分条件）。
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
