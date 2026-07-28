package org.matrix.vector.manager.ui.screens.modules

import android.util.Log
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.UserInfo
import org.matrix.vector.manager.data.model.InstalledModule
import org.matrix.vector.manager.data.model.MATCH_ANY_USER
import org.matrix.vector.manager.data.model.ModuleDetection
import org.matrix.vector.manager.data.model.PER_USER_RANGE
import org.matrix.vector.manager.data.repository.ModuleRepository
import org.matrix.vector.manager.data.model.StoreEntry
import org.matrix.vector.manager.data.repository.ModuleUpdateQueue
import org.matrix.vector.manager.data.model.XposedApi
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient

/** One tab: a user, and the modules installed for them. */
data class UserModulesState(val user: UserInfo, val modules: List<InstalledModule>)

/** What the list is showing. Answering "what is running" should not need a scroll. */
enum class ModuleFilter {
    All,
    Active,
    Inactive,
}

/**
 * How far a module reaches, and whether it can run at all.
 *
 * The scope count is the useful part: it lets the list answer "what does this module actually
 * touch" without opening it, which is the question behind most visits to the scope editor.
 */
/** Identifies one module row: a package can be installed for several users at once. */
data class ModuleKey(val packageName: String, val userId: Int)

data class ModuleFacts(
    val scopeCount: Int = -1,
    val incompatible: Boolean = false,
    /**
     * The libxposed version that broke what this module was built against, if one has.
     *
     * Distinct from [incompatible], which is "the framework does not go that high". This is the
     * opposite direction: the module is *behind* a break, so the framework can load it and it may
     * still misbehave. The number is kept rather than a flag because naming the version is the
     * explanation.
     */
    val apiBrokenSince: Int? = null,
    /**
     * The first few apps in the module's scope, for the row's preview.
     *
     * A count tells you the *size* of a scope; the icons tell you the scope. "5 apps" answers a
     * question nobody asked, where three recognisable icons answer "does this touch anything I
     * care about" at a glance.
     */
    val scopePreview: List<android.content.pm.ApplicationInfo> = emptyList(),
    /** Whether the module hooks the system framework, which has no icon of its own to show. */
    val scopeFramework: Boolean = false,
)

/** How the list is ordered. Only [EnabledFirst] groups into sections; the others are flat. */
enum class ModuleSort {
    EnabledFirst,
    Name,
    RecentlyUpdated,
    WidestScope,
}

/** How many scoped apps a row previews before collapsing the rest into a count. */
const val SCOPE_PREVIEW_LIMIT = 3

/** The framework itself, which the daemon names in a scope like any package but which is not one. */
private const val SYSTEM_FRAMEWORK = "system"
private const val TAG = "VectorModules"

