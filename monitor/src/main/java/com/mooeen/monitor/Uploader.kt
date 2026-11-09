package com.mooeen.monitor

import org.json.JSONArray
import org.json.JSONObject

/**
 * 批量上报。每轮最多发 [maxBatches] 批，避免一次 WorkManager 运行里长时间占网。
 * 服务端按 (project, hash) 聚合，客户端不做本地合并——少一套账本就少一处能算错的地方。
 */
internal class Uploader(
    private val config: MonitorConfig,
    private val store: MonitorStore,
    private val transport: Transport,
) {
    sealed class Outcome {
        data class Sent(val records: Int) : Outcome()
        object Nothing : Outcome()
        object Disabled : Outcome()
        data class Retry(val reason: String) : Outcome()
    }

    fun flush(maxBatches: Int = 3): Outcome {
        if (!config.enabled) return Outcome.Disabled

        var total = 0
        repeat(maxBatches) {
            val records = store.pending(BATCH_RECORDS)
            if (records.isEmpty()) return if (total > 0) Outcome.Sent(total) else Outcome.Nothing

            val body = JSONObject()
                .put("app", config.app)
                .put("sdk", JSONObject()
                    .put("name", "moo-monitor-android")
                    .put("version", BuildInfo.VERSION)
                    .put("schema", 1))
                .put("records", JSONArray(records))

            when (val result = transport.post("${config.endpoint}/api/v1/app-errors/intake", config.token, body.toString())) {
                is Transport.Result.Ok -> {
                    store.dropFirst(records.size)
                    total += records.size
                }
                is Transport.Result.Unauthorized -> return Outcome.Disabled
                is Transport.Result.Rejected -> {
                    // 格式问题重试也不会成功：丢掉这一批，避免卡住后面的记录。
                    store.dropFirst(records.size)
                }
                is Transport.Result.Retryable -> return Outcome.Retry(result.reason)
            }
        }

        return Outcome.Sent(total)
    }

    private companion object {
        const val BATCH_RECORDS = 50
    }
}

internal object BuildInfo {
    const val VERSION = "0.1.0"
}
