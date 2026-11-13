package app.synapse.remote

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val agent: String = "",
    val cwd: String = "",
    val state: String = "idle", // idle | working | needs_input
    val created: Long = 0,
    val activity: Long = 0,
    /** Friendlier name than [id] (set for mirrors: the project folder). */
    val title: String = "",
    /** A mirror of an agent running in a plain terminal on the desktop: shown, but nothing can be typed into it. */
    val readonly: Boolean = false,
)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

@Serializable
data class Cursor(val x: Int = 0, val y: Int = 0, val visible: Boolean = true)

@Serializable
data class Screen(val text: String = "", val state: String = "idle", val cursor: Cursor? = null, val readonly: Boolean = false)

/** A one-tap answer a notification may offer, e.g. Yes = keys ["y","Enter"]. Decided by the bridge. */
@Serializable
data class QuickAction(val label: String, val keys: List<String> = emptyList())

@Serializable
data class BridgeEvent(
    val id: Long = 0,
    val type: String = "", // state | attention | hook
    val session: String = "",
    val agent: String = "",
    val state: String = "",
    val message: String = "",
    val ts: Double = 0.0,
    val actions: List<QuickAction> = emptyList(),
    /** Seconds between the event and its delivery: large for events replayed after being offline. */
    val age: Double = 0.0,
)

private val CGNAT = Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.\\d+\\.\\d+$")

/** Tailscale MagicDNS name or 100.64.0.0/10 address. */
fun isTailnetHost(h: String) = h.endsWith(".ts.net") || CGNAT.matches(h)

/** Human label for the path the app is using to reach the desktop. */
fun describeHost(h: String?): String = when {
    h == null -> ""
    h == "127.0.0.1" -> "USB (adb reverse)"
    isTailnetHost(h) -> "Tailscale · $h"
    h.startsWith("192.168.") || h.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) -> "LAN · $h"
    else -> h
}

/** What the QR printed by `synapse pair` contains. */
data class PairInfo(
    val hosts: List<String>,
    val port: Int,
    val token: String,
    val fingerprint: String,
    val name: String,
) {
    val tailnetHost: String? get() = hosts.firstOrNull(::isTailnetHost)

    companion object {
        private val HOST_RE = Regex("^[A-Za-z0-9.\\-:]{1,253}$")
        private val FP_RE = Regex("^[0-9a-f]{64}$")

        /** synapse://pair?hosts=a,b&port=7433&token=...&fp=<sha256 hex>&name=<host> */
        fun parse(raw: String): PairInfo? = runCatching {
            val u = Uri.parse(raw.trim())
            if (u.scheme != "synapse") return null
            val hosts = u.getQueryParameter("hosts")?.split(",")?.map { it.trim() }
                ?.filter { HOST_RE.matches(it) }.orEmpty()
            val token = u.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
            val fp = u.getQueryParameter("fp")?.replace(":", "")?.lowercase() ?: return null
            val port = u.getQueryParameter("port")?.toIntOrNull() ?: 7433
            if (hosts.isEmpty() || port !in 1..65535 || !FP_RE.matches(fp)) return null
            PairInfo(hosts, port, token, fp, (u.getQueryParameter("name") ?: hosts.first()).take(64))
        }.getOrNull()
    }
}
