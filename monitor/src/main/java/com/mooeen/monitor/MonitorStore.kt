package com.mooeen.monitor

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 落盘。两个文件、两把锁：
 *
 * - `queue.jsonl`：常规路径（capture / 低内存 / ANR）追加写，由 [queueLock] 保护。
 * - `crash.jsonl`：崩溃处理器专用，只被 [crashLock] 保护。
 *
 * 崩溃路径**不能**和队列共用锁：崩溃线程可能正持着队列锁（崩溃就发生在写队列时），
 * 或另一线程正在重写 2MB 的队列，处理器会一直等，而进程随时被系统杀掉——记录就丢了。
 */
internal class MonitorStore(rootDir: File, private val config: MonitorConfig) {

    private val dir = rootDir.apply { mkdirs() }
    private val queueFile = File(dir, "queue.jsonl")
    private val crashFile = File(dir, "crash.jsonl")

    private val queueLock = Any()
    private val crashLock = Any()

    /** 因容量或写失败丢掉的条数，随下一次上报的 payload 带上。 */
    @Volatile
    var dropped: Int = 0
        private set

    fun append(record: JSONObject) {
        appendTo(queueFile, record, queueLock, config.maxQueueRecords)
    }

    /** 崩溃路径：独立文件、独立锁，不碰队列。 */
    fun appendCrash(record: JSONObject) {
        appendTo(crashFile, record, crashLock, config.maxCrashRecords)
    }

    private fun appendTo(file: File, record: JSONObject, lock: Any, cap: Int) {
        synchronized(lock) {
            try {
                file.appendText(record.toString() + "\n")
                if (countLines(file) > cap) {
                    val kept = readLines(file).takeLast(cap * 3 / 4)
                    dropped += countLines(file) - kept.size
                    file.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            } catch (_: Throwable) {
                // 磁盘满 / 权限：丢弃这条，不再抛。崩溃路径尤其不能在这里炸。
                dropped++
            }
        }
    }

    /** 下次启动把崩溃文件并入队列，然后清空。 */
    fun mergeCrashIntoQueue() {
        val lines = synchronized(crashLock) {
            val lines = readLines(crashFile)
            crashFile.delete()
            lines
        }
        if (lines.isEmpty()) return
        synchronized(queueLock) {
            runCatching {
                queueFile.appendText(lines.joinToString("\n", postfix = "\n"))
                if (countLines(queueFile) > config.maxQueueRecords) {
                    val kept = readLines(queueFile).takeLast(config.maxQueueRecords * 3 / 4)
                    dropped += countLines(queueFile) - kept.size
                    queueFile.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    /** 读出待发记录（坏行跳过并计入 dropped）。不清空——发送成功后再 [clearQueue]。 */
    fun pending(limit: Int): List<JSONObject> {
        val lines = synchronized(queueLock) { readLines(queueFile) }
        val out = ArrayList<JSONObject>(limit)
        for (line in lines) {
            if (out.size >= limit) break
            val obj = runCatching { JSONObject(line) }.getOrNull()
            if (obj == null) {
                dropped++
                continue
            }
            out.add(obj)
        }
        return out
    }

    /** 发送成功后移除前 n 行（按读取顺序），保留其余未发记录。 */
    fun dropFirst(count: Int) {
        if (count <= 0) return
        synchronized(queueLock) {
            runCatching {
                val lines = readLines(queueFile)
                val rest = if (lines.size <= count) emptyList() else lines.drop(count)
                if (rest.isEmpty()) queueFile.delete()
                else queueFile.writeText(rest.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun queueBytes(): Long = runCatching { queueFile.length() }.getOrDefault(0)

    private fun readLines(file: File): List<String> =
        runCatching { file.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList() }
            .getOrDefault(emptyList())

    private fun countLines(file: File): Int =
        runCatching { file.takeIf { it.exists() }?.useLines { it.count { l -> l.isNotBlank() } } ?: 0 }
            .getOrDefault(0)

    companion object {
        /** 生产入口：固定放在应用私有目录，随卸载清除。 */
        fun forApp(context: Context, config: MonitorConfig) =
            MonitorStore(File(context.filesDir, "monitor"), config)
    }
}
