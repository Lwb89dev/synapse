@file:OptIn(ExperimentalMaterial3Api::class)

package app.synapse.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

private val AGENTS = listOf("claude", "codex", "kimi", "grok")

@Composable
fun SynapseApp(vm: AppViewModel, requestedSession: String?, onConsumed: () -> Unit, onPaired: () -> Unit) {
    val pair by vm.pair.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(requestedSession) {
        if (requestedSession != null) {
            open = requestedSession
            onConsumed()
        }
    }

    when {
        pair == null -> PairScreen(onPair = { vm.setPair(it); onPaired() })
        open != null -> {
            BackHandler { open = null }
            TerminalScreen(vm, open!!, onBack = { open = null })
        }
        else -> SessionsScreen(vm, onOpen = { open = it })
    }
}

// ---------------------------------------------------------------- pairing

@Composable
fun PairScreen(onPair: (PairInfo) -> Unit) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    fun submit(raw: String) {
        val p = PairInfo.parse(raw)
        if (p == null) err = "Not a valid synapse:// link" else onPair(p)
    }

    val scanner = rememberLauncherForScan { contents -> submit(contents) }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair with your desktop") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("On the desktop run:  synapse pair", style = MaterialTheme.typography.bodyLarge)
            Text("then scan the QR code it prints.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = scanner, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
            Text("…or paste the synapse:// link", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = text, onValueChange = { text = it; err = null },
                modifier = Modifier.fillMaxWidth(), isError = err != null,
                supportingText = { err?.let { Text(it) } },
            )
            OutlinedButton(onClick = { submit(text) }, enabled = text.isNotBlank()) { Text("Pair") }
        }
    }
}

@Composable
private fun rememberLauncherForScan(onResult: (String) -> Unit): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let(onResult)
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setPrompt("Scan the QR printed by `synapse pair`"),
        )
    }
}

// ---------------------------------------------------------------- sessions

@Composable
fun stateColor(state: String): Color = when (state) {
    "needs_input" -> Color(0xFFE5484D)
    "working" -> Color(0xFF3E9B4F)
    else -> MaterialTheme.colorScheme.outline
}

@Composable
fun SessionsScreen(vm: AppViewModel, onOpen: (String) -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val pair by vm.pair.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pair?.name ?: "Synapse") },
                actions = {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Unpair") }, onClick = { menu = false; vm.setPair(null) })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showNew = true }) { Text("New session") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            error?.let {
                Text(
                    "Disconnected: $it", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sessions.isEmpty() && error == null) {
                Text(
                    "No sessions. Start one here, or from the desktop with  synapse run claude",
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(s.id) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor(s.state)))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("${s.agent} · ${s.id}", style = MaterialTheme.typography.titleMedium)
                                Text(s.cwd, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            Text(s.state.replace('_', ' '), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showNew) NewSessionDialog(
        onDismiss = { showNew = false },
        onCreate = { agent, cwd ->
            showNew = false
            vm.create(agent, cwd, null) { onOpen(it.id) }
        },
    )
}

@Composable
private fun NewSessionDialog(onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    var agent by remember { mutableStateOf(AGENTS.first()) }
    var cwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AGENTS.forEach { a ->
                        FilterChip(selected = a == agent, onClick = { agent = a }, label = { Text(a) })
                    }
                }
                OutlinedTextField(
                    value = cwd, onValueChange = { cwd = it }, singleLine = true,
                    label = { Text("Working directory (optional)") }, placeholder = { Text("~/Documents/vscode/myrepo") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(agent, cwd.ifBlank { null }) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- terminal

/** label -> tmux key names */
private val QUICK_KEYS = listOf(
    "↵" to listOf("Enter"),
    "Esc" to listOf("Escape"),
    "↑" to listOf("Up"),
    "↓" to listOf("Down"),
    "Tab" to listOf("Tab"),
    "⇧Tab" to listOf("BTab"),
    "^C" to listOf("C-c"),
    "1" to listOf("1"),
    "2" to listOf("2"),
    "3" to listOf("3"),
    "y" to listOf("y"),
    "n" to listOf("n"),
)

@Composable
fun TerminalScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val client = vm.client ?: return
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen()) }
    var input by rememberSaveable { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmKill by remember { mutableStateOf(false) }
    val vscroll = rememberScrollState()

    LaunchedEffect(id) {
        runCatching { client.screen(id) }.onSuccess { screen = it }
        client.screenStream(id)
            .retryWhen { _, _ -> delay(2000); true }
            .collect { screen = it; failure = null }
    }
    LaunchedEffect(screen.text) { vscroll.scrollTo(vscroll.maxValue) }

    fun run(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { failure = it.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(screen.state)))
                        Text(id, Modifier.padding(start = 8.dp))
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { TextButton(onClick = { confirmKill = true }) { Text("Kill") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111111))
                    .verticalScroll(vscroll).horizontalScroll(rememberScrollState()).padding(8.dp),
            ) {
                Text(
                    screen.text, color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, lineHeight = 13.sp, softWrap = false,
                )
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_KEYS.forEach { (label, keys) ->
                    OutlinedButton(onClick = { run { client.keys(id, keys) } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text(label) }
                }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message to the agent") }, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val t = input; input = ""; run { client.input(id, t) }
                    }),
                )
                Button(
                    onClick = { val t = input; input = ""; run { client.input(id, t) } },
                    enabled = input.isNotBlank(), modifier = Modifier.padding(start = 8.dp),
                ) { Text("Send") }
            }
        }
    }

    if (confirmKill) AlertDialog(
        onDismissRequest = { confirmKill = false },
        title = { Text("Kill session $id?") },
        text = { Text("The agent process and its tmux session will be terminated.") },
        confirmButton = { TextButton(onClick = { confirmKill = false; vm.kill(id); onBack() }) { Text("Kill") } },
        dismissButton = { TextButton(onClick = { confirmKill = false }) { Text("Cancel") } },
    )
}
