package com.hybrid.assistant

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class VM(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val termux = Termux(app)
    private val tools = Tools(app, termux)
    val rec = Recorder()
    private val speaker = Speaker(app)
    val msgs = mutableStateListOf<UiMsg>()
    var cfg by mutableStateOf(prefs.load())
    var status by mutableStateOf("checking…")
    var busy by mutableStateOf(false)
    var recording by mutableStateOf(false)
    var imgB64 by mutableStateOf<String?>(null)
    var confirmReq by mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null)
    private val history = mutableListOf<Msg>()
    private val toolRe = Regex("<tool>(.*?)</tool>", RegexOption.DOT_MATCHES_ALL)

    fun saveCfg(c: Cfg) { cfg = c; prefs.save(c); refresh() }
    private fun sys(t: String) { msgs.add(UiMsg("system", t)) }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        status = if (LlmClient.health(cfg.localUrl)) "local model online"
        else if (cfg.mode != "LOCAL" && cfg.cloudKey.isNotBlank()) "local offline · cloud ready" else "local model offline"
    }

    fun setup() = viewModelScope.launch {
        if (!termux.installed()) { sys("Termux not installed (install from F-Droid)."); return@launch }
        sys("Installing scripts into Termux…")
        sys(runCatching { termux.installScripts() }.getOrElse { "Failed: ${it.message}\nEnable allow-external-apps in Termux (see README)." })
        sys("Building llama.cpp in Termux (10-30 min). Log: ~/llm/setup.log")
        runCatching { termux.run("nohup bash ~/llm/setup.sh > ~/llm/setup.log 2>&1 &") }
    }

    fun startServer() = viewModelScope.launch {
        sys("Starting llama-server in a Termux session…")
        runCatching { termux.startSession("bash ~/llm/serve.sh") }.onFailure { sys("Failed: ${it.message}"); return@launch }
        repeat(60) { delay(2000); if (withContext(Dispatchers.IO) { LlmClient.health(cfg.localUrl) }) { refresh(); sys("Model ready."); return@launch } }
        sys("Server not responding. Log:\n" + runCatching { termux.run("tail -n 12 ~/llm/server.log") }.getOrDefault("(no log - did you tap Reinstall Termux scripts?)"))
    }

    fun updateScripts() = viewModelScope.launch {
        sys(runCatching { termux.installScripts() }.getOrElse { "Failed: ${it.message}" })
    }

    fun toggleMic() {
        if (!recording) { rec.start(); recording = true }
        else { recording = false; send("", rec.stop()) }
    }

    fun attach(uri: Uri?) {
        if (uri == null) { imgB64 = null; return }
        val ctx = getApplication<Application>()
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        val s = 768f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
        val sc = if (s < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val o = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 85, o); imgB64 = b64(o.toByteArray())
    }

    private suspend fun ask(d: String): Boolean {
        val def = CompletableDeferred<Boolean>(); confirmReq = d to def
        return def.await().also { confirmReq = null }
    }

    fun send(text: String, wav: ByteArray? = null) {
        if (busy || (text.isBlank() && wav == null && imgB64 == null)) return
        val img = imgB64; imgB64 = null; busy = true
        msgs.add(UiMsg("user", text.ifBlank { if (wav != null) "🎤 (voice message)" else "(image)" }, if (img != null) "📎 image" else ""))
        history.add(Msg("user", text, img, wav?.let { b64(it) }))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val routed = if (wav == null && img == null && cfg.tools) tools.route(text, ::ask) else null
                if (routed != null) { msgs.add(UiMsg("assistant", routed, "action")); history.add(Msg("assistant", routed)) }
                else for (step in 0..3) {
                    val (reply, via) = LlmClient.chat(history, cfg)
                    history.add(Msg("assistant", reply))
                    val j = if (cfg.tools) toolRe.find(reply)?.let { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() } else null
                    if (j == null || j.optString("name").isEmpty()) {
                        val clean = reply.replace(toolRe, "").trim().ifBlank { reply.trim() }
                        msgs.add(UiMsg("assistant", clean, via)); if (cfg.speak) speaker.speak(clean); break
                    }
                    val name = j.getString("name")
                    msgs.add(UiMsg("tool", "🔧 $name ${j.optJSONObject("args") ?: ""}"))
                    val res = tools.run(name, j.optJSONObject("args") ?: JSONObject(), ::ask)
                    msgs.add(UiMsg("tool", "↳ ${res.take(400)}"))
                    history.add(Msg("user", "Tool result for $name:\n${res.take(3000)}"))
                }
            } catch (e: Exception) { sys("Error: ${e.message}") }
            // drop heavy media from history after it has been consumed
            for (i in history.indices) history[i] = history[i].copy(imgB64 = null, wavB64 = null)
            busy = false; refresh()
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(vm: VM = viewModel()) {
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { vm.attach(it) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleMic() }
    val ctx = LocalContext.current
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) contactsPerm.launch(Manifest.permission.READ_CONTACTS)
        while (true) { vm.refresh(); delay(5000) }
    }
    LaunchedEffect(vm.msgs.size) { if (vm.msgs.isNotEmpty()) list.animateScrollToItem(vm.msgs.size - 1) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hybrid AI Assistant", style = MaterialTheme.typography.titleMedium)
                Text("${vm.status} · mode ${vm.cfg.mode}", style = MaterialTheme.typography.labelSmall)
            }
            TextButton({ vm.startServer() }) { Text("▶ Start") }
            TextButton({ settings = true }) { Text("⚙") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.msgs.isEmpty()) item { Text("Tap ⚙ → Setup Termux, put a .gguf (+ mmproj) in ~/llm/models, tap ▶ Start, then chat by text, 🎤 voice or 🖼 image.", style = MaterialTheme.typography.bodySmall) }
            items(vm.msgs) { m ->
                val mine = m.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = if (m.role == "assistant") 4.dp else if (mine) 8.dp else 0.dp) {
                        Text(m.text, Modifier.padding(10.dp).widthIn(max = 320.dp),
                            style = if (m.role == "assistant" || mine) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall)
                    }
                    if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (vm.imgB64 == null) pick.launch("image/*") else vm.attach(null) }) { Text(if (vm.imgB64 == null) "🖼" else "✅🖼") }
            TextButton({ micPerm.launch(Manifest.permission.RECORD_AUDIO) }) { Text(if (vm.recording) "⏹" else "🎤") }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask anything…") }, maxLines = 4)
            Button({ vm.send(input); input = "" }, enabled = !vm.busy) { Text("➤") }
        }
    }

    vm.confirmReq?.let { (desc, d) ->
        AlertDialog(onDismissRequest = { d.complete(false) }, title = { Text("Allow this action?") }, text = { Text(desc) },
            confirmButton = { TextButton({ d.complete(true) }) { Text("Allow") } },
            dismissButton = { TextButton({ d.complete(false) }) { Text("Deny") } })
    }
    if (settings) SettingsDialog(vm) { settings = false }
}

