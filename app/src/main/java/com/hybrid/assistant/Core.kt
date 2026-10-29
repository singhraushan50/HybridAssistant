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
    val tools: Boolean = true,
)

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun load(): Cfg { val d = Cfg(); return Cfg(
        p.getString("mode", d.mode)!!, p.getString("localUrl", d.localUrl)!!, p.getString("cloudUrl", d.cloudUrl)!!,
        p.getString("cloudKey", "")!!, p.getString("cloudModel", d.cloudModel)!!, p.getBoolean("speak", true), p.getBoolean("tools", true)) }
    fun save(c: Cfg) = p.edit().putString("mode", c.mode).putString("localUrl", c.localUrl).putString("cloudUrl", c.cloudUrl)
        .putString("cloudKey", c.cloudKey).putString("cloudModel", c.cloudModel).putBoolean("speak", c.speak).putBoolean("tools", c.tools).apply()
}

val BASE_PROMPT = "You are a helpful, concise assistant inside an Android app on the user's phone. Answer chat, questions and voice messages directly in plain text. You are connected to the phone through tools, so never claim you cannot open apps or make calls."
val TOOL_PROMPT = """

Tools exist, but use one ONLY when the user explicitly asks for a phone action (open a website or app, call, text, search their documents, run a command). For greetings, questions and normal chat, just answer in plain text and NEVER call a tool.
To use a tool, reply with ONLY one line in exactly this JSON format and nothing else:
<tool>{"name":"open_url","args":{"url":"https://example.com"}}</tool>
Tool names and args: open_url{url}, open_app{package}, dial{number}, sms{number,text}, search_docs{query}, shell{cmd}.
Examples:
User: hello -> Hi! How can I help you?
User: what is the capital of France -> The capital of France is Paris.
User: open youtube -> <tool>{"name":"open_url","args":{"url":"https://youtube.com"}}</tool>
After a tool result arrives, reply to the user in plain text. Never invent tool results."""

/** OpenAI-compatible client: same code talks to local llama-server and the optional cloud endpoint. */
object LlmClient {
    fun health(localUrl: String): Boolean = try {
        val c = URL(localUrl.removeSuffix("/v1") + "/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500; c.responseCode == 200
    } catch (e: Exception) { false }

    /** Returns (reply, "local"|"cloud") */
    fun chat(history: List<Msg>, cfg: Cfg): Pair<String, String> {
        val sysPrompt = if (cfg.tools) BASE_PROMPT + TOOL_PROMPT else BASE_PROMPT
        if (cfg.mode == "CLOUD") return post(cfg.cloudUrl, cfg.cloudKey, cfg.cloudModel, history, sysPrompt, false) to "cloud"
        return try { post(cfg.localUrl, "", "local", history, sysPrompt, true) to "local" }
        catch (e: Exception) {
            if (cfg.mode == "AUTO" && cfg.cloudKey.isNotBlank()) post(cfg.cloudUrl, cfg.cloudKey, cfg.cloudModel, history, sysPrompt, false) to "cloud"
            else throw e
        }
    }

    private fun post(base: String, key: String, model: String, history: List<Msg>, sysPrompt: String, local: Boolean): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sysPrompt))
        for (m in history) {
            if (m.imgB64 == null && m.wavB64 == null) { msgs.put(JSONObject().put("role", m.role).put("content", m.text)); continue }
            val parts = JSONArray()
            if (m.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", m.text))
            m.imgB64?.let { parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it"))) }
            m.wavB64?.let { parts.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", it).put("format", "wav"))) }
            msgs.put(JSONObject().put("role", m.role).put("content", parts))
        }
        val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.3).put("max_tokens", 768)
        if (local) body.put("top_k", 50).put("repeat_penalty", 1.1)
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
