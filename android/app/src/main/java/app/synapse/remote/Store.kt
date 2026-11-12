package app.synapse.remote

import android.content.Context
import android.net.Uri

/** Keeps the pairing (token included) AES-GCM encrypted with an Android Keystore key. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("synapse", Context.MODE_PRIVATE)

    var fontSp: Int
        get() = prefs.getInt("fontSp", 11)
        set(v) = prefs.edit().putInt("fontSp", v.coerceIn(7, 20)).apply()

    /** The last synapse:// link already shown to the user: Android re-delivers a task's original intent on restart. */
    var lastLink: String?
        get() = prefs.getString("lastLink", null)
        set(v) = prefs.edit().putString("lastLink", v).apply()

    /** Last bridge event this phone has seen, so a reconnect can ask for what it missed. */
    var lastEventId: Long
        get() = prefs.getLong("lastEventId", 0L)
        set(v) = prefs.edit().putLong("lastEventId", v).apply()

    fun load(): PairInfo? {
        val raw = prefs.getString("pair", null) ?: return null
        val legacy = raw.startsWith("synapse://") // written by an older build in clear text
        val parsed = (if (legacy) raw else SecretBox.decrypt(raw))?.let(PairInfo::parse)
        if (parsed != null && legacy) save(parsed) // migrate to the encrypted form
        return parsed
    }

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
        prefs.edit().putString("pair", SecretBox.encrypt(uri)).apply()
    }
}
