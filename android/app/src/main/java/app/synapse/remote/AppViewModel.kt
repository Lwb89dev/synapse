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

    private val _agents = MutableStateFlow(listOf("claude", "codex", "kimi", "grok"))
    val agents: StateFlow<List<String>> = _agents

    fun setPair(p: PairInfo?) {
        store.save(p)
        _pair.value = p
        client = p?.let(::SynapseClient)
        agentsLoaded = false
        _sessions.value = emptyList()
        _error.value = null
        if (p != null) viewModelScope.launch { refresh() }
    }

    private var agentsLoaded = false

    /** Called by the UI only while it is visible (see SynapseApp): no polling in the background. */
    suspend fun refresh() {
        val c = client ?: return
        runCatching { c.sessions() }
            .onSuccess {
                if (c === client) { _sessions.value = it; _error.value = null }
            }
            .onFailure { if (c === client) _error.value = it.message ?: it.javaClass.simpleName }
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
