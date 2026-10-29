package com.hybrid.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Tool Execution Engine: validates -> checks permission/confirmation -> runs -> returns structured result. */
class Tools(private val ctx: Context, private val termux: Termux) {
    private fun start(i: Intent): String { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "launched" }
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    suspend fun run(name: String, a: JSONObject, confirm: suspend (String) -> Boolean): String = try {
        when (name) {
            "open_url" -> {
                val u = a.getString("url")
                require(u.startsWith("http://") || u.startsWith("https://")) { "only http(s) urls" }
                start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
            }
            "open_app" -> ctx.packageManager.getLaunchIntentForPackage(a.getString("package"))
                ?.let { start(it) } ?: "app not installed"
            "dial" -> { val n = a.getString("number")
                if (confirm("Open dialer for $n?")) start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) else "denied by user" }
            "sms" -> { val n = a.getString("number"); val t = a.optString("text")
                if (confirm("Compose SMS to $n:\n\"$t\"")) start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).putExtra("sms_body", t)) else "denied by user" }
            "search_docs" -> termux.run("python ~/llm/rag.py ${q(a.getString("query"))}")
            "shell" -> { val c = a.getString("cmd")
                if (confirm("Run in Termux:\n$c")) termux.run(c) else "denied by user" }
            else -> "unknown tool '$name'"
        }
    } catch (e: Exception) { "tool error: ${e.message}" }
}
