package app.synapse.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(3000)
            }
        }
    }

    fun setPair(p: PairInfo?) {
        store.save(p)
        _pair.value = p
        client = p?.let(::SynapseClient)
        _sessions.value = emptyList()
        _error.value = null
        if (p != null) viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val c = client ?: return
        runCatching { c.sessions() }
            .onSuccess { _sessions.value = it; _error.value = null }
            .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
    }

    fun create(agent: String, cwd: String?, name: String?, onDone: (Session) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.create(agent, cwd, name) }
                .onSuccess { refresh(); onDone(it) }
                .onFailure { _error.value = it.message }
        }
    }

    fun kill(id: String) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.kill(id) }.onFailure { _error.value = it.message }
            refresh()
        }
    }
}
