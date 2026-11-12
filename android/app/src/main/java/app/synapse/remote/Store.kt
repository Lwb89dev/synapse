package app.synapse.remote

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/** Keeps the pairing (token included) AES-GCM encrypted with an Android Keystore key. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("synapse", Context.MODE_PRIVATE)

    init {
        // older builds kept the whole pairing link (it carries the token) in clear text: scrub it
        if (prefs.contains("lastLink")) prefs.edit().remove("lastLink").apply()
    }

    var fontSp: Int
        get() = prefs.getInt("fontSp", 11)
        set(v) = prefs.edit().putInt("fontSp", v.coerceIn(7, 20)).apply()

    /**
     * True the first time a given synapse:// link is seen (Android re-delivers a task's original intent on restart).
     * Only a SHA-256 of the link is stored: the link carries the access token and must never sit in clear text.
     */
    fun isNewLink(link: String): Boolean {
        val h = MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }
        if (prefs.getString("lastLinkHash", null) == h) return false
        prefs.edit().putString("lastLinkHash", h).remove("lastLink").apply() // also drops the clear-text value of older builds
        return true
    }

    /** Keep terminal content out of screenshots, screen recordings and the recents thumbnail (FLAG_SECURE). */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secureScreen", true)
        set(v) = prefs.edit().putBoolean("secureScreen", v).apply()

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
