package nl.mteij.healthbridge

import android.app.Application
import nl.mteij.healthbridge.data.Api
import nl.mteij.healthbridge.data.Prefs
import nl.mteij.healthbridge.data.SyncLog
import nl.mteij.healthbridge.sync.SyncEngine

/** Process-wide singletons; small enough that a DI framework would only add weight. */
object Graph {
    lateinit var prefs: Prefs
        private set
    lateinit var log: SyncLog
        private set
    lateinit var api: Api
        private set
    lateinit var engine: SyncEngine
        private set

    fun init(app: Application) {
        prefs = Prefs(app)
        log = SyncLog(app)
        api = Api(prefs)
        engine = SyncEngine(app, prefs, api, log)
    }
}
