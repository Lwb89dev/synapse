@file:OptIn(ExperimentalMaterial3Api::class)

package app.synapse.remote

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

private val AGENTS = listOf("claude", "codex", "kimi", "grok")
private val Mono = FontFamily.Monospace

/** Height of the virtual terminal when "fit to phone" is on; scrollback is shown above it. */
private const val FIT_ROWS = 30

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

// ---------------------------------------------------------------- shared bits

@Composable
private fun stateColor(state: String): Color = when (state) {
    "needs_input" -> MaterialTheme.colorScheme.error
    "working" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

private fun stateGlyph(state: String) = when (state) {
    "needs_input" -> "▲"
    "working" -> "●"
    else -> "○"
}

@Composable
private fun PromptTitle(host: String, command: String) {
    Text(
        buildPrompt(host, command),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
    )
}

@Composable
private fun buildPrompt(host: String, command: String) = androidx.compose.ui.text.buildAnnotatedString {
    val cs = MaterialTheme.colorScheme
    pushStyle(androidx.compose.ui.text.SpanStyle(color = cs.primary, fontWeight = FontWeight.Bold))
    append("synapse@$host")
    pop()
    pushStyle(androidx.compose.ui.text.SpanStyle(color = cs.onSurfaceVariant))
    append(":~$ ")
    pop()
    append(command)
}

// ---------------------------------------------------------------- pairing

@Composable
fun PairScreen(onPair: (PairInfo) -> Unit) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    fun submit(raw: String) {
        val p = PairInfo.parse(raw)
        if (p == null) err = "not a valid synapse:// link" else onPair(p)
    }

    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let(::submit) }
    val scan = {
        launcher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt("Scan the QR printed by `synapse pair`"),
        )
    }

    Scaffold(topBar = { TopAppBar(title = { PromptTitle("phone", "pair") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Text(
                    "$ synapse pair\n▌ scan the QR on your desktop\n\nEnd-to-end encrypted:\nTLS 1.3, certificate pinned from the QR.",
                    Modifier.padding(16.dp).fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = { scan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
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

// ---------------------------------------------------------------- sessions

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
                title = { PromptTitle(pair?.name ?: "host", "ls") },
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
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { showNew = true }) { Text("$ new") } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            error?.let {
                Text(
                    "✗ disconnected: $it", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sessions.isEmpty() && error == null) {
                Text(
                    "no sessions\n\n$ synapse run claude\n  …or tap  $ new",
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { s ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { onOpen(s.id) },
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stateGlyph(s.state), color = stateColor(s.state))
                                Text(
                                    "  ${s.agent}  ${s.id}", Modifier.weight(1f), maxLines = 1,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text("[${s.state.replace('_', ' ')}]", color = stateColor(s.state), style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                s.cwd, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
        title = { Text("$ synapse run") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AGENTS.forEach { a -> FilterChip(selected = a == agent, onClick = { agent = a }, label = { Text(a) }) }
                }
                OutlinedTextField(
                    value = cwd, onValueChange = { cwd = it }, singleLine = true,
                    label = { Text("cwd (optional)") }, placeholder = { Text("~/Documents/vscode/repo") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(agent, cwd.ifBlank { null }) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- terminal

/** label -> tmux key names */
private val EXTRA_KEYS = listOf(
    "Esc" to "Escape", "Tab" to "Tab", "⇧Tab" to "BTab", "^C" to "C-c", "^D" to "C-d",
    "←" to "Left", "↓" to "Down", "↑" to "Up", "→" to "Right", "↵" to "Enter",
    "y" to "y", "n" to "n", "1" to "1", "2" to "2", "3" to "3",
)

@Composable
fun TerminalScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val client = vm.client ?: return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()

    var screen by remember { mutableStateOf(Screen()) }
    var live by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmKill by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var fit by rememberSaveable { mutableStateOf(true) }
    var fontSp by rememberSaveable { mutableIntStateOf(vm.fontSp) }
    var cols by remember { mutableIntStateOf(0) }

    val vscroll = rememberScrollState()
    val hscroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }

    val palette = remember(cs.onSurface, cs.surfaceContainerLowest, dark) {
        AnsiPalette.of(dark, cs.onSurface, cs.surfaceContainerLowest)
    }
    val styled = remember(screen.text, palette) { parseAnsi(screen.text, palette) }
    val termStyle = remember(fontSp) {
        TextStyle(fontFamily = Mono, fontSize = fontSp.sp, lineHeight = (fontSp * 1.25f).sp)
    }
    val charPx = remember(fontSp) { measurer.measure("M".repeat(20), termStyle).size.width / 20f }

    // Live screen: initial snapshot, then SSE frames; reconnects forever.
    LaunchedEffect(id) {
        runCatching { client.screen(id) }.onSuccess { screen = it }
        client.screenStream(id)
            .onEach { live = true; failure = null }
            .retryWhen { _, _ -> live = false; delay(2000); true }
            .collect { screen = it }
    }

    // Make the tmux window exactly as wide as the phone's grid, so TUIs reflow instead of overflowing.
    LaunchedEffect(cols, fit) {
        if (cols < 20) return@LaunchedEffect
        delay(250)
        runCatching { if (fit) client.resize(id, cols, FIT_ROWS) else client.resizeAuto(id) }
    }
    DisposableEffect(id) { onDispose { vm.releaseSize(id) } }

    // Stick to the bottom unless the user scrolled up to read history.
    LaunchedEffect(vscroll.value) { follow = vscroll.value >= vscroll.maxValue - 48 }
    LaunchedEffect(vscroll.maxValue) { if (follow) vscroll.scrollTo(vscroll.maxValue) }

    fun run(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onFailure { failure = it.message } }
    fun sendInput() {
        val t = input
        input = ""
        run { client.input(id, t) }
        follow = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(id, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${if (live) "● live" else "○ reconnecting"} · ${screen.state.replace('_', ' ')}" +
                                if (fit && cols > 0) " · ${cols}×$FIT_ROWS" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!live) cs.error else stateColor(screen.state),
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    TextButton(onClick = { fontSp = (fontSp - 1).coerceAtLeast(7); vm.fontSp = fontSp }) { Text("A−") }
                    TextButton(onClick = { fontSp = (fontSp + 1).coerceAtMost(20); vm.fontSp = fontSp }) { Text("A+") }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Fit to phone") },
                                leadingIcon = { Checkbox(checked = fit, onCheckedChange = null) },
                                onClick = { fit = !fit; menu = false },
                            )
                            DropdownMenuItem(text = { Text("Kill session") }, onClick = { menu = false; confirmKill = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
                    .background(palette.bg, RoundedCornerShape(16.dp)),
            ) {
                val usablePx = with(density) { (maxWidth - 20.dp).toPx() }
                val newCols = (usablePx / charPx).toInt()
                if (newCols != cols) cols = newCols
                Box(
                    Modifier.fillMaxSize().verticalScroll(vscroll).horizontalScroll(hscroll).padding(10.dp),
                ) {
                    Text(styled, style = termStyle, color = palette.fg, softWrap = false)
                }
            }

            failure?.let {
                Text(it, color = cs.error, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EXTRA_KEYS.forEach { (label, key) ->
                    FilledTonalButton(
                        onClick = { run { client.keys(id, listOf(key)) }; follow = true },
                        modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(label, fontFamily = Mono) }
                }
            }

            TextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                prefix = { Text("❯ ", color = cs.primary, fontWeight = FontWeight.Bold) },
                placeholder = { Text("message the agent") },
                trailingIcon = { TextButton(onClick = { sendInput() }, enabled = input.isNotBlank()) { Text("⏎") } },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendInput() }),
            )
        }
    }

    if (confirmKill) AlertDialog(
        onDismissRequest = { confirmKill = false },
        title = { Text("kill $id?") },
        text = { Text("The agent process and its tmux session will be terminated.") },
        confirmButton = { TextButton(onClick = { confirmKill = false; vm.kill(id); onBack() }) { Text("Kill") } },
        dismissButton = { TextButton(onClick = { confirmKill = false }) { Text("Cancel") } },
    )
}
