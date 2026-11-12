package app.synapse.remote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Session requested by tapping a notification. */
    private val requested = mutableStateOf<String?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { syncService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                SynapseApp(vm, requested.value, onConsumed = { requested.value = null }, onPaired = ::syncService)
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
        i.data?.toString()?.let(PairInfo::parse)?.let { vm.setPair(it); syncService() }
    }

    private fun syncService() {
        if (vm.pair.value != null) EventsService.start(this) else EventsService.stop(this)
    }

    override fun onStart() {
        super.onStart()
        syncService()
    }
}