class ModulesViewModel(
    private val daemonClient: DaemonClient,
    private val moduleRepository: ModuleRepository,
    private val packageManager: PackageManager,
) : ViewModel() {

    private val _discovered = MutableStateFlow<List<UserModulesState>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Whether the daemon answered at all.
     *
     * An empty list means two completely different things — "you have no modules" and "the
     * framework is not running" — and showing the first when the second is true is a lie the
     * previous build told. The UI branches on this.
     */
    private val _daemonAvailable = MutableStateFlow(true)
    val daemonAvailable: StateFlow<Boolean> = _daemonAvailable.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(ModuleFilter.All)
    val filter: StateFlow<ModuleFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(ModuleSort.EnabledFirst)
    val sort: StateFlow<ModuleSort> = _sort.asStateFlow()

    /** Keyed by package name. Filled in after the list appears, so it never delays first paint. */
    /**
     * Per module *per user*, not per package.
     *
     * A module installed in both the owner's space and a private space is two rows with two scopes,
     * and keying this by package alone meant the second row was handed the first one's facts —
     * so the private space tab depicted the owner's apps. Filtering the scope by user, which is
     * also necessary, could not fix that on its own: the entry being filtered already belonged to
     * the wrong copy of the module.
     */
    private val _facts = MutableStateFlow<Map<ModuleKey, ModuleFacts>>(emptyMap())
    val facts: StateFlow<Map<ModuleKey, ModuleFacts>> = _facts.asStateFlow()

    /**
     * Which of the installed modules the catalogue has something newer for, minus the muted ones.
     *
     * The catalogue is the Store's, not a second fetch: this is the same set the Store counts in
     * its header, so the mark on a row and the number on the tab cannot disagree.
     */
    val upgradable: StateFlow<Set<String>> = ServiceLocator.upgradablePackages

    val mutedUpgradable: StateFlow<Set<String>> = ServiceLocator.mutedUpgradablePackages

    val storeEntries: StateFlow<Map<String, StoreEntry>> = ServiceLocator.storeEntries

    val updateChannel: StateFlow<String> = ServiceLocator.settings.updateChannel

    val updateQueue: StateFlow<ModuleUpdateQueue.State> = ServiceLocator.moduleUpdates.state

    fun startUpdates(items: List<ModuleUpdateQueue.Item>) =
        ServiceLocator.moduleUpdates.start(items)

    fun acknowledgeUpdates() = ServiceLocator.moduleUpdates.acknowledge()

    private val _frameworkApi = MutableStateFlow(0)

    /** "8 of 14 active", without needing the filtered list. */
    val counts: StateFlow<Pair<Int, Int>> =
        combine(_discovered, moduleRepository.enabledModulesState) { tabs, enabled ->
                val all = tabs.flatMap { it.modules }
                all.count { it.packageName in enabled } to all.size
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0 to 0)

    /**
     * Set when the daemon rejects a toggle, so the UI can say so. Previously a rejected write just
     * snapped the switch back with no explanation.
     */
    private val _toggleFailed = MutableStateFlow<String?>(null)
    val toggleFailed: StateFlow<String?> = _toggleFailed.asStateFlow()

    /**
     * The enabled set is owned by [ModuleRepository] and merged in here rather than baked into the
     * discovered list, so enabling a module updates one flow and every screen showing it agrees.
     */
    val userModulesTabs: StateFlow<List<UserModulesState>> =
        combine(_discovered, moduleRepository.enabledModulesState, _query, _filter, _sort) {
                tabs,
                enabled,
                query,
                filter,
                sort ->
                tabs.map { tab ->
                    tab.copy(
                        modules =
                            tab.modules
                                .map { it.copy(isEnabled = it.packageName in enabled) }
                                .filter { module ->
                                    val matchesQuery =
                                        query.isBlank() ||
                                            module.appName.contains(query, ignoreCase = true) ||
                                            module.packageName.contains(query, ignoreCase = true)
                                    val matchesFilter =
                                        when (filter) {
                                            ModuleFilter.All -> true
                                            ModuleFilter.Active -> module.isEnabled
                                            ModuleFilter.Inactive -> !module.isEnabled
                                        }
                                    matchesQuery && matchesFilter
                                }
                                .sortedWith(comparatorFor(sort))
                    )
                }
            }
            // Filtering happens off the main thread. stateIn(viewModelScope) alone collects on
            // Dispatchers.Main.immediate, which would run this over the full list on every
            // keystroke in the search field.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Reach is read from the facts map rather than the module, because it arrives after the list
     * does — sorting by it before it loads would reshuffle the list under the user's finger.
     */
    private fun comparatorFor(sort: ModuleSort): Comparator<InstalledModule> =
        when (sort) {
            // Active first: the list's first job is to say what is running.
            ModuleSort.EnabledFirst ->
                compareByDescending<InstalledModule> { it.isEnabled }
                    .thenBy { it.appName.lowercase() }
            ModuleSort.Name -> compareBy { it.appName.lowercase() }
            ModuleSort.RecentlyUpdated ->
                compareByDescending<InstalledModule> { it.lastUpdateTime }
                    .thenBy { it.appName.lowercase() }
            ModuleSort.WidestScope ->
                compareByDescending<InstalledModule> {
                        _facts.value[ModuleKey(it.packageName, it.userId)]?.scopeCount ?: -1
                    }
                    .thenBy { it.appName.lowercase() }
        }

    init {
        loadModules()
        moduleRepository.refresh()
        // The update marks, the header line and the sheet all read the catalogue, and until now
        // nothing on this screen asked for it — the marks appeared only if the splash prefetch had
        // happened to succeed. Cheap to repeat: the request is cache-controlled and a concurrent
        // caller is a no-op rather than a second 1.2 MB download.
        ServiceLocator.appScope.launch { runCatching { ServiceLocator.store.refresh() } }
        viewModelScope.launch {
            // drop(1): the current value is the state we just rendered, not a change.
            moduleRepository.scopeRevision.drop(1).collect { loadFacts(_discovered.value) }
        }
        viewModelScope.launch {
            // A package appearing or going away is the only thing that can change *which* modules
            // exist, so it is the only thing that needs a rediscovery. Everything else reuses the
            // cached answer.
            moduleRepository.packageRevision.drop(1).collect { loadModules() }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: ModuleFilter) {
        _filter.value = value
    }

    fun setSort(value: ModuleSort) {
        _sort.value = value
    }

    fun consumeToggleFailure() {
        _toggleFailed.value = null
    }

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun consumeBackupMessage() {
        _backupMessage.value = null
    }

    fun backupTo(uri: android.net.Uri, onResult: (Int?) -> Unit) {
        viewModelScope.launch {
            val count = ServiceLocator.backup.backupTo(uri).getOrNull()
            onResult(count)
        }
    }

    fun restoreFrom(
        uri: android.net.Uri,
        onResult: (org.matrix.vector.manager.data.repository.BackupRepository.RestoreOutcome?) -> Unit,
    ) {
        viewModelScope.launch {
            val outcome = ServiceLocator.backup.restoreFrom(uri).getOrNull()
            // Whatever happened, the list on screen is now stale.
            moduleRepository.refresh()
            loadModules()
            onResult(outcome)
        }
    }

    /**
     * Enable or disable a module.
     *
     * The single most important thing a manager for a hooking framework does, and it did not exist
     * at all before this: the repository's toggle had no call sites anywhere, and every row
     * rendered with a hardcoded `isEnabled = false`.
     */
    fun setEnabled(module: InstalledModule, enable: Boolean) {
        viewModelScope.launch {
            val ok = moduleRepository.toggleModule(module.packageName, enable)
            if (!ok) _toggleFailed.value = module.appName
        }
    }


    // --- selection ------------------------------------------------------------------------------

    /**
     * Which modules are selected, by package and user.
     *
     * Empty means the screen is in its ordinary reading mode; anything in it puts the screen into
     * selection mode. There is no separate `selectionMode` flag because two sources of truth for
     * one state is how a screen ends up in selection mode with nothing selected.
     */
    private val _selection = MutableStateFlow<Set<ModuleKey>>(emptySet())
    val selection: StateFlow<Set<ModuleKey>> = _selection.asStateFlow()

    fun toggleSelected(module: InstalledModule) {
        val key = ModuleKey(module.packageName, module.userId)
        _selection.update { if (key in it) it - key else it + key }
    }

    fun selectAll(modules: List<InstalledModule>) {
        _selection.update { it + modules.map { m -> ModuleKey(m.packageName, m.userId) } }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /**
     * Enables or disables everything selected, then reports how many actually changed.
     *
     * Sequential rather than concurrent: each toggle is a Binder call into the daemon that rewrites
     * the same config, and firing twenty at once at a single-threaded service buys nothing but a
     * harder failure to explain.
     */
    fun setSelectedEnabled(enable: Boolean, onResult: (changed: Int, failed: Int) -> Unit) {
        val targets = _selection.value.toList()
        viewModelScope.launch {
            var changed = 0
            var failed = 0
            targets.forEach { key ->
                if (moduleRepository.toggleModule(key.packageName, enable)) changed++ else failed++
            }
            // No re-read, and no rediscovery. Each toggle above already returned the daemon's own
            // answer and the repository recorded it; asking again could only replace something
            // confirmed with something less fresh, and a toggle changes nothing about which
            // packages are installed. The single-module path never did either, which is why it
            // moved the row and this one did not.
            _selection.value = emptySet()
            onResult(changed, failed)
        }
    }

    /** Uninstalls everything selected. The screen confirms first; this does not. */
    fun uninstallSelected(onResult: (removed: Int, failed: Int) -> Unit) {
        val targets = _selection.value.toList()
        viewModelScope.launch {
            var removed = 0
            var failed = 0
            targets.forEach { key ->
                val ok =
                    daemonClient.uninstallPackage(key.packageName, key.userId).getOrDefault(false)
                if (ok) removed++ else failed++
            }
            moduleRepository.refresh()
            loadModules()
            _selection.value = emptySet()
            onResult(removed, failed)
        }
    }

    /** Backs up only what is selected, using the same file format as a whole-collection backup. */
    fun backupSelectedTo(uri: android.net.Uri, onResult: (Int?) -> Unit) {
        val targets = _selection.value.map { it.packageName }.toSet()
        viewModelScope.launch {
            val count = ServiceLocator.backup.backupTo(uri, targets).getOrNull()
            _selection.value = emptySet()
            onResult(count)
        }
    }

    fun loadModules() {
        viewModelScope.launch {
            _isLoading.update { true }
            val tabs = withContext(Dispatchers.IO) { discover() }
            _discovered.update { tabs }
            _isLoading.update { false }
            loadFacts(tabs)
        }
    }

    /**
     * Reads each module's scope size and API requirement after the list is already on screen.
     *
     * One binder call per module, so it is deliberately not on the path to first paint — the list
     * appears immediately and the reach figures fill in.
     */
    private fun loadFacts(tabs: List<UserModulesState>) {
        viewModelScope.launch(Dispatchers.IO) {
            val api = daemonClient.getXposedApiVersion().getOrDefault(0)
            _frameworkApi.value = api
            // One lookup table for every scope preview, rather than a package-manager query per
            // scoped app per module.
            // Keyed by package *and* user. A device with a work profile or a private space holds
            // the same package twice, and collapsing them meant a row could depict the wrong
            // profile's copy of an app.
            val byPackage =
                ServiceLocator.apps.getInstalledApps().associateBy {
                    it.packageName to it.userId
                }

            val collected = mutableMapOf<ModuleKey, ModuleFacts>()
            // Every copy of every module, not one per package: the scope is read once but split
            // per user below, and each row needs its own answer.
            val scopeCache = mutableMapOf<String, List<Application>?>()
            tabs.flatMap { it.modules }.forEach { module ->
                val scope =
                    scopeCache.getOrPut(module.packageName) {
                        daemonClient.getModuleScope(module.packageName).getOrNull()
                    }
                // A module is almost always in its own scope, and its icon is already the row's
                // leading image — counting or showing it again says nothing.
                //
                // Only this user's targets. A module installed in two profiles has one scope list
                // covering both, so the owner's row was showing the work profile's apps as well —
                // visibly, as the same app icon twice.
                val targets =
                    scope?.filter {
                        it.packageName != module.packageName && it.userId == module.userId
                    }
                val framework = targets?.any { it.packageName == SYSTEM_FRAMEWORK } == true
                val apps = targets?.filter { it.packageName != SYSTEM_FRAMEWORK }.orEmpty()

                collected[ModuleKey(module.packageName, module.userId)] =
                    ModuleFacts(
                        // Counts what the row actually depicts. It used to count raw scope rows,
                        // so a module scoped to itself and the framework claimed "2 apps" while
                        // showing neither of them — the count and the icons disagreed because
                        // they were derived from different lists.
                        scopeCount = if (targets == null) -1 else apps.size,
                        // A module asking for a newer API than the framework provides cannot
                        // load; saying so here beats letting the user wonder why nothing happens.
                        incompatible = api > 0 && module.minVersion > api,
                        apiBrokenSince =
                            if (api <= 0) null
                            else XposedApi.brokenSince(module.minVersion, api),
                        scopeFramework = framework,
                        scopePreview =
                            apps
                                .asSequence()
                                .mapNotNull { byPackage[it.packageName to it.userId]?.applicationInfo }
                                .take(SCOPE_PREVIEW_LIMIT)
                                .toList(),
                    )
                _facts.value = collected.toMap()
            }
        }
    }

    private suspend fun discover(): List<UserModulesState> {
        val usersResult = daemonClient.getUsers()
        _daemonAvailable.value = usersResult.isSuccess
        val users = usersResult.getOrNull() ?: emptyList()

        val flags =
            PackageManager.GET_META_DATA or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                MATCH_ANY_USER

        val packages =
            daemonClient.getInstalledPackagesFromAllUsers(flags, filterNoProcess = false).getOrNull()
                ?: emptyList()

        // Through the cache, not straight to ModuleDetection: inspecting a package means opening
        // its APK and every split as a zip, and there are ~550 of those on a normal device. Keyed
        // by version code and install time, so an unchanged package is a map lookup and only a
        // newly installed or updated one is actually opened.
        val detection = ServiceLocator.moduleDetection
        val startedAt = SystemClock.elapsedRealtime()
        val allModules =
            packages.mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                val manifest =
                    detection.inspect(
                        appInfo,
                        packageManager,
                        pkg.longVersionCode,
                        pkg.lastUpdateTime,
                    )
                if (!manifest.isModule) return@mapNotNull null

                InstalledModule(
                    packageName = pkg.packageName,
                    userId = appInfo.uid / PER_USER_RANGE,
                    appName = appInfo.loadLabel(packageManager).toString(),
                    versionName = pkg.versionName ?: "",
                    versionCode = pkg.longVersionCode,
                    description = manifest.description,
                    minVersion = manifest.minApiVersion,
                    targetVersion = manifest.targetApiVersion,
                    isLegacy = manifest.isLegacy,
                    declaresApiVersion = manifest.declaresApiVersion,
                    lastUpdateTime = pkg.lastUpdateTime,
                    isEnabled = false, // merged in from the repository above
                    applicationInfo = appInfo,
                )
            }

        detection.flush(packages.mapNotNull { it.packageName }.toSet())
        // The one number that explains a slow Modules panel: how many APKs this scan had to open.
        // Everything else is a map lookup, so a large figure here after the first run means the
        // cache key is wrong rather than that the device is slow.
        Log.i(
            TAG,
            "Scanned ${packages.size} packages in " +
                "${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "opened ${detection.inspectedThisRun}",
        )

        val perUser =
            users.map { user ->
                UserModulesState(
                    user = user,
                    modules =
                        allModules
                            .filter { it.userId == user.id }
                            .sortedBy { it.appName.lowercase() },
                )
            }

        // A profile with no modules in it is not a tab worth having: a private space that has never
        // had one adds a second tab to a screen that otherwise has none, and the tab bar then
        // implies a choice where there is nothing to choose. If that empties the list entirely,
        // keep it as it was so the empty state has a user to name.
        return perUser.filter { it.modules.isNotEmpty() }.ifEmpty { perUser.take(1) }
    }
}
