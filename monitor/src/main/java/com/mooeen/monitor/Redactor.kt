package com.mooeen.monitor

/**
 * 客户端脱敏。服务端还会再过一遍 SecretRedactor，这里是第一道：
 * 崩溃消息和堆栈里经常夹着 URL query、绝对路径、长 token。
 */
internal object Redactor {

    private val URL = Regex("""https?://\S+""")
    private val BLOB = Regex("""[A-Za-z0-9+/_\-]{32,}={0,2}""")
    private val APP_DIR = Regex("""/data/(?:user|data)/[^/]+/[^/\s:]+""")
    private val SDCARD = Regex("""/storage/(?:emulated/\d+|sdcard\d*)/[^\s:]+""")
    private val SECRET_QUERY = Regex("""([?&](?:password|pwd|token|secret|api_key|apikey|authorization|sig|signature)=)[^&\s]+""", RegexOption.IGNORE_CASE)

    fun scrub(value: String?): String? {
        if (value.isNullOrEmpty()) return value
        var out = value
        out = SECRET_QUERY.replace(out) { it.groupValues[1] + "***" }
        out = URL.replace(out, "[url]")
        out = APP_DIR.replace(out, "~")
        out = SDCARD.replace(out, "~/sdcard")
        out = BLOB.replace(out, "[blob]")
        return out
    }
}
