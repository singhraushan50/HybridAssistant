package com.hybrid.assistant

import android.app.PendingIntent
import android.content.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android IPC -> Termux (RUN_COMMAND intent) and results back via PendingIntent broadcast. */
class Termux(private val ctx: Context) {
    private val prefix = "/data/data/com.termux/files/usr"
    private val home = "/data/data/com.termux/files/home"

    fun installed() = runCatching { ctx.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    suspend fun run(cmd: String, timeoutMs: Long = 120_000): String = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val action = "com.hybrid.assistant.RESULT.${System.nanoTime()}"
            val rcv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    runCatching { ctx.unregisterReceiver(this) }
                    val r = i.getBundleExtra("result")
                    val out = r?.getString("stdout").orEmpty().trim()
                    val err = (r?.getString("stderr").orEmpty() + r?.getString("errmsg").orEmpty()).trim()
                    val code = r?.getInt("exitCode", 0) ?: 0
                    if (cont.isActive) cont.resume(if (code == 0) out.ifBlank { "(ok)" } else "exit $code\n$out\n$err".trim())
                }
            }
            ContextCompat.registerReceiver(ctx, rcv, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "$prefix/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            try { ctx.startService(i) } catch (e: Exception) {
                runCatching { ctx.unregisterReceiver(rcv) }; cont.resumeWithException(e)
            }
            cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(rcv) } }
        }
    }

    /** Copies bundled scripts (assets/termux/*) into ~/llm inside Termux. */
    suspend fun installScripts(): String {
        val sb = StringBuilder("mkdir -p ~/llm/models ~/llm/kb;")
        for (n in ctx.assets.list("termux").orEmpty()) {
            val data = ctx.assets.open("termux/$n").readBytes()
            sb.append("echo '${b64(data)}' | base64 -d > ~/llm/$n;")
        }
        sb.append("chmod +x ~/llm/*.sh; ls ~/llm")
        return run(sb.toString())
    }

    suspend fun startServer(): String = run("bash ~/llm/start_llm.sh")
}
