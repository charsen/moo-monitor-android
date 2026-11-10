package com.mooeen.monitor

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** 单进程双日志。崩溃文件与常规队列始终使用独立锁。 */
internal class MonitorStore(rootDir: File, private val config: MonitorConfig) {
    private val dir = rootDir.apply { mkdirs() }
    private val queueFile = File(dir, "queue.jsonl")
    private val crashFile = File(dir, "crash.jsonl")
    private val queueLock = Any()
    private val crashLock = Any()
    private val lost = AtomicInteger()
    val dropped: Int get() = lost.get()

    // 只在启动时扫描计数，追加路径不再为每条事件重读整个文件。
    private var queueCount = initialize(queueFile, config.maxQueueRecords)
    private var crashCount = initialize(crashFile, config.maxCrashRecords)

    data class Batch(val records: List<JSONObject>, internal val lines: List<String>)

    fun append(record: JSONObject) = synchronized(queueLock) {
        queueCount = appendTo(queueFile, record, queueCount, config.maxQueueRecords, sync = false)
    }

    fun appendCrash(record: JSONObject) = synchronized(crashLock) {
        crashCount = appendTo(crashFile, record, crashCount, config.maxCrashRecords, sync = true)
    }

    private fun appendTo(file: File, record: JSONObject, count: Int, cap: Int, sync: Boolean): Int {
        return try {
            val line = record.toString()
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (bytes.size > config.maxQueueBytes) {
                lost.incrementAndGet()
                return count
            }
            if (count + 1 > cap || file.length() + bytes.size > config.maxQueueBytes) {
                val lines = readLines(file) + line
                val kept = bounded(lines, cap)
                replace(file, kept)
                lost.addAndGet(lines.size - kept.size)
                kept.size
            } else {
                FileOutputStream(file, true).use {
                    it.write(bytes)
                    if (sync) it.fd.sync()
                }
                count + 1
            }
        } catch (_: Throwable) {
            lost.incrementAndGet()
            // 修剪半行，防止下一次成功追加与它粘连后一起变成坏记录。
            runCatching {
                val lines = readLines(file).filter { runCatching { JSONObject(it) }.isSuccess }
                replace(file, lines)
                lines.size
            }.getOrDefault(count)
        }
    }

    /** 先原子提交目标，再删源；中途退出重放同一原始行，不制造新 event_id。 */
    fun mergeCrashIntoQueue() = synchronized(crashLock) {
        val crashes = readLines(crashFile)
        if (crashes.isEmpty()) return@synchronized
        synchronized(queueLock) {
            runCatching {
                val lines = (readLines(queueFile) + crashes).distinct()
                val kept = bounded(lines, config.maxQueueRecords)
                replace(queueFile, kept)
                queueCount = kept.size
                lost.addAndGet(lines.size - kept.size)
                replace(crashFile, emptyList())
                crashCount = 0
            }
        }
    }

    fun pending(limit: Int): List<JSONObject> = readBatch(limit).records

    /** 保留原始行作为收据。坏行只清理、计数一次，不占已发送记录的位置。 */
    fun readBatch(limit: Int): Batch = synchronized(queueLock) {
        val lines = readLines(queueFile)
        val valid = ArrayList<String>(lines.size)
        val records = ArrayList<JSONObject>()
        val receipts = ArrayList<String>()
        for (line in lines) {
            if (records.size >= limit) {
                valid.add(line)
                continue
            }
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            valid.add(line)
            if (records.size < limit) {
                records.add(obj)
                receipts.add(line)
            }
        }
        if (valid.size != lines.size) {
            runCatching {
                replace(queueFile, valid)
                queueCount = valid.size
                lost.addAndGet(lines.size - valid.size)
            }
        }
        Batch(records, receipts)
    }

    /** 按发送时的原始行确认，轮转或并发追加都不会删除未发送的新记录。 */
    fun acknowledge(batch: Batch, indexes: Collection<Int>): Boolean = synchronized(queueLock) {
        runCatching {
            val receipts = indexes.map { batch.lines[it] }.groupingBy { it }.eachCount().toMutableMap()
            val rest = readLines(queueFile).filter { line ->
                val count = receipts[line] ?: 0
                if (count > 0) receipts[line] = count - 1
                count == 0
            }
            replace(queueFile, rest)
            queueCount = rest.size
        }.isSuccess
    }

    fun queueBytes(): Long = synchronized(queueLock) { queueFile.length() }

    /** 兼容旧队列的超限/半行；只在安装时修复尾部，不给每次追加增加读盘。 */
    private fun initialize(file: File, cap: Int): Int {
        val lines = readLines(file)
        val valid = lines.filter { runCatching { JSONObject(it) }.isSuccess }
        val kept = bounded(valid, cap)
        val tornTail = file.length() > 0 && java.io.RandomAccessFile(file, "r").use {
            it.seek(it.length() - 1)
            it.read() != '\n'.code
        }
        if (kept != lines || tornTail) replace(file, kept)
        lost.addAndGet(lines.size - kept.size)
        return kept.size
    }

    private fun bounded(lines: List<String>, cap: Int): List<String> {
        if (lines.size <= cap && lines.sumOf { PayloadLimits.size(it) + 1L } <= config.maxQueueBytes) return lines
        var bytes = 0L
        val kept = ArrayDeque<String>()
        val target = maxOf(1, cap * 3 / 4)
        for (line in lines.asReversed()) {
            val size = PayloadLimits.size(line) + 1L
            if (size > config.maxQueueBytes) continue
            if (kept.size >= target || bytes + size > config.maxQueueBytes) break
            kept.addFirst(line)
            bytes += size
        }
        return kept.toList()
    }

    /** 同目录临时文件 + fsync + rename，不能先截断原文件或先删除崩溃源。 */
    private fun replace(file: File, lines: List<String>) {
        val temp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                for (line in lines) output.write((line + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temp.renameTo(file)) { "journal rename failed" }
        } finally {
            temp.delete()
        }
    }

    private fun readLines(file: File): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    companion object {
        fun forApp(context: Context, config: MonitorConfig) =
            MonitorStore(File(context.filesDir, "monitor"), config)
    }
}