@Composable
fun SettingsDialog(vm: VM, close: () -> Unit) {
    var c by remember { mutableStateOf(vm.cfg) }
    AlertDialog(onDismissRequest = close, title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { listOf("LOCAL" to "Offline", "AUTO" to "Auto fallback", "CLOUD" to "Cloud").forEach { (k, l) ->
                    FilterChip(c.mode == k, { c = c.copy(mode = k) }, { Text(l) }, Modifier.padding(end = 4.dp)) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(c.speak, { c = c.copy(speak = it) }); Text("  Speak replies") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(c.tools, { c = c.copy(tools = it) }); Text("  Enable tools (open apps, call, SMS…)") }
                OutlinedTextField(c.localUrl, { c = c.copy(localUrl = it) }, label = { Text("Local endpoint") }, singleLine = true)
                OutlinedTextField(c.cloudUrl, { c = c.copy(cloudUrl = it) }, label = { Text("Cloud endpoint (OpenAI-compatible)") }, singleLine = true)
                OutlinedTextField(c.cloudKey, { c = c.copy(cloudKey = it) }, label = { Text("API key") }, singleLine = true)
                OutlinedTextField(c.cloudModel, { c = c.copy(cloudModel = it) }, label = { Text("Cloud model") }, singleLine = true)
                OutlinedButton({ vm.setup(); close() }) { Text("Setup Termux (install scripts + build llama.cpp)") }
                OutlinedButton({ vm.updateScripts(); close() }) { Text("Reinstall Termux scripts only") }
            }
        },
        confirmButton = { TextButton({ vm.saveCfg(c); close() }) { Text("Save") } },
        dismissButton = { TextButton(close) { Text("Cancel") } })
}
