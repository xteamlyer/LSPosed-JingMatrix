package org.matrix.vector.manager.ui.screens.modules
import android.util.Log
import org.matrix.vector.manager.Constants
import kotlinx.coroutines.CancellationException

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspd.models.Application
import org.matrix.vector.manager.data.model.AppInfo
import org.matrix.vector.manager.data.model.ModuleDetection
import org.matrix.vector.manager.data.model.RecommendedScope
import org.matrix.vector.manager.data.repository.AppRepository
import org.matrix.vector.manager.data.repository.ModuleRepository
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.data.repository.SettingsRepository
import org.matrix.vector.manager.ipc.DaemonClient

/** A package/user pair, as a value type so set arithmetic is correct. */
data class ScopeTarget(val packageName: String, val userId: Int)

/**
 * How the list is ordered.
 *
 * The legacy manager offered four orderings plus a reverse toggle, and dropping them was a real
 * loss: sorting by package name is how you find something whose display name you cannot recall,
 * and by install time is how you find the app you added five minutes ago.
 */
enum class ScopeSort {
    /** Selected first, then recommended, then alphabetical — the working order. */
    Relevance,
    Name,
    PackageName,
    InstallTime,
    UpdateTime,
}

data class ScopeUiState(
    val moduleName: String = "",
    val isEnabled: Boolean = false,
    val includeNewApps: Boolean = false,
    val recommended: RecommendedScope = RecommendedScope.NONE,
    val loading: Boolean = true,
    /**
     * Whether this device has more than one user at all.
     *
     * The framework row explains that it is shared across users, which is only ever news on a
     * device that has more than one. On a single-user phone — most of them — it is a sentence
     * about a distinction that does not exist, so it is not shown.
     */
    val multipleUsers: Boolean = false,
)

