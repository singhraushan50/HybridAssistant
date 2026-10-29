package com.hybrid.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
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

    private val callRe = Regex("^(?:please\\s+)?(?:call|dial|phone)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val openRe = Regex("^(?:please\\s+)?(?:open|launch)\\s+(.+)$", RegexOption.IGNORE_CASE)

    private fun findPhone(name: String): Pair<String, String>? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use {
            if (it.moveToFirst()) return it.getString(0) to it.getString(1)
        }
        return null
    }

    private fun findApp(label: String): String? {
        val pm = ctx.packageManager
        val l = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return (l.firstOrNull { it.loadLabel(pm).toString().equals(label, true) }
            ?: l.firstOrNull { it.loadLabel(pm).toString().contains(label, true) })?.activityInfo?.packageName
    }

    /** Deterministic shortcuts so basic commands never depend on a small model. Returns null if not a shortcut. */
    suspend fun route(text: String, confirm: suspend (String) -> Boolean): String? {
        val t = text.trim().trimEnd('.', '!', '?')
        callRe.find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.split(" ").size > 3) return null
            val digits = target.filter { it.isDigit() || it == '+' }
            val (label, number) = if (digits.length >= 3 && digits.length >= target.length - 3) target to digits
                else findPhone(target) ?: return "No contact matching '$target' found (is Contacts permission granted?)."
            return if (confirm("Call $label ($number)?")) { start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))); "Opening dialer for $label" } else "Cancelled."
        }
        openRe.find(t)?.let { m ->
            val name = m.groupValues[1].trim().removePrefix("the ").trim()
            if (name.contains('.') && !name.contains(' ')) {
                start(Intent(Intent.ACTION_VIEW, Uri.parse(if (name.startsWith("http")) name else "https://$name"))); return "Opening $name"
            }
            val pkg = findApp(name) ?: return "No installed app named '$name'."
            return ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { start(it); "Opened $name" } ?: "Can't launch $name."
        }
        return null
    }
}
