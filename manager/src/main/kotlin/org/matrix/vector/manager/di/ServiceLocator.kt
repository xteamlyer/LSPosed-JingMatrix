package org.matrix.vector.manager.di

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.data.github.GitHubAuth
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.repository.AppRepository
import org.matrix.vector.manager.data.repository.BackupRepository
import org.matrix.vector.manager.data.repository.ModuleInstaller
import org.matrix.vector.manager.data.repository.ModuleRepository
import org.matrix.vector.manager.data.repository.RepoRepository
import org.matrix.vector.manager.data.repository.SettingsRepository
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.ipc.packageEventsFlow
import org.matrix.vector.manager.net.HttpClientFactory

/**
 * Hand-rolled service location, deliberately not a DI framework.
 *
 * The manager normally runs *parasitically*: `ParasiticManagerHooker` injects `manager.apk` into
 * the `com.android.shell` process, so this app's `AndroidManifest.xml` is never installed. Nothing
 * declared in it exists at runtime — no `ContentProvider`, therefore no `androidx.startup`, and no
 * guaranteed custom `Application`. Anything that self-initialises through `InitializationProvider`
 * silently never runs. Everything here is therefore initialised explicitly and lazily.
 *
 * Initialisation order is not fixed either. The daemon may call `Constants.setBinder()` before the
 * activity exists, or the activity may start before any binder arrives. [attach] and [bind] are
 * both idempotent and safe in either order; nothing here throws because the other half has not
 * happened yet.
 */
@SuppressLint("StaticFieldLeak") // Application context; it outlives everything here by design.
object ServiceLocator {

    /** Survives configuration changes, unlike anything scoped to the activity. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var appContext: Context? = null

    private val _service = MutableStateFlow<ILSPManagerService?>(null)

    /**
     * The daemon binder, as observable state.
     *
     * Repositories collect this instead of being poked by a setter, which removes the old bug where
     * the module list was fetched once, before any binder existed, and then never again.
     */
    val service: StateFlow<ILSPManagerService?> = _service.asStateFlow()

    val context: Context
        get() =
            appContext
                ?: error("ServiceLocator.attach() must run before the UI touches the context")

    /** True once the activity has handed us a context. */
    val isAttached: Boolean
        get() = appContext != null

    val daemon: DaemonClient by lazy { DaemonClient(service) }

    val http: OkHttpClient by lazy { HttpClientFactory.create(context, settings) }

    val settings: SettingsRepository by lazy { SettingsRepository(context) }

    val modules: ModuleRepository by lazy { ModuleRepository(daemon, appScope) }

    val apps: AppRepository by lazy { AppRepository(daemon, context.packageManager) }

    val store: RepoRepository by lazy { RepoRepository(http, daemon, appScope) }

    val installer: ModuleInstaller by lazy { ModuleInstaller(context, http) }

    val backup: BackupRepository by lazy { BackupRepository(context, daemon) }

    val githubAuth: GitHubAuth by lazy { GitHubAuth(context, http) }

    val github: GitHubRepository by lazy {
        GitHubRepository(
            client = http,
            cacheDir = context.cacheDir,
            tokenProvider = { githubAuth.token },
            windowMonthsProvider = { settings.activityWindowMonths.value },
        )
    }

    /** Called from the activity. Safe to call repeatedly; later calls are ignored. */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext ?: context
        observePackageChanges()
    }

    /**
     * Invalidates the caches when a package is installed, updated or removed.
     *
     * `packageEventsFlow` existed and had no collectors at all, so every list went stale for the
     * life of the process: a module installed while the manager was open simply never appeared.
     */
    private fun observePackageChanges() {
        appScope.launch {
            context.packageEventsFlow().collect {
                apps.invalidate()
                modules.refresh()
            }
        }
    }

    /** Called from `Constants.setBinder`, possibly before [attach]. */
    fun bind(service: ILSPManagerService?) {
        _service.value = service
    }
}
