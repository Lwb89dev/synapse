package app.synapse.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Session requested by tapping a notification. */
    private val requested = mutableStateOf<String?>(null)

    /** A synapse:// link from outside the app. Never applied silently: the user must confirm it. */
    private val pendingPair = mutableStateOf<PairInfo?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { syncService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only for a real launch: a rotation re-delivers the same Intent and must not replay it.
        if (savedInstanceState == null) {
            handle(intent)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SynapseTheme {
                SynapseApp(
                    vm, requested.value, onConsumed = { requested.value = null }, onPaired = ::syncService,
                    pendingPair = pendingPair.value,
                    onPendingDecision = { accept ->
                        val p = pendingPair.value
                        pendingPair.value = null
                        if (accept && p != null) { vm.setPair(p); syncService() }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra(EventsService.EXTRA_SESSION)?.let { requested.value = it }
        // synapse://pair?... opened from a link/QR scanner app
        val link = i.data?.toString()
        val store = Store(this)
        if (link != null && link != store.lastLink) {
            store.lastLink = link // ask once per distinct link, even if the task is restarted with the same intent
            PairInfo.parse(link)?.let { pendingPair.value = it }
        }
        i.data = null // consumed
    }

    private fun syncService() {
        if (vm.pair.value != null) EventsService.start(this) else EventsService.stop(this)
    }

    override fun onStart() {
        super.onStart()
        syncService()
    }
}
