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
        /** synapse://pair?hosts=a,b&port=7433&token=...&fp=<sha256 hex>&name=<host> */
        fun parse(raw: String): PairInfo? = runCatching {
            val u = Uri.parse(raw.trim())
            if (u.scheme != "synapse") return null
            val hosts = u.getQueryParameter("hosts")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            val token = u.getQueryParameter("token") ?: return null
            val fp = u.getQueryParameter("fp") ?: return null
            if (hosts.isEmpty()) return null
            PairInfo(
                hosts = hosts,
                port = u.getQueryParameter("port")?.toIntOrNull() ?: 7433,
                token = token,
                fingerprint = fp.replace(":", "").lowercase(),
                name = u.getQueryParameter("name") ?: hosts.first(),
            )
        }.getOrNull()
    }
}
