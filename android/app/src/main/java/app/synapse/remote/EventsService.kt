package app.synapse.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Holds an SSE connection to /api/events and raises a notification when an
 * agent starts waiting for the user ("attention" events).
 */
class EventsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var activePair: PairInfo? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val ongoing = NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_synapse)
            .setContentTitle("Synapse")
            .setContentText("Listening to your agents")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, 1, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        val pair = Store(this).load()
        if (pair == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        Tailnet.ensure(this, pair) // the node must live as long as this service does
        // Every Activity start calls us again: keep the live stream instead of dropping events in a reconnect gap.
        if (pair == activePair && job?.isActive == true) return START_STICKY
        activePair = pair
        job?.cancel()
        val client = SynapseClient(pair)
        val store = Store(this)
        job = scope.launch {
            var last = store.lastEventId.takeIf { it > 0 }
            while (true) {
                // On reconnect ask for everything after the last event we saw: alerts that fired while we were offline.
                client.events(last)
                    .catch { /* fall through to retry */ }
                    .collect { ev ->
                        last = ev.id
                        store.lastEventId = ev.id
                        if (relevant(ev, client)) notify(ev)
                    }
                delay(3000)
            }
        }
        return START_STICKY
    }

    /** Replayed events (age > 20 s) are only worth a notification if they still matter. */
    private suspend fun relevant(ev: BridgeEvent, client: SynapseClient): Boolean {
        if (ev.type != "attention" && ev.type != "done") return false
        if (ev.age <= 20) return true
        if (ev.age > 900) return false // stale
        if (ev.type == "done") return true
        // an old attention is moot if the agent has been answered since
        val s = runCatching { client.sessions() }.getOrNull()?.firstOrNull { it.id == ev.session }
        return s?.state == "needs_input"
    }

    private fun notify(ev: BridgeEvent) {
        val urgent = ev.type == "attention"
        val open = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SESSION, ev.session)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, ev.session.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = ev.agent.ifBlank { "Agent" }
        val n: Notification = NotificationCompat.Builder(this, if (urgent) CH_ALERT else CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_synapse)
            .setContentTitle(if (urgent) "$who needs you" else "$who is done")
            .setContentText(ev.message.ifBlank { ev.session })
            .setStyle(NotificationCompat.BigTextStyle().bigText(ev.message.ifBlank { ev.session }))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(ev.session.hashCode(), n)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Agent needs input", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION = "session"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val CH_DONE = "done"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EventsService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EventsService::class.java))
        }
    }
}
