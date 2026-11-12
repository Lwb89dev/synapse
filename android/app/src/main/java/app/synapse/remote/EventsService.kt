package app.synapse.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
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
    private var activeClient: SynapseClient? = null

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
        if (intent?.action == ACTION_KEYS) {
            runQuickAction(activeClient ?: SynapseClient(pair).also { activeClient = it }, intent)
        }
        // Every Activity start calls us again: keep the live stream instead of dropping events in a reconnect gap.
        if (pair == activePair && job?.isActive == true) return START_STICKY
        activePair = pair
        watchNetwork()
        startStream(pair)
        return START_STICKY
    }

    private var lastStart = 0L
    private var watchdog: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** (Re)opens the event stream, resuming after the last event seen. Safe to call from any thread. */
    @Synchronized
    private fun startStream(pair: PairInfo) {
        job?.cancel()
        lastStart = System.currentTimeMillis()
        val client = SynapseClient(pair).also { activeClient = it }
        val store = Store(this)
        watchdog?.cancel()
        watchdog = scope.launch { watchTailnet(client, pair) }
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
    }

    /**
     * Self-healing: if the desktop has been unreachable for a long time (no stream open, no call succeeding) while
     * the embedded tailnet node claims to be fine, bounce the node to force fresh path discovery. Rate limited.
     */
    private suspend fun watchTailnet(client: SynapseClient, pair: PairInfo) {
        if (pair.tailnetHost == null) return
        var lastBounce = 0L
        while (true) {
            delay(30_000)
            val now = System.currentTimeMillis()
            val stuck = client.streamsOpen == 0 && now - client.lastHealthyAt > STUCK_AFTER_MS
            if (stuck && now - lastBounce > BOUNCE_EVERY_MS) {
                Log.w("Synapse", "desktop unreachable for ${(now - client.lastHealthyAt) / 1000}s: restarting the tailnet node")
                lastBounce = now
                Tailnet.stop()
                Tailnet.ensure(this, pair)
            }
        }
    }

    /**
     * A new default network (Wi-Fi <-> mobile, back from Doze, VPN up/down) makes the old connection useless:
     * reconnect now instead of waiting for the read timeout.
     */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val pair = activePair ?: return
                if (System.currentTimeMillis() - lastStart < 4000) return // the initial callback right after a start
                startStream(pair)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
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
        val nid = ev.session.hashCode()
        val open = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SESSION, ev.session)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, nid, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = ev.agent.ifBlank { "Agent" }
        val builder = NotificationCompat.Builder(this, if (urgent) CH_ALERT else CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_synapse)
            .setContentTitle(if (urgent) "$who needs you" else "$who is done")
            .setContentText(ev.message.ifBlank { ev.session })
            .setStyle(NotificationCompat.BigTextStyle().bigText(ev.message.ifBlank { ev.session }))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (urgent) {
            // one-tap answers chosen by the bridge for the prompt on screen; each carries the event id so a late tap
            // on a prompt that has since changed is refused by the bridge instead of answering the wrong question
            ev.actions.filter { it.keys.isNotEmpty() }.take(2).forEachIndexed { i, a ->
                val act = Intent(this, EventsService::class.java).setAction(ACTION_KEYS)
                    .putExtra(EXTRA_SESSION, ev.session)
                    .putExtra(EXTRA_KEYS, a.keys.toTypedArray())
                    .putExtra(EXTRA_ATTENTION, ev.id)
                    .putExtra(EXTRA_NID, nid)
                    .putExtra(EXTRA_LABEL, a.label)
                val api = PendingIntent.getForegroundService(
                    this, nid * 31 + i + 1, act,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, a.label, api)
            }
        }
        getSystemService(NotificationManager::class.java).notify(nid, builder.build())
    }

    /** Runs a notification action: types the keys, then clears the notification or says why nothing was sent. */
    private fun runQuickAction(client: SynapseClient, intent: Intent) {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val keys = intent.getStringArrayExtra(EXTRA_KEYS)?.toList() ?: return
        val attention = intent.getLongExtra(EXTRA_ATTENTION, 0L)
        val nid = intent.getIntExtra(EXTRA_NID, session.hashCode())
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "answer"
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            try {
                client.keys(session, keys, attention.takeIf { it > 0 })
                nm.cancel(nid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                showResult(nid, session, if (e.code == 409) "Already handled, nothing was sent." else "Couldn't send \"$label\": ${e.message}")
            } catch (e: Exception) {
                showResult(nid, session, "Couldn't reach the desktop: ${e.message}")
            }
        }
    }

    private fun showResult(nid: Int, session: String, text: String) {
        val open = Intent(this, MainActivity::class.java).putExtra(EXTRA_SESSION, session)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_synapse)
            .setContentTitle(session)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, nid, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(20_000)
            .build()
        getSystemService(NotificationManager::class.java).notify(nid, n)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Agent needs input", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onDestroy() {
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val STUCK_AFTER_MS = 90_000L
        private const val BOUNCE_EVERY_MS = 180_000L
        const val EXTRA_SESSION = "session"
        const val ACTION_KEYS = "app.synapse.remote.KEYS"
        const val EXTRA_KEYS = "keys"
        const val EXTRA_ATTENTION = "attention"
        const val EXTRA_NID = "nid"
        const val EXTRA_LABEL = "label"
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
