package com.mooeen.monitor

/**
 * SDK 配置。token 为空即整体禁用——不在没有凭据的情况下悄悄发请求。
 *
 * @param endpoint 云端地址，末尾不要带斜杠
 * @param token 项目 token（能力必须含 app_errors），构建时注入，不进源码
 * @param app 产品 slug（如 shuhe），与云端项目对应
 * @param release 版本名（Android versionName）
 * @param build 版本号（versionCode）
 * @param env release / debug，云端按此做环境过滤
 */
data class MonitorConfig(
    val endpoint: String = "https://c.mooeen.com",
    val token: String = "",
    val app: String = "",
    val release: String = "",
    val build: String = "",
    val env: String = "release",
    /** 队列上限：条数与字节，先到者为准。 */
    val maxQueueRecords: Int = 200,
    val maxQueueBytes: Int = 2 * 1024 * 1024,
    /** 崩溃风暴闸：同一指纹每小时最多写多少条。 */
    val crashThrottlePerHour: Int = 10,
    /** 崩溃文件最多留多少条（下次启动并入队列）。 */
    val maxCrashRecords: Int = 50,
    /** 主动上报(handled)每类每天上限；<=0 表示不限。 */
    val handledDailyCap: Int = 20,
    /** 主动上报(handled)每天总上限；<=0 表示不限。 */
    val handledTotalDailyCap: Int = 100,
    /**
     * 是否允许「主动诊断(handled)」上报。
     *
     * 宿主把可预期的失败（如同步 429 限流 / timeout）也走 capture() 上报时，云端会按消息聚成
     * 一条条 open 的 app_error，形成噪音。关掉它可只保留崩溃/低内存/渲染进程死亡/ANR 等硬信号。
     */
    val handledDiagnosticsEnabled: Boolean = true,
    /**
     * 主线程卡死(ANR)看门狗超时毫秒；<=0 关闭。
     *
     * **默认 0（关闭）**：ANR 采集是新能力，墨水屏上重排版/解析可能偶发长时间占用主线程，
     * 未在目标机型验证前不应默认改变线上行为；宿主按需显式设置（如 8000）再开启。
     */
    val anrTimeoutMs: Long = 0,
    /** 源码 namespace；与 applicationId 不同时显式填写（如 debug/隔离包后缀）。 */
    val inAppPackage: String = "",
) {
    init {
        require(maxQueueRecords > 0 && maxQueueBytes > 0 && maxCrashRecords > 0 && crashThrottlePerHour > 0)
        require(handledDailyCap >= 0 && handledTotalDailyCap >= 0)
        require(anrTimeoutMs >= 0)
    }
    val enabled: Boolean get() = token.isNotBlank() && endpoint.isNotBlank()
}

/** 仅保存发送身份的摘要，不把 token 放进偏好文件或日志。 */
internal fun MonitorConfig.deliveryScope(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest("${endpoint.trimEnd('/')}|$app|$token".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
