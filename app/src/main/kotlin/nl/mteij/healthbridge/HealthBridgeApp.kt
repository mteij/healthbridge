package nl.mteij.healthbridge

import android.app.Application
import nl.mteij.healthbridge.sync.SyncScheduler

class HealthBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        if (Graph.prefs.isLoggedIn) SyncScheduler.schedule(this, Graph.prefs)
    }
}
