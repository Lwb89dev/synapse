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
)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

@Serializable
data class Screen(val text: String = "", val state: String = "idle")

@Serializable
data class BridgeEvent(
    val id: Long = 0,
    val type: String = "", // state | attention | hook
    val session: String = "",
    val agent: String = "",
    val state: String = "",
    val message: String = "",
    val ts: Double = 0.0,
)

/** What the QR printed by `synapse pair` contains. */
data class PairInfo(
    val hosts: List<String>,
    val port: Int,
    val token: String,
    val fingerprint: String,
    val name: String,
) {
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
