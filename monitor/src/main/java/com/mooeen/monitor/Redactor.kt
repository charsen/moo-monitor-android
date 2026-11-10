package com.mooeen.monitor

/**
 * 客户端脱敏。服务端还会再过一遍 SecretRedactor，这里是第一道：
 * 崩溃消息和堆栈里经常夹着 URL query、绝对路径、长 token。
 */
internal object Redactor {

    private val URL = Regex("""(?:https?|content|file)://\S+""")
    private val BLOB = Regex("""(?<![A-Za-z0-9_$.])[A-Za-z0-9+/_\-]{32,}={0,2}(?![A-Za-z0-9_$.])""")
    private val PATH = Regex("""(?:/data/|/storage/|/sdcard/|/mnt/)[^\r\n:\"'<>]+""")
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val SECRET = Regex("""(?i)(?<![A-Za-z0-9])(password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|private[_-]?key|secret|api[_-]?key|authorization|cookie)\b([\"']?\s*[:=]\s*)(\"[^\"]*\"|'[^']*'|[^\s,;&]+)""")
    private val SECRET_KEY = Regex("""(?i)(password|passwd|pwd|secret|token|authorization|cookie|api.?key|credential)""")
    private val SECRET_QUERY = Regex("""([?&](?:password|pwd|token|secret|api_key|apikey|authorization|sig|signature)=)[^&\s]+""", RegexOption.IGNORE_CASE)

    fun scrub(value: String?): String? {
        if (value.isNullOrEmpty()) return value
        var out = value
        out = SECRET_QUERY.replace(out) { it.groupValues[1] + "***" }
        out = BEARER.replace(out, "Bearer ***")
        out = JWT.replace(out, "[jwt]")
        out = SECRET.replace(out) { it.groupValues[1] + it.groupValues[2] + "***" }
        out = URL.replace(out, "[url]")
        out = PATH.replace(out, "~/[path]")
        // 长类名、方法名、源码文件名也是字母串，不能把它们当成凭据毁掉调用栈。
        out = BLOB.replace(out) { match ->
            if (match.value.any { it.isDigit() || it in "+/_-=" }) "[blob]" else match.value
        }
        return out
    }

    /** 共享节点/字符串预算，避免浅层但高扇出的对象图在最终 8KB 检查前先耗尽内存。 */
    fun value(input: Any?): Any? = sanitize(input, 0, Budget())

    private class Budget(var nodes: Int = 128, var bytes: Int = 8 * 1024)

    private fun sanitize(input: Any?, depth: Int, budget: Budget): Any? {
        if (depth > 4 || budget.nodes-- <= 0) return "[truncated]"
        return when {
            input == null -> null
            input is String -> PayloadLimits.truncate(scrub(input).orEmpty(), minOf(1024, budget.bytes)).also {
                budget.bytes -= PayloadLimits.size(it)
            }
            input is Double && !input.isFinite() -> null
            input is Float && !input.isFinite() -> null
            input is Boolean || input is Byte || input is Short || input is Int || input is Long || input is Float || input is Double -> input
            input is Map<*, *> -> org.json.JSONObject().also { out ->
                for ((key, value) in input.entries.take(30)) {
                    if (budget.nodes <= 0 || budget.bytes <= 0) {
                        out.put("__truncated", "budget")
                        break
                    }
                    if (key !is String) continue
                    val safeKey = PayloadLimits.truncate(key, minOf(128, budget.bytes))
                    budget.bytes -= PayloadLimits.size(safeKey)
                    val safeValue = if (SECRET_KEY.containsMatchIn(key)) {
                        budget.nodes--
                        "***"
                    } else sanitize(value, depth + 1, budget)
                    out.put(safeKey, safeValue)
                }
            }
            input is Iterable<*> -> org.json.JSONArray().also { out ->
                for (item in input.take(30)) {
                    if (budget.nodes <= 0 || budget.bytes <= 0) break
                    out.put(sanitize(item, depth + 1, budget))
                }
            }
            else -> "[unsupported]"
        }
    }
}
