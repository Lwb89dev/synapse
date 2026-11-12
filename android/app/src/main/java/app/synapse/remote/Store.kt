package app.synapse.remote

import android.content.Context
import android.net.Uri

/** Keeps the pairing in app-private SharedPreferences. TODO: move the token behind the Android Keystore. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("synapse", Context.MODE_PRIVATE)

    fun load(): PairInfo? = prefs.getString("pair", null)?.let(PairInfo::parse)

    fun save(p: PairInfo?) {
        if (p == null) {
            prefs.edit().remove("pair").apply()
            return
        }
        val uri = Uri.Builder().scheme("synapse").authority("pair")
            .appendQueryParameter("hosts", p.hosts.joinToString(","))
            .appendQueryParameter("port", p.port.toString())
            .appendQueryParameter("token", p.token)
            .appendQueryParameter("fp", p.fingerprint)
            .appendQueryParameter("name", p.name)
            .build().toString()
        prefs.edit().putString("pair", uri).apply()
    }
}
