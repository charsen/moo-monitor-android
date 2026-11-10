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
        data class Retry(val reason: String, val afterSeconds: Long = 0) : Outcome()
        object More : Outcome()
    }

    fun flush(maxBatches: Int = 3): Outcome {
        if (!config.enabled) return Outcome.Disabled

        var total = 0
        repeat(maxBatches) {
            val batch = store.readBatch(BATCH_RECORDS)
            val pending = batch.records
            if (pending.isEmpty()) return if (total > 0) Outcome.Sent(total) else Outcome.Nothing

            // 条数与字节先到者为准：一条超长栈不该把同批的其它记录一起拖进 422。
            val records = PayloadLimits.batch(pending, BATCH_RECORDS, PayloadLimits.BATCH_BYTES)

            val body = JSONObject()
                .put("app", config.app)
                .put("sdk", JSONObject()
                    .put("name", "moo-monitor-android")
                    .put("version", BuildInfo.VERSION)
                    .put("schema", 1))
                .put("records", JSONArray(records))

            when (val result = transport.post("${config.endpoint.trimEnd('/')}/api/v1/app-errors/intake", config.token, body.toString())) {
                is Transport.Result.Ok -> {
                    val confirmed = confirmedIndexes(result.body, records.size)
                        ?: return Outcome.Retry("invalid response")
                    if (confirmed.isEmpty()) return Outcome.Retry("partial response")
                    if (!store.acknowledge(batch, confirmed)) return Outcome.Retry("acknowledgement failed")
                    android.util.Log.d(TAG, "intake confirmed: ${confirmed.size} 条")
                    total += confirmed.size
                    if (confirmed.size < records.size) return Outcome.Retry("partial response")
                }
                is Transport.Result.Unauthorized -> {
                    android.util.Log.w(TAG, "intake 被拒(${result.code})：token 无效或撤权，停止发送")
                    return Outcome.Disabled
                }
                is Transport.Result.Rejected -> {
                    // 格式问题重试也不会成功：丢掉这一批，避免卡住后面的记录。
                    android.util.Log.w(TAG, "intake 格式错误，丢弃 ${records.size} 条")
                    if (!store.acknowledge(batch, records.indices.toList())) return Outcome.Retry("acknowledgement failed")
                }
                is Transport.Result.Retryable -> {
                    android.util.Log.w(TAG, "intake 稍后重试：${result.reason}")
                    return Outcome.Retry(result.reason, result.afterSeconds)
                }
            }
        }

        return if (store.pending(1).isEmpty()) Outcome.Sent(total) else Outcome.More
    }

    /** 未确认、重复 index 或形状不完整的响应整批保留；只删除服务端明确终结的记录。 */
    private fun confirmedIndexes(body: String, count: Int): List<Int>? {
        return runCatching {
            val response = JSONObject(body)
            if (response.opt("ok") != true) return null
            val results = response.optJSONArray("results") ?: return null
            if (results.length() != count) return null
            val seen = HashSet<Int>()
            val confirmed = ArrayList<Int>()
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val index = result.opt("index")
                if (index !is Int || index !in 0 until count || !seen.add(index)) return null
                when (result.optString("status")) {
                    "saved", "created", "updated", "duplicate", "filtered" -> confirmed.add(index)
                    "skipped" -> if (result.opt("retryable") == false) confirmed.add(index)
                    else -> return null
                }
            }
            confirmed
        }.getOrNull()
    }

    private companion object {
        const val TAG = "MooMonitor"
        const val BATCH_RECORDS = 50
    }
}

internal object BuildInfo {
    const val VERSION = "0.1.0"
}
