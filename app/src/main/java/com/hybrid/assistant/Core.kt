package com.hybrid.assistant

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val role: String, val text: String, val imgB64: String? = null, val wavB64: String? = null)
data class UiMsg(val role: String, val text: String, val note: String = "")

/** mode: LOCAL (offline only) | AUTO (local, cloud fallback) | CLOUD */
data class Cfg(
    val mode: String = "LOCAL",
    val localUrl: String = "http://127.0.0.1:8080/v1",
    val cloudUrl: String = "https://api.openai.com/v1",
    val cloudKey: String = "",
    val cloudModel: String = "gpt-4o-audio-preview",
    val speak: Boolean = true,
)

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun load(): Cfg { val d = Cfg(); return Cfg(
        p.getString("mode", d.mode)!!, p.getString("localUrl", d.localUrl)!!, p.getString("cloudUrl", d.cloudUrl)!!,
        p.getString("cloudKey", "")!!, p.getString("cloudModel", d.cloudModel)!!, p.getBoolean("speak", true)) }
    fun save(c: Cfg) = p.edit().putString("mode", c.mode).putString("localUrl", c.localUrl).putString("cloudUrl", c.cloudUrl)
        .putString("cloudKey", c.cloudKey).putString("cloudModel", c.cloudModel).putBoolean("speak", c.speak).apply()
}

val SYSTEM_PROMPT = """You are a private on-device assistant running on an Android phone. Be concise.
You can call tools. To call one, reply with ONLY: <tool>{"name":"TOOL","args":{...}}</tool>
Tools:
- open_url {url}            open a link in the browser (also https://wa.me/NUMBER?text=... for WhatsApp)
- open_app {package}        launch an installed app by package name
- dial {number}             open the phone dialer
- sms {number,text}         open SMS composer with text prefilled
- search_docs {query}       search the user's local knowledge base (offline)
- shell {cmd}               run a shell command in Termux
After a tool result arrives, answer the user normally. Never invent tool results."""

/** OpenAI-compatible client: same code talks to local llama-server and the optional cloud endpoint. */
object LlmClient {
    fun health(localUrl: String): Boolean = try {
        val c = URL(localUrl.removeSuffix("/v1") + "/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500; c.responseCode == 200
    } catch (e: Exception) { false }

    /** Returns (reply, "local"|"cloud") */
    fun chat(history: List<Msg>, cfg: Cfg): Pair<String, String> {
        if (cfg.mode == "CLOUD") return post(cfg.cloudUrl, cfg.cloudKey, cfg.cloudModel, history) to "cloud"
        return try { post(cfg.localUrl, "", "local", history) to "local" }
        catch (e: Exception) {
            if (cfg.mode == "AUTO" && cfg.cloudKey.isNotBlank()) post(cfg.cloudUrl, cfg.cloudKey, cfg.cloudModel, history) to "cloud"
            else throw e
        }
    }

    private fun post(base: String, key: String, model: String, history: List<Msg>): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (m in history) {
            if (m.imgB64 == null && m.wavB64 == null) { msgs.put(JSONObject().put("role", m.role).put("content", m.text)); continue }
            val parts = JSONArray()
            if (m.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", m.text))
            m.imgB64?.let { parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it"))) }
            m.wavB64?.let { parts.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", it).put("format", "wav"))) }
            msgs.put(JSONObject().put("role", m.role).put("content", parts))
        }
        val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.7).put("max_tokens", 768)
        val c = URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 600_000
        c.setRequestProperty("Content-Type", "application/json")
        if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val txt = stream.bufferedReader().readText()
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}: ${txt.take(300)}")
        return JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}

fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
