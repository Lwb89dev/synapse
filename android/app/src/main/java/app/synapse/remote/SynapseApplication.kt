package app.synapse.remote

import android.app.Application

class SynapseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Bring the embedded tailnet node up early when the saved pairing uses one.
        Store(this).load()?.let { Tailnet.ensure(this, it) }
    }
}