class ScopeViewModel(
    private val modulePackageName: String,
    /**
     * The user the module is installed for. The previous navigation layer parsed this out of the
     * route and then threw it away, so a module in a work profile had its scope resolved against
     * the wrong user.
     */
    private val userId: Int,
    private val daemonClient: DaemonClient,
    private val appRepository: AppRepository,
    private val moduleRepository: ModuleRepository,
    private val packageManager: android.content.pm.PackageManager,
    private val settings: SettingsRepository = ServiceLocator.settings,
) : ViewModel() {

    private val allApps = MutableStateFlow<List<AppInfo>>(emptyList())

    /** What the daemon currently holds. */
    private val savedScope = MutableStateFlow<Set<ScopeTarget>>(emptySet())

    /**
     * What the user has built up but not yet applied.
     *
     * The whole reason this is separate: applying a scope makes the daemon force-stop every
     * affected app. The previous implementation wrote the entire scope on *every checkbox tap*, so
     * ticking ten apps killed and restarted them ten times over. Edits accumulate here and go out
     * as one write.
     */
    private val draftScope = MutableStateFlow<Set<ScopeTarget>>(emptySet())

    private val _uiState = MutableStateFlow(ScopeUiState())
    val uiState: StateFlow<ScopeUiState> = _uiState.asStateFlow()

    val searchQuery = MutableStateFlow("")
    /**
     * System apps are hidden by default.
     *
     * A device carries several hundred of them and a handful of apps the user installed; showing
     * both at once buries the second set. Anything already in the scope is exempt from every
     * filter, so turning this off can never hide a choice that has been made.
     */
    val showSystemApps = MutableStateFlow(settings.scopeShowSystemApps.value)

    val showGames = MutableStateFlow(settings.scopeShowGames.value)

    /**
     * Show only what the module asked for.
     *
     * The same view a static scope gets, reachable on purpose. A module that declares a scope but
     * does not fix it leaves the user to find those apps among several hundred, and the list already
     * knows which they are — so this is the "just show me what it wants" the static case gets for
     * free. It narrows to the module's own request, not to what is currently ticked, so it stays
     * useful for adding the ones that are missing.
     */
    val showRecommendedOnly = MutableStateFlow(false)

    /**
     * Whether other Xposed modules appear in the list.
     *
     * They are installed apps like any other and a module *can* legitimately hook one, but that
     * is rare enough that the default is off: on a device with two dozen modules, listing them
     * all among the hookable apps is two dozen rows of noise for one plausible use.
     */
    val showModules = MutableStateFlow(settings.scopeShowModules.value)

    val sort =
        MutableStateFlow(
            ScopeSort.entries.firstOrNull { it.name.equals(settings.scopeSort.value, true) }
                ?: ScopeSort.Relevance
        )
    val reverseSort = MutableStateFlow(settings.scopeSortReversed.value)

    /**
     * Whether the list is filtered differently from how it ships.
     *
     * Compared against the defaults, not against "is anything hidden" — the defaults hide system
     * apps and other modules on purpose, so the second reading is true on a device nobody has
     * touched, and a mark that is always on is a mark that says nothing. It was: the old expression
     * asked for `!showModules`, which is the default, so the dot was lit permanently.
     *
     * It earns its place now that these choices are remembered. Coming back to a list that is
     * filtered the way you left it a week ago is exactly the moment you need telling.
     */
    val filtersChanged: StateFlow<Boolean> =
        combine(showSystemApps, showGames, showModules, showRecommendedOnly) {
                system,
                games,
                modules,
                recommendedOnly ->
                system != DEFAULT_SHOW_SYSTEM ||
                    games != DEFAULT_SHOW_GAMES ||
                    modules != DEFAULT_SHOW_MODULES ||
                    recommendedOnly
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Whether this module has a screen to open at all.
     *
     * Null until asked, so the control does not flicker into existence on arrival. Most modules
     * have no companion and no launcher entry, and offering to open one is offering nothing —
     * which is why this is worth a lookup rather than a snackbar after the fact.
     *
     * Declared above [init] and not beside the function that fills it, because it has to be. The
     * lookup runs on `Main.immediate`, which starts the coroutine inline on the constructing
     * thread: the body reaches this field while the constructor is still running, and every
     * property below the `init` block is still null at that point. It was — a `null` field read,
     * parked across the suspension, and a crash on the way back.
     */
    private val _companion = MutableStateFlow<Boolean?>(null)
    val hasCompanion: StateFlow<Boolean?> = _companion.asStateFlow()

    init {
        findCompanion()
        // Written back as they change rather than on the way out: this screen is left by a back
        // gesture, by the process being killed, and by the host application deciding it is done —
        // and only the first of those runs any teardown of ours.
        viewModelScope.launch {
            showSystemApps.collect { settings.setScopeShowSystemApps(it) }
        }
        viewModelScope.launch { showGames.collect { settings.setScopeShowGames(it) } }
        viewModelScope.launch { showModules.collect { settings.setScopeShowModules(it) } }
        viewModelScope.launch { sort.collect { settings.setScopeSort(it.name.lowercase()) } }
        viewModelScope.launch { reverseSort.collect { settings.setScopeSortReversed(it) } }
    }

    /**
     * Packages that are themselves modules.
     *
     * Null until known. Deciding this means opening every installed APK, so it is computed once
     * per process by [AppRepository] and shared; until it arrives the filter simply does not apply,
     * which shows a few extra rows for a moment rather than blocking the list on disk I/O.
     */
    private val modulePackages = MutableStateFlow<Set<String>?>(null)

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val _message = MutableStateFlow<ScopeMessage?>(null)
    val message: StateFlow<ScopeMessage?> = _message.asStateFlow()

    /** Added and removed relative to what the daemon holds, so the UI can say what Apply will do. */
    val pendingChanges: StateFlow<PendingChanges> =
        combine(savedScope, draftScope) { saved, draft ->
                PendingChanges(
                    added = (draft - saved).size,
                    removed = (saved - draft).size,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PendingChanges())

    val filteredApps: StateFlow<List<AppInfo>> =
        combine(allApps, draftScope, searchQuery, showSystemApps, showGames) {
                apps,
                draft,
                query,
                showSys,
                showGame ->
                Filters(apps, draft, query, showSys, showGame, false)
            }
            .combine(showRecommendedOnly) { filters, only -> filters.copy(recommendedOnly = only) }
            // Two typed halves rather than one list of Any. The inputs outnumber the arities
            // `combine` provides, and the previous shape carried them positionally through a
            // `List<Any>` and cast each one back out — a rename or a reorder would have compiled
            // and then failed at runtime.
            .combine(
                combine(showModules, modulePackages, sort, reverseSort, _uiState) {
                    showMods,
                    modules,
                    order,
                    reverse,
                    state ->
                    View(showMods, modules, order, reverse, state)
                }
            ) { filters, view ->
                val showMods = view.showModules
                val modules = view.modulePackages
                val order = view.sort
                val reverse = view.reverse
                val recommended = view.state.recommended.packages.toSet()
                // A static scope is the module's whole answer, so the list *is* that set. Showing
                // the other few hundred apps beneath uncheckable checkboxes offered a choice that
                // does not exist.
                val locked = view.state.recommended.staticScope || filters.recommendedOnly
                filters.apps
                    .asSequence()
                    .filter { app -> !locked || app.packageName in recommended }
                    .filter { app ->
                        val matchesQuery =
                            filters.query.isBlank() ||
                                app.appName.contains(filters.query, ignoreCase = true) ||
                                app.packageName.contains(filters.query, ignoreCase = true)
                        // The framework is always offered: it is the one target a module cannot
                        // reach any other way, and hiding it behind a filter strands the module.
                        val isFramework = app.packageName == SYSTEM_FRAMEWORK_PACKAGE
                        // An app already in the scope is never filtered away. Otherwise a default
                        // filter can hide a target the user deliberately chose, and the list then
                        // disagrees with what the module is actually hooking.
                        val chosen = ScopeTarget(app.packageName, app.userId) in filters.draft
                        val matchesSys =
                            isFramework || chosen || filters.showSystem || !app.isSystemApp
                        val matchesGame = chosen || filters.showGames || !app.isGame
                        val matchesModule =
                            chosen || showMods || modules == null || app.packageName !in modules
                        matchesQuery && matchesSys && matchesGame && matchesModule
                    }
                    .map { app ->
                        app.copy(
                            isSelectedInScope =
                                ScopeTarget(app.packageName, app.userId) in filters.draft,
                            isRecommended = app.packageName in recommended,
                        )
                    }
                    .sortedWith(comparatorFor(order))
                    .toList()
                    .let { if (reverse) it.reversed() else it }
                    // Pinned above the apps, and after the reverse so that reversing the order
                    // cannot bury it at the bottom. It is not an app and does not belong in the
                    // same ordering as one: sorted by name it landed under S, by install time it
                    // landed wherever its borrowed timestamp put it, and either way the one target
                    // that is not discoverable any other way was somewhere in a list of thousands.
                    .let { list ->
                        val framework = list.filter { it.packageName == SYSTEM_FRAMEWORK_PACKAGE }
                        if (framework.isEmpty()) list
                        else framework + list.filterNot { it.packageName == SYSTEM_FRAMEWORK_PACKAGE }
                    }
            }
            // Filtering and sorting the full installed-app list is real work — often thousands of
            // entries — and stateIn(viewModelScope) alone would run it on Dispatchers.Main.immediate
            // on every keystroke.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything outside the app list that decides what the list shows. */
    private data class View(
        val showModules: Boolean,
        val modulePackages: Set<String>?,
        val sort: ScopeSort,
        val reverse: Boolean,
        val state: ScopeUiState,
    )

    private data class Filters(
        val apps: List<AppInfo>,
        val draft: Set<ScopeTarget>,
        val query: String,
        val showSystem: Boolean,
        val showGames: Boolean,
        val recommendedOnly: Boolean,
    )

    private fun comparatorFor(order: ScopeSort): Comparator<AppInfo> =
        when (order) {
            ScopeSort.Name -> compareBy { it.appName.lowercase() }
            ScopeSort.PackageName -> compareBy { it.packageName }
            ScopeSort.InstallTime ->
                compareByDescending<AppInfo> { it.firstInstallTime }
                    .thenBy { it.appName.lowercase() }
            ScopeSort.UpdateTime ->
                compareByDescending<AppInfo> { it.lastUpdateTime }.thenBy { it.appName.lowercase() }
            // Whatever the user is working on floats up: what they have chosen, then what the
            // module asked for, then everything else.
            ScopeSort.Relevance ->
                compareByDescending<AppInfo> { it.isSelectedInScope }
                    .thenByDescending { it.isRecommended }
                    .thenBy { it.appName.lowercase() }
        }

    init {
        load()
        // Hidden by default, so the set has to be known without anyone asking for it. It is cached
        // per process, so this is free after the first scope screen of the session.
        viewModelScope.launch { modulePackages.value = appRepository.modulePackages() }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)

            // Only the apps belonging to the user this module is installed for. A module in a
            // work profile can only hook that profile's apps, so listing the owner's alongside
            // them offers choices the framework will not honour — and the same package appears
            // once per user, so an unfiltered list shows visible duplicates.
            val apps =
                withContext(Dispatchers.IO) {
                    appRepository.getInstalledApps().filter { it.userId == userId }
                }

            // The system server is a hook target like any other and modules ask for it by name,
            // but it is not an installed package so it never appears in the package list. Without
            // this entry a module whose entire recommended scope is the framework — Core Patch,
            // for one — offers the user nothing to tick.
            //
            // Offered to every user, not only the owner. There is exactly one system_server on the
            // device, so it is not a per-user target that other users happen to lack — it is one
            // process they all share. Restricting the row to user 0 left a module in a work profile
            // or a private space with no way to ask for the only target it may need (issue #136);
            // the daemon never had that restriction, mapping any `system` scope row straight to
            // system_server without looking at whose module it was.
            val withFramework = listOf(systemFrameworkEntry(apps)) + apps
            allApps.value = withFramework

            // Asked once per load rather than per row, and carried into the state built at the
            // end of this function rather than copied into the state that exists now — that copy
            // was silently discarded when the final ScopeUiState was constructed from scratch. A
            // failure to ask means not explaining, never explaining wrongly.
            val userCount =
                withContext(Dispatchers.IO) { daemonClient.getUsers().getOrNull()?.size ?: 1 }

            val savedResult = daemonClient.getModuleScope(modulePackageName)
            // Keyed on the exception, not on a null: a fresh module with nothing ticked is a
            // success carrying an empty list and must stay silent.
            savedResult.exceptionOrNull()?.let { e ->
                Log.e(
                    Constants.TAG,
                    "scope: reading the saved scope of $modulePackageName failed, showing none",
                    e,
                )
            }
            val saved =
                savedResult
                    .getOrNull()
                    ?.map { ScopeTarget(it.packageName, it.userId) }
                    ?.toSet() ?: emptySet()
            savedScope.value = saved
            draftScope.value = saved

            val info =
                withContext(Dispatchers.IO) {
                    runCatching {
                            packageManager.getApplicationInfo(
                                modulePackageName,
                                android.content.pm.PackageManager.GET_META_DATA,
                            )
                        }
                        .onFailure { e ->
                            Log.w(
                                Constants.TAG,
                                "scope: package info for $modulePackageName (user $userId) " +
                                    "unavailable, no recommended scope",
                                e,
                            )
                        }
                        .getOrNull()
                }
            val recommended =
                info?.let {
                    withContext(Dispatchers.IO) {
                        val manifest = ModuleDetection.inspect(it, packageManager)
                        RecommendedScope(manifest.scope, manifest.staticScope)
                    }
                } ?: RecommendedScope.NONE

            _uiState.value =
                ScopeUiState(
                    moduleName =
                        info?.loadLabel(packageManager)?.toString() ?: modulePackageName,
                    isEnabled = modulePackageName in moduleRepository.enabledModulesState.value,
                    includeNewApps =
                        daemonClient.getIncludeNewApps(modulePackageName).getOrDefault(false),
                    recommended = recommended,
                    loading = false,
                    multipleUsers = userCount > 1,
                )
        }
    }

    /**
     * A stand-in for the system server.
     *
     * Borrows an existing entry's [android.content.pm.ApplicationInfo] purely so the row has
     * something to draw an icon from; only the package name and label are meaningful.
     */
    private fun systemFrameworkEntry(apps: List<AppInfo>): AppInfo {
        val donor = apps.firstOrNull()
        return AppInfo(
            packageName = SYSTEM_FRAMEWORK_PACKAGE,
            userId = 0,
            appName = FRAMEWORK_LABEL,
            isSystemApp = true,
            isGame = false,
            isSelectedInScope = false,
            isRecommended = false,
            lastUpdateTime = Long.MAX_VALUE,
            firstInstallTime = Long.MAX_VALUE,
            applicationInfo = donor?.applicationInfo ?: android.content.pm.ApplicationInfo(),
        )
    }

    /** Local only. Nothing reaches the daemon until [apply]. */
    fun toggle(app: AppInfo, selected: Boolean) {
        val target = ScopeTarget(app.packageName, app.userId)
        draftScope.value =
            if (selected) draftScope.value + target else draftScope.value - target
    }

    fun selectAllVisible() {
        draftScope.value =
            draftScope.value +
                filteredApps.value.map { ScopeTarget(it.packageName, it.userId) }
    }

    fun clearAllVisible() {
        draftScope.value =
            draftScope.value -
                filteredApps.value.map { ScopeTarget(it.packageName, it.userId) }.toSet()
    }

    /** Replace the draft with exactly what the module asked for. */
    fun useRecommended() {
        val recommended = _uiState.value.recommended.packages.toSet()
        if (recommended.isEmpty()) return
        draftScope.value =
            allApps.value
                .filter { it.packageName in recommended }
                .map { ScopeTarget(it.packageName, it.userId) }
                .toSet()
    }

    fun discard() {
        draftScope.value = savedScope.value
    }

    fun setSort(order: ScopeSort) {
        sort.value = order
    }

    fun toggleReverse() {
        reverseSort.value = !reverseSort.value
    }

    /**
     * Turns the "show modules" filter on or off, computing the module set the first time it is
     * needed.
     */
    fun setShowModules(show: Boolean) {
        showModules.value = show
    }

    fun setRecommendedOnly(only: Boolean) {
        showRecommendedOnly.value = only
    }

    fun setIncludeNewApps(enabled: Boolean) {
        viewModelScope.launch {
            daemonClient
                .setIncludeNewApps(modulePackageName, enabled)
                .onSuccess { stored ->
                    // The daemon's answer, not merely the fact that it answered. It refuses a
                    // package it holds no row for, and moving the switch anyway showed a setting
                    // that was never saved.
                    if (stored) {
                        _uiState.value = _uiState.value.copy(includeNewApps = enabled)
                    } else {
                        Log.e(
                            Constants.TAG,
                            "scope: daemon refused include-new-apps=$enabled for " +
                                modulePackageName,
                        )
                        _message.value = ScopeMessage.IncludeNewAppsFailed
                    }
                }
                .onFailure { e ->
                    Log.e(
                        Constants.TAG,
                        "scope: setting include-new-apps=$enabled for $modulePackageName failed",
                        e,
                    )
                    _message.value = ScopeMessage.IncludeNewAppsFailed
                }
        }
    }

    /** Fills [hasCompanion], which is declared next to [init] for the reason given there. */
    private fun findCompanion() {
        viewModelScope.launch {
            _companion.value =
                daemonClient
                    .findAppUi(modulePackageName, userId, companionFirst = true)
                    .onFailure { e ->
                        Log.w(
                            Constants.TAG,
                            "scope: companion lookup for $modulePackageName user $userId failed",
                            e,
                        )
                    }
                    .getOrNull() != null
        }
    }

    /**
     * Opens the module's own screen — its companion activity, or its launcher entry.
     *
     * Here as well as in the long-press sheet because this is the screen you are on when you are
     * thinking about that module: a scope is half of its configuration and the other half lives
     * inside the module, so having to go back to the list to reach it was a detour through a place
     * you had just come from.
     *
     * Asks the same question [findCompanion] asked. It did not: the button appeared when a
     * companion existed and then opened whatever a plain app would open, so a module whose only
     * screen is its Xposed settings activity showed a button that could never work.
     */
    fun openModule() {
        viewModelScope.launch {
            val opened =
                daemonClient
                    .openAppUi(modulePackageName, userId, companionFirst = true)
                    .onFailure { e ->
                        Log.e(
                            Constants.TAG,
                            "scope: companion open of $modulePackageName for user $userId failed",
                            e,
                        )
                    }
                    .getOrDefault(false)
            if (!opened) _message.value = ScopeMessage.NothingToOpen
        }
    }

    fun setModuleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (moduleRepository.toggleModule(modulePackageName, enabled)) {
                _uiState.value = _uiState.value.copy(isEnabled = enabled)
            } else {
                _message.value = ScopeMessage.ToggleFailed
            }
        }
    }

    /**
     * Writes the draft, once.
     *
     * The daemon force-stops every app whose scope changed, which is why this is an explicit act
     * with a visible cost rather than a side effect of ticking a box.
     */
    fun apply() {
        if (_applying.value) return
        viewModelScope.launch {
            _applying.value = true
            val draft = draftScope.value
            val aidl =
                draft.map { target ->
                    Application().apply {
                        packageName = target.packageName
                        userId = target.userId
                    }
                }
            daemonClient
                .setModuleScope(modulePackageName, aidl)
                .onSuccess {
                    savedScope.value = draft
                    _message.value = ScopeMessage.Applied
                    // The module list depicts this scope as a row of app icons. It is a different
                    // screen with a different view model, so it is told rather than left to
                    // discover the change on the next manual refresh.
                    ServiceLocator.modules.noteScopeChanged()
                }
                .onFailure { e ->
                    Log.e(
                        Constants.TAG,
                        "scope: apply of ${draft.size} targets to $modulePackageName failed",
                        e,
                    )
                    _message.value = ScopeMessage.ApplyFailed
                }
            _applying.value = false
        }
    }

    /**
     * True when leaving now would leave the module enabled with nothing to hook.
     *
     * That combination does nothing at all but looks like it works, so the user is warned rather
     * than left to discover it. The legacy manager offered to disable the module; so does this.
     */
    fun wouldStrandModule(): Boolean =
        _uiState.value.isEnabled && draftScope.value.isEmpty() && savedScope.value.isEmpty()

    /**
     * This one module's scope, as plain JSON.
     *
     * Not gzipped like the whole-list backup: a single scope is small, and a readable file is
     * worth more here — it is the kind of thing someone hand-edits or pastes into an issue.
     */
    fun backupScopeTo(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                            val payload =
                                draftScope.value.joinToString(",\n  ") {
                                    """{"packageName":"${it.packageName}","userId":${it.userId}}"""
                                }
                            ServiceLocator.context.contentResolver.openOutputStream(uri)?.use {
                                it.write("[\n  $payload\n]".toByteArray())
                            } ?: error("could not open the file")
                        }
                        .onFailure { e ->
                            Log.e(Constants.TAG, "scope: backup of $modulePackageName failed", e)
                        }
                        .isSuccess
                }
            onDone(ok)
        }
    }

    fun restoreScopeFrom(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val targets =
                withContext(Dispatchers.IO) {
                    runCatching {
                            val text =
                                ServiceLocator.context.contentResolver.openInputStream(uri)?.use {
                                    it.readBytes().decodeToString()
                                } ?: error("could not open the file")
                            Regex("\"packageName\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"userId\"\\s*:\\s*(\\d+)")
                                .findAll(text)
                                .map { ScopeTarget(it.groupValues[1], it.groupValues[2].toInt()) }
                                .toSet()
                        }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            Log.e(Constants.TAG, "scope: restore for $modulePackageName failed", e)
                        }
                        .getOrNull()
                }
            if (targets == null) {
                onDone(false)
            } else {
                // Into the draft, not straight to the daemon: a restore is an edit like any
                // other, and the user should see what it will do before it force-stops anything.
                draftScope.value = targets
                onDone(true)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // Not private: the scope list has to recognise the framework row to explain what it is, and
    // a second copy of the literal in the screen would be a second thing to keep in step.
    internal companion object {
        /** What the list looks like before anyone touches it; see [filtersChanged]. */
        const val DEFAULT_SHOW_SYSTEM = false
        const val DEFAULT_SHOW_GAMES = true
        const val DEFAULT_SHOW_MODULES = false

        /** How the daemon names the system server in a scope list. */
        const val SYSTEM_FRAMEWORK_PACKAGE = "system"

        /**
         * What to *show* for it, which is not what it is stored as.
         *
         * The scope table has said `system` since long before this manager, and the daemon, the
         * CLI and every backup file on every device say it too — so the stored name stays. But the
         * process it actually means is `system_server`, and a reader looking at a package name
         * expects the name of the thing. The rename lives here, at the point of display, and
         * nothing written back to the daemon ever passes through it.
         */
        const val SYSTEM_FRAMEWORK_DISPLAY_NAME = "system_server"

        /** The package name as it should appear on screen. */
        fun displayPackageName(packageName: String): String =
            if (packageName == SYSTEM_FRAMEWORK_PACKAGE) SYSTEM_FRAMEWORK_DISPLAY_NAME
            else packageName
        const val FRAMEWORK_LABEL = "System Framework"
    }
}

data class PendingChanges(val added: Int = 0, val removed: Int = 0) {
    val any: Boolean
        get() = added > 0 || removed > 0
}

enum class ScopeMessage {
    Applied,
    ApplyFailed,
    ToggleFailed,
    IncludeNewAppsFailed,
    NothingToOpen,
}
