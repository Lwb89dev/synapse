package app.synapse.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val _pair = MutableStateFlow(store.load())
    val pair: StateFlow<PairInfo?> = _pair

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var client: SynapseClient? = _pair.value?.let(::SynapseClient)
        private set

    init {
        _pair.value?.let { Tailnet.ensure(app, it) }
    }

    private val _agents = MutableStateFlow(listOf("claude", "codex", "kimi", "grok"))
    val agents: StateFlow<List<String>> = _agents

    fun setPair(p: PairInfo?) {
        store.save(p)
        _pair.value = p
        client = p?.let(::SynapseClient)
        // keep the embedded node only while the pairing actually uses the tailnet (saves battery)
        if (p?.tailnetHost != null) Tailnet.ensure(getApplication(), p) else Tailnet.stop()
        agentsLoaded = false
        _sessions.value = emptyList()
        _error.value = null
        if (p != null) viewModelScope.launch { refresh() }
    }

    private val _via = MutableStateFlow<String?>(null)
    /** Which pairing host answered (LAN / Tailscale / USB), for the status line. */
    val via: StateFlow<String?> = _via

    private val _tailnet = MutableStateFlow(TailnetStatus())
    val tailnet: StateFlow<TailnetStatus> = _tailnet

    private var agentsLoaded = false

    /** Called by the UI only while it is visible (see SynapseApp): no polling in the background. */
    suspend fun refresh() {
        val c = client ?: return
        if (pair.value?.tailnetHost != null) _tailnet.value = Tailnet.status()
        runCatching { c.sessions() }
            .onSuccess {
                if (c === client) { _sessions.value = it; _error.value = null; _via.value = c.activeHost }
            }
            .onFailure { if (c === client) { _error.value = it.message ?: it.javaClass.simpleName; _via.value = null } }
        if (!agentsLoaded && _error.value == null) {
            runCatching { c.agents() }.onSuccess { if (it.isNotEmpty() && c === client) { _agents.value = it; agentsLoaded = true } }
        }
    }

    fun create(agent: String, cwd: String?, name: String?, onDone: (Session) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.create(agent, cwd, name) }
                .onSuccess { refresh(); onDone(it) }
                .onFailure { _error.value = it.message }
        }
    }

    var secureScreen: Boolean
        get() = store.secureScreen
        set(v) { store.secureScreen = v }

    var fontSp: Int
        get() = store.fontSp
        set(v) { store.fontSp = v }

    /** Terminal left the screen: let the desktop terminal have its size back. */
    fun releaseSize(id: String) {
        val c = client ?: return
        viewModelScope.launch { runCatching { c.resizeAuto(id) } }
    }

    fun kill(id: String) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.kill(id) }.onFailure { _error.value = it.message }
            refresh()
        }
    }
}
