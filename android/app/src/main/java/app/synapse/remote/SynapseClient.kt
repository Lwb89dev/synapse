package app.synapse.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.TlsVersion
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * The bridge uses a self-signed certificate. Instead of a CA we trust exactly one
 * thing: the SHA-256 of the leaf certificate that came through the pairing QR.
 */
private class PinnedTrust(private val fingerprint: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("client certs not supported")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
        val hex = digest.joinToString("") { "%02x".format(it) }
        if (!hex.equals(fingerprint, ignoreCase = true)) throw CertificateException("certificate pin mismatch")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class SynapseClient(private val pair: PairInfo) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private val http: OkHttpClient = run {
        val trust = PinnedTrust(pair.fingerprint)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier { _, _ -> true } // identity is the pinned fingerprint, not the hostname
            // TLS 1.3 only: forward secrecy, no downgrade. The bridge refuses anything older too.
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    // The bridge sends a keep-alive every 15 s, so 30 s of silence means the link is dead (Wi-Fi switch, sleep...).
    private val streamHttp = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    // Keys and text must reach tmux in the order the user tapped them: never two input calls in flight.
    private val inputLock = Mutex()

    @Volatile private var base: String? = null

    private fun req(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${pair.token}")

    private fun hostPart(h: String) = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h

    /** Tries each host from the QR (LAN, VPN, ...) until one answers; the winner is cached. */
    private suspend fun resolve(): String = withContext(Dispatchers.IO) {
        base ?: run {
            var last: Exception? = null
            for (h in pair.hosts) {
                val url = "https://${hostPart(h)}:${pair.port}"
                try {
                    http.newCall(req("$url/api/health").build()).execute().use {
                        if (it.isSuccessful) {
                            base = url
                            return@run url
                        }
                        last = ApiException(it.code, "HTTP ${it.code}")
                    }
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("no host to try")
        }
    }

    private suspend fun <T> call(method: String, path: String, body: String? = null, parse: (String) -> T): T {
        val b = resolve()
        return withContext(Dispatchers.IO) {
            val rb = when {
                body != null -> body.toRequestBody(jsonType)
                method == "POST" -> "{}".toRequestBody(jsonType)
                else -> null
            }
            try {
                http.newCall(req("$b$path").method(method, rb).build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}: ${text.take(200)}")
                    parse(text)
                }
            } catch (e: IOException) {
                base = null // network moved under us: re-resolve next time
                throw e
            }
        }
    }

    suspend fun sessions(): List<Session> =
        call("GET", "/api/sessions") { json.decodeFromString<SessionList>(it).sessions }

    suspend fun create(agent: String, cwd: String?, name: String?): Session {
        val body = buildJsonObject {
            put("agent", agent)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
            if (!name.isNullOrBlank()) put("name", name)
        }.toString()
        return call("POST", "/api/sessions", body) { json.decodeFromString<Session>(it) }
    }

    suspend fun screen(id: String): Screen =
        call("GET", "/api/sessions/$id/screen") { json.decodeFromString<Screen>(it) }

    suspend fun agents(): List<String> = call("GET", "/api/health") {
        json.parseToJsonElement(it).jsonObject["agents"]?.jsonArray?.map { a -> a.jsonPrimitive.content }.orEmpty()
    }

    suspend fun input(id: String, text: String, submit: Boolean = true) {
        val body = buildJsonObject { put("text", text); put("submit", submit) }.toString()
        inputLock.withLock { call("POST", "/api/sessions/$id/input", body) { } }
    }

    suspend fun keys(id: String, keys: List<String>) {
        val body = buildJsonObject { put("keys", JsonArray(keys.map(::JsonPrimitive))) }.toString()
        inputLock.withLock { call("POST", "/api/sessions/$id/keys", body) { } }
    }

    /** Fit the tmux window to the phone's terminal grid. */
    suspend fun resize(id: String, cols: Int, rows: Int) {
        val body = buildJsonObject { put("cols", cols); put("rows", rows) }.toString()
        inputLock.withLock { call("POST", "/api/sessions/$id/resize", body) { } }
    }

    /** Give the window size back to the desktop terminal. */
    suspend fun resizeAuto(id: String) = inputLock.withLock { call("POST", "/api/sessions/$id/resize", "{}") { } }

    suspend fun kill(id: String) = call("DELETE", "/api/sessions/$id") { }

    fun screenStream(id: String): Flow<Screen> =
        sse("/api/sessions/$id/stream", Channel.CONFLATED).map { json.decodeFromString<Screen>(it.second) }

    fun events(): Flow<BridgeEvent> =
        sse("/api/events", Channel.UNLIMITED).map { json.decodeFromString<BridgeEvent>(it.second) }

    /** [capacity]: CONFLATED keeps only the newest frame (screens), UNLIMITED never drops (events). */
    private fun sse(path: String, capacity: Int): Flow<Pair<String, String>> = callbackFlow {
        val b = resolve()
        val request = req("$b$path").header("Accept", "text/event-stream").build()
        val source = EventSources.createFactory(streamHttp).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend((type ?: "message") to data)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                base = null
                close(t ?: IOException("stream closed (HTTP ${response?.code})"))
            }
        })
        awaitClose { source.cancel() }
    }.buffer(capacity)
}
