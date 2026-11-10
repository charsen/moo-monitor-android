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
    /** 源码 namespace；与 applicationId 不同时显式填写（如 debug/隔离包后缀）。 */
    val inAppPackage: String = "",
) {
    init {
        require(maxQueueRecords > 0 && maxQueueBytes > 0 && maxCrashRecords > 0 && crashThrottlePerHour > 0)
    }
    val enabled: Boolean get() = token.isNotBlank() && endpoint.isNotBlank()
}

/** 仅保存发送身份的摘要，不把 token 放进偏好文件或日志。 */
internal fun MonitorConfig.deliveryScope(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest("${endpoint.trimEnd('/')}|$app|$token".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
