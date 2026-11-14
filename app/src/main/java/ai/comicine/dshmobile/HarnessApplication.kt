package ai.comicine.dshmobile

import android.app.Application
import ai.comicine.dshmobile.data.HarnessDatabase
import ai.comicine.dshmobile.data.HarnessRepository
import ai.comicine.dshmobile.engine.HarnessEngine
import ai.comicine.dshmobile.plugins.PluginManager
import ai.comicine.dshmobile.security.CredentialStore

class HarnessApplication : Application() {
    lateinit var database: HarnessDatabase
        private set
    lateinit var repository: HarnessRepository
        private set
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var engine: HarnessEngine
        private set
    lateinit var pluginManager: PluginManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = HarnessDatabase.open(this)
        repository = HarnessRepository(database)
        credentialStore = CredentialStore(this)
        pluginManager = PluginManager(this, database.plugins())
        engine = HarnessEngine(this, repository, credentialStore, pluginManager)
    }

    override fun onTerminate() {
        engine.cancelAll()
        pluginManager.close()
        database.close()
        super.onTerminate()
    }
}
