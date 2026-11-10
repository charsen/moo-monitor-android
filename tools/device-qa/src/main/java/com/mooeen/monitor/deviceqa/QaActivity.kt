package com.mooeen.monitor.deviceqa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.mooeen.monitor.MooMonitor
import org.json.JSONObject
import java.io.File

class QaActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var web: WebView? = null
    private var nonce: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "Moo Monitor · 隔离测试"; textSize = 24f })
        setContentView(root)
        val requested = Intent(intent)
        handler.post { dispatch(requested) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handler.post { dispatch(intent) }
    }

    private fun dispatch(input: Intent) {
        nonce = input.getStringExtra("qa_nonce")
        val action = input.getStringExtra("qa_action") ?: "snapshot"
        Log.i("MonitorDeviceQa", "action=$action installed=${MooMonitor.installed}")
        when (action) {
            "capture" -> {
                val start = SystemClock.elapsedRealtimeNanos()
                MooMonitor.capture(IllegalStateException("deviceqa handled"), mapOf("password" to "fixture-secret"))
                result("capture", JSONObject().put("elapsed_us", (SystemClock.elapsedRealtimeNanos() - start) / 1000))
            }
            "crash" -> Thread({
                val error = IllegalStateException("deviceqa uncaught")
                CrashTiming.startNanos = SystemClock.elapsedRealtimeNanos()
                throw error
            }, "deviceqa-crash").start()
            "flush" -> { MooMonitor.flush(); result(action) }
            "memory" -> { repeat(2) { MooMonitor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }; result(action) }
            "token_b" -> { getSharedPreferences("device-qa", 0).edit().putString("token_variant", "B").commit(); result(action) }
            "webview" -> { openWebView(crash = true); result("webview_requested") }
            "snapshot" -> result("snapshot")
        }
    }

    private fun openWebView(crash: Boolean) {
        val view = WebView(this)
        web = view
        view.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                MooMonitor.capture("webview_gone", mapOf("did_crash" to detail.didCrash(), "renderer_priority" to detail.rendererPriorityAtExit()))
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                web = null
                result("webview_gone", JSONObject().put("did_crash", detail.didCrash()))
                root.addView(TextView(this@QaActivity).apply { text = "Renderer recovered"; textSize = 24f })
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (crash && url.startsWith("data:")) handler.postDelayed({ view.loadUrl("chrome://crash") }, 500)
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 400))
        view.loadData("<p>monitor deviceqa</p>", "text/html", "UTF-8")
    }

    private fun result(action: String, extra: JSONObject = JSONObject()) {
        fun count(name: String) = File(filesDir, "monitor/$name").takeIf { it.isFile }?.readLines()?.count { it.isNotBlank() } ?: 0
        extra.put("action", action).put("qa_nonce", nonce).put("installed", MooMonitor.installed)
            .put("queue", count("queue.jsonl")).put("crash", count("crash.jsonl"))
        File(filesDir, "device-qa-result.json").writeText(extra.toString())
        Log.i("MonitorDeviceQa", extra.toString())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        super.onDestroy()
    }
}
