@file:OptIn(ExperimentalMaterial3Api::class)

package app.synapse.remote

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Mono = FontFamily.Monospace

/** Height of the virtual terminal when "fit to phone" is on; scrollback is shown above it. */
private const val FIT_ROWS = 30

@Composable
fun SynapseApp(
    vm: AppViewModel, requestedSession: String?, onConsumed: () -> Unit, onPaired: () -> Unit,
    pendingPair: PairInfo?, onPendingDecision: (Boolean) -> Unit,
) {
    val pair by vm.pair.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    // Poll the session list only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pair) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(3000)
            }
        }
    }

    if (pendingPair != null) AlertDialog(
        onDismissRequest = { onPendingDecision(false) },
        title = { Text("Pair with ${pendingPair.name}?") },
        text = {
            Text(
                "A link asked this app to connect to:\n${pendingPair.hosts.joinToString("\n") { "$it:${pendingPair.port}" }}\n\n" +
                    "certificate ${pendingPair.fingerprint.take(16)}…\n\n" +
                    "Everything you type will be sent there" +
                    (if (pair != null) " and your current pairing will be replaced" else "") +
                    ". Only accept if you just ran `synapse pair` and the certificate matches.",
            )
        },
        confirmButton = { TextButton(onClick = { onPendingDecision(true) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { onPendingDecision(false) }) { Text("Cancel") } },
    )

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
    val agents by vm.agents.collectAsStateWithLifecycle()
    val via by vm.via.collectAsStateWithLifecycle()
    val tailnet by vm.tailnet.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
            if (pair?.tailnetHost != null && tailnet.state != "running" && tailnet.state != "unavailable") {
                TailnetCard(tailnet, onSignIn = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                })
            }
            via?.let {
                Text(
                    "● ${describeHost(it)}", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                )
            }
            error?.let {
                Column(Modifier.padding(16.dp)) {
                    Text("✗ disconnected: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (pair?.tailnetHost != null) {
                        // away from home the way back is the tailnet: make sure Tailscale is up on this phone
                        val launch = remember { context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn") }
                        Text(
                            "Reaching ${pair?.tailnetHost} needs Tailscale (or another route to your tailnet) on this phone.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (launch != null) TextButton(onClick = { context.startActivity(launch) }) { Text("Open Tailscale") }
                    }
                }
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
        agents = agents,
        onDismiss = { showNew = false },
        onCreate = { agent, cwd ->
            showNew = false
            vm.create(agent, cwd, null) { onOpen(it.id) }
        },
    )
}

@Composable
private fun TailnetCard(t: TailnetStatus, onSignIn: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$ tailscale up", style = MaterialTheme.typography.titleSmall)
            when {
                t.state == "needs_login" && t.authUrl != null -> {
                    Text(
                        "This phone is its own node on your tailnet (no VPN app needed, it works next to Mullvad). Sign in once to add it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { onSignIn(t.authUrl) }) { Text("Sign in") }
                }
                t.state == "error" -> Text("✗ ${t.error ?: "tailnet error"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Text("starting the tailnet node…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NewSessionDialog(agents: List<String>, onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    var agent by remember { mutableStateOf(agents.first()) }
    var cwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$ synapse run") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    agents.forEach { a -> FilterChip(selected = a == agent, onClick = { agent = a }, label = { Text(a) }) }
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
private val KEY_ROWS = listOf(
    listOf("Esc" to "Escape", "Tab" to "Tab", "⇧Tab" to "BTab", "^C" to "C-c", "←" to "Left", "↓" to "Down", "↑" to "Up", "→" to "Right"),
    listOf("↵" to "Enter", "y" to "y", "n" to "n", "1" to "1", "2" to "2", "3" to "3"),
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
    var ended by remember { mutableStateOf(false) }
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
    val styled = remember(screen.text, screen.cursor, palette) { parseAnsi(screen.text, palette, screen.cursor) }
    val termStyle = remember(fontSp) {
        TextStyle(fontFamily = Mono, fontSize = fontSp.sp, lineHeight = (fontSp * 1.25f).sp)
    }
    val charPx = remember(fontSp) { measurer.measure("M".repeat(20), termStyle).size.width / 20f }

    // Live screen: initial snapshot, then SSE frames; reconnects forever.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(id) {
        runCatching { client.screen(id) }.onSuccess { screen = it }
        // Stream only while the screen is visible: no 4 Hz traffic (and battery drain) in the background.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (ended) return@repeatOnLifecycle
            while (true) {
                try {
                    client.screenStream(id).collect { live = true; failure = null; screen = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // network trouble: fall through and retry
                }
                live = false
                // The stream also ends cleanly when the session is gone: tell that apart from a bridge restart.
                val gone = try {
                    client.sessions().none { it.id == id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (gone) { ended = true; break }
                delay(2000)
            }
        }
    }

    // Make the tmux window exactly as wide as the phone's grid, so TUIs reflow instead of overflowing.
    var refit by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { refit++ }
    // Phone in the pocket: give the desktop terminal its size back; fit again when we return.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.releaseSize(id) }
    LaunchedEffect(cols, fit, refit) {
        if (cols < 20) return@LaunchedEffect
        delay(250)
        runCatching { if (fit) client.resize(id, cols, FIT_ROWS) else client.resizeAuto(id) }
    }
    DisposableEffect(id) { onDispose { vm.releaseSize(id) } }

    // Stick to the bottom unless the user scrolled up to read history.
    LaunchedEffect(vscroll.value) { follow = vscroll.value >= vscroll.maxValue - 48 }
    LaunchedEffect(vscroll.maxValue) { if (follow) vscroll.scrollTo(vscroll.maxValue) }

    fun run(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message
        }
    }
    fun sendInput() {
        val t = input
        input = ""
        follow = true
        scope.launch {
            try {
                client.input(id, t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message
                if (input.isBlank()) input = t // don't lose what the user typed
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(id, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        val sc = stateColor(screen.state)
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = if (live) cs.primary else cs.error)) {
                                    append(if (ended) "✗ ended" else if (live) "● live" else "○ offline")
                                }
                                withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(" · ") }
                                withStyle(SpanStyle(color = if (ended) cs.onSurfaceVariant else sc)) {
                                    append(if (ended) "closed" else screen.state.replace('_', ' '))
                                }
                                if (fit && cols > 0) withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(" · $cols×$FIT_ROWS") }
                            },
                            style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Fit to phone") },
                                leadingIcon = { Checkbox(checked = fit, onCheckedChange = null) },
                                onClick = { fit = !fit; menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Font larger  (${fontSp}sp)") },
                                onClick = { fontSp = (fontSp + 1).coerceAtMost(20); vm.fontSp = fontSp },
                            )
                            DropdownMenuItem(
                                text = { Text("Font smaller") },
                                onClick = { fontSp = (fontSp - 1).coerceAtLeast(7); vm.fontSp = fontSp },
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
                LaunchedEffect(newCols) { cols = newCols }
                Box(
                    Modifier.fillMaxSize().verticalScroll(vscroll).horizontalScroll(hscroll).padding(10.dp),
                ) {
                    Text(styled, style = termStyle, color = palette.fg, softWrap = false)
                }
            }

            failure?.let {
                Text(it, color = cs.error, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            }

            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KEY_ROWS.forEach { keys ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        keys.forEach { (label, key) ->
                            FilledTonalButton(
                                onClick = { run { client.keys(id, listOf(key)) }; follow = true },
                                enabled = !ended,
                                modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text(label, fontFamily = Mono, fontSize = 13.sp, maxLines = 1) }
                        }
                    }
                }
            }

            TextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                enabled = !ended,
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
