package app.synapse.remote

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** State of the embedded tailnet node (see tailnet/ in the repo). */
data class TailnetStatus(
    val state: String = "stopped", // stopped | starting | needs_login | running | error | unavailable
    val authUrl: String? = null,
    val dnsName: String? = null,
    val ips: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * The app's own Tailscale node: userspace, no VpnService, so it runs next to any system VPN (Mullvad, ...).
 * It never carries system traffic. [forward] returns a loopback port that tunnels to a tailnet address; OkHttp
 * connects there and TLS (pinned) stays end to end.
 */
object Tailnet {
    private const val TAG = "Tailnet"
    private var loaded: Boolean? = null
    private var started = false

    @JvmStatic private external fun nativeStart(dir: String, host: String, key: String, url: String): String
    @JvmStatic private external fun nativeStatus(): String
    @JvmStatic private external fun nativeForward(target: String): String
    @JvmStatic private external fun nativeStop()

    /** False when the app was built without libsynapsenet.so (tailnet/build-android.sh). */
    @Synchronized
    fun available(): Boolean {
        loaded?.let { return it }
        loaded = try {
            System.loadLibrary("synapsenet")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "embedded tailnet not built into this APK: ${e.message}")
            false
        }
        return loaded == true
    }

    /** Idempotent. [authKey]/[controlUrl] are optional (headless join / alternative control server). */
    @Synchronized
    fun start(ctx: Context, authKey: String = "", controlUrl: String = ""): String? {
        if (!available()) return "tailnet support is not built into this app"
        if (started) return null
        val dir = ctx.filesDir.resolve("tailnet").apply { mkdirs() }.absolutePath
        val host = "synapse-android-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val err = nativeStart(dir, host, authKey, controlUrl)
        if (err.isEmpty()) started = true
        return err.ifEmpty { null }
    }

    /** Start the node when the pairing includes a tailnet address (no-op otherwise). */
    fun ensure(ctx: Context, pair: PairInfo) {
        if (pair.tailnetHost != null) start(ctx)
    }

    fun status(): TailnetStatus {
        if (!available()) return TailnetStatus("unavailable")
        if (!started) return TailnetStatus("stopped")
        return try {
            val j = JSONObject(nativeStatus())
            TailnetStatus(
                state = j.optString("state", "stopped"),
                authUrl = j.optString("auth_url").ifEmpty { null },
                dnsName = j.optString("dns_name").ifEmpty { null },
                ips = j.optJSONArray("ips")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                error = j.optString("error").ifEmpty { null },
            )
        } catch (e: Exception) {
            TailnetStatus("error", error = e.message)
        }
    }

    /** Waits (up to [timeoutMs]) for the node to be up; true when it is `running`. */
    suspend fun awaitRunning(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        var left = timeoutMs
        while (left > 0) {
            if (status().state == "running") return@withContext true
            delay(250)
            left -= 250
        }
        status().state == "running"
    }

    /** Loopback port tunnelling to [target] ("host:port") over the tailnet, or null. */
    @Synchronized
    fun forward(target: String): Int? {
        if (!started) return null
        val r = nativeForward(target)
        return if (r.startsWith("port:")) r.removePrefix("port:").toIntOrNull() else null
    }

    @Synchronized
    fun stop() {
        if (started) nativeStop()
        started = false
    }
}
