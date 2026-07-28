package org.matrix.vector.manager.ui.screens.home

import org.matrix.vector.manager.data.repository.FrameworkUpdateState
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.data.github.CommunityFeed
import org.matrix.vector.manager.data.github.GitHubAuth
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.ui.components.FrameworkState

/** A specific reason the framework is degraded, so the UI never has to say merely "something". */
enum class HealthIssue {
    SepolicyNotLoaded,
    SystemServerNotInjected,
    Dex2oatWrapperBroken,
}

data class FrameworkStatus(
    val state: FrameworkState = FrameworkState.Checking,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val apiVersion: Int? = null,
    val issues: List<HealthIssue> = emptyList(),
    val dex2oatCompatibility: Int = ILSPManagerService.DEX2OAT_OK,
    val sepolicyLoaded: Boolean = false,
    val systemServerInjected: Boolean = false,
    /**
     * The commit the running framework was built from, short, `-dirty` when its tree was not clean.
     *
     * The version code is a commit count, so it cannot tell a branch build from the official build
     * of the same depth — and the framework and the manager are flashed separately, so they are
     * not always the same build. Naming both is the difference between a bug report that can be
     * placed and one that cannot.
     */
    val commit: String? = null,
) {
    val versionLabel: String?
        get() = versionName?.let { if (versionCode > 0) "$it ($versionCode)" else it }
}

data class DeviceInfo(
    val androidRelease: String = Build.VERSION.RELEASE ?: "",
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val device: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
)

class HomeViewModel(
    private val daemon: DaemonClient,
    private val github: GitHubRepository,
    private val auth: GitHubAuth,
) : ViewModel() {

    val signInState: StateFlow<org.matrix.vector.manager.data.github.SignInState> = auth.state

    val openLinksExternally: StateFlow<Boolean> = ServiceLocator.settings.openLinksExternally

    val headerAmbience: StateFlow<String> = ServiceLocator.settings.headerAmbience

    val isSignInConfigured: Boolean
        get() = auth.isConfigured

    fun signIn() {
        viewModelScope.launch { auth.signIn() }
    }

    fun signOut() {
        auth.signOut()
        // The rate limit changes with the token, so the feed is worth re-reading.
        refreshFeed(GitHubRepository.Freshness.Force)
    }

    fun cancelSignIn() = auth.cancel()

    private val _status = MutableStateFlow(FrameworkStatus())
    val status: StateFlow<FrameworkStatus> = _status.asStateFlow()

    private val _feed = MutableStateFlow(CommunityFeed())
    val feed: StateFlow<CommunityFeed> = _feed.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val device = DeviceInfo()

    init {
        // The binder may arrive after this ViewModel exists — injection order is not ours to
        // control — so status is re-derived whenever it changes rather than read once in init.
        // Reading it once is why the previous Home could get permanently stuck on "Not Activated".
        viewModelScope.launch {
            ServiceLocator.service.collect { service -> refreshStatus(service) }
        }
        // Opening Home is not a reason to talk to GitHub. The page renders from disk every time
        // and only occasionally goes and checks — the window it shows changes a few times a week
        // at most, and the user's battery and their share of an anonymous rate limit are worth
        // more than redrawing identical rows. Pull-to-refresh is always there when they do want it.
        val checkNow = Random.nextFloat() < REVALIDATE_PROBABILITY
        refreshFeed(
            if (checkNow) GitHubRepository.Freshness.Revalidate
            else GitHubRepository.Freshness.Cached
        )
        viewModelScope.launch {
            // drop(1): the value on subscription is the window already rendered.
            ServiceLocator.settings.activityWindowMonths.drop(1).collect {
                _windowChanged.value = true
                // From disk, not from GitHub. The archive already holds the commits; the window is
                // only a view of it, so re-cutting it costs nothing and the feed answers on the
                // frame after the sheet closes.
                _feed.update { github.load(GitHubRepository.Freshness.Cached) }
            }
        }
    }

    private suspend fun refreshStatus(service: ILSPManagerService?) {
        if (service == null || !daemon.isAlive) {
            _status.value = FrameworkStatus(state = FrameworkState.Inactive)
            return
        }

        val versionName = daemon.getXposedVersionName().getOrNull()
        val commit = daemon.getFrameworkCommit().getOrNull()
        val versionCode = daemon.getXposedVersionCode().getOrDefault(0L)
        val api = daemon.getXposedApiVersion().getOrNull()

        val sepolicy = daemon.isSepolicyLoaded().getOrDefault(false)
        val systemServer = daemon.systemServerRequested().getOrDefault(false)
        val dex2oat =
            daemon.getDex2OatWrapperCompatibility().getOrDefault(ILSPManagerService.DEX2OAT_OK)
        val dex2oatFlags = daemon.dex2oatFlagsLoaded().getOrDefault(true)

        val issues = buildList {
            if (!sepolicy) add(HealthIssue.SepolicyNotLoaded)
            if (!systemServer) add(HealthIssue.SystemServerNotInjected)
            // Matches the daemon's own rule: a non-OK wrapper only matters when the flags did
            // not load.
            if (dex2oat != ILSPManagerService.DEX2OAT_OK && !dex2oatFlags) {
                add(HealthIssue.Dex2oatWrapperBroken)
            }
        }

        _status.value =
            FrameworkStatus(
                state = if (issues.isEmpty()) FrameworkState.Active else FrameworkState.Degraded,
                commit = commit,
                versionName = versionName,
                versionCode = versionCode,
                apiVersion = api,
                issues = issues,
                dex2oatCompatibility = dex2oat,
                sepolicyLoaded = sepolicy,
                systemServerInjected = systemServer,
            )

        if (versionCode > 0) {
            viewModelScope.launch {
                ServiceLocator.frameworkUpdates.refresh(
                    versionCode,
                    daemon.getFrameworkCommit().getOrNull(),
                )
            }
        }
    }

    // --- filtering the rail by author ---------------------------------------------------------

    /**
     * The logins whose commits are being shown, lower-cased; empty means everyone.
     *
     * A set rather than a single login because collaboration is the thing this screen is about:
     * two people's rails side by side answers "what did we do together", which one person's rail
     * cannot. Emptying it is the only way out of filter mode, so there is exactly one way back to
     * the whole history and it is the same gesture that got you here.
     */
    private val _authorFilter = MutableStateFlow<Set<String>>(emptySet())
    val authorFilter: StateFlow<Set<String>> = _authorFilter.asStateFlow()

    fun toggleAuthorFilter(login: String) {
        val key = login.lowercase()
        _authorFilter.update { if (key in it) it - key else it + key }
    }

    fun clearAuthorFilter() {
        _authorFilter.value = emptySet()
    }

    /**
     * The rail, laid out: commits with their elapsed-time gaps, month boundaries, named silences,
     * and the marker showing where the reader's own build sits in the history.
     */
    val feedItems: StateFlow<List<org.matrix.vector.manager.data.github.FeedItem>> =
        combine(_feed, _status, _authorFilter) { feed, status, filter ->
                // The framework's version when the daemon is up, otherwise this manager's own.
                // Both are `git rev-list --count` on the same repository, so either locates a
                // build on the timeline correctly — and without the fallback the marker would
                // simply never appear for anyone running the manager standalone.
                val installed =
                    if (status.versionCode > 0) status.versionCode
                    else org.matrix.vector.manager.BuildConfig.VERSION_CODE.toLong()
                org.matrix.vector.manager.data.github.FeedLayout.build(
                    feed.filteredBy(filter),
                    installed,
                )
            }
            // Off the main thread, and this is not a precaution. `stateIn(viewModelScope, …)`
            // collects on the main dispatcher, so laying the rail out — filtering, grouping by
            // month, measuring every gap — happened there. At a hundred commits that was invisible.
            // At the two thousand the archive now holds, every filter toggle froze the very frame
            // that was meant to acknowledge the touch.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- framework toggles ------------------------------------------------------------------
    // These used to live on a catch-all Settings screen. They are properties of the *framework*,
    // so they belong on the screen that reports the framework's state.

    private val _statusNotification = MutableStateFlow(false)
    val statusNotification: StateFlow<Boolean> = _statusNotification.asStateFlow()

    /**
     * Whether a newer framework build exists, on the channel this device is actually on.
     *
     * Refreshed off the back of the status read rather than on its own timer: the version code it
     * compares against comes from the same daemon call, and asking GitHub before we know what we
     * are running would compare against zero.
     */
    val frameworkUpdate: StateFlow<FrameworkUpdateState> = ServiceLocator.frameworkUpdates.state

    // True is the platform's own default, so the switch shows what the system is doing on a device
    // where nobody has touched it — rather than reading "off" until the daemon answers.
    private val _hiddenIcon = MutableStateFlow(true)
    val hiddenIcon: StateFlow<Boolean> = _hiddenIcon.asStateFlow()

    private suspend fun refreshToggles() {
        _statusNotification.value = daemon.enableStatusNotification().getOrDefault(false)
        // Read, not assumed. This one had no getter at all, so the switch started at a hardcoded
        // value on every launch and told the reader whatever that value was — which on a device
        // where the setting had been changed was simply wrong.
        _hiddenIcon.value = daemon.forcedLauncherIcons().getOrDefault(true)
    }

    fun setStatusNotification(enabled: Boolean) {
        viewModelScope.launch {
            daemon.setEnableStatusNotification(enabled).onSuccess {
                _statusNotification.value = enabled
            }
        }
    }

    fun setForcedLauncherIcons(force: Boolean) {
        viewModelScope.launch {
            // The old implementation wrote the inverse of what it read — it set the daemon flag
            // from one source of truth and read its state back from Settings.Global — so the
            // switch could show a state the framework did not hold. It now reports only what it
            // successfully wrote.
            daemon.setForcedLauncherIcons(force).onSuccess {
                // Read back rather than assumed: the write reaches a system setting that can
                // refuse, and this switch has spent its life reporting a success it never checked.
                _hiddenIcon.value = daemon.forcedLauncherIcons().getOrDefault(force)
            }
        }
    }

    fun refreshFeed(freshness: GitHubRepository.Freshness) {
        viewModelScope.launch {
            _refreshing.value = true
            _feed.update { github.load(freshness) }
            _refreshing.value = false
            // Anything that actually went to the network settles the debt below.
            if (freshness != GitHubRepository.Freshness.Cached) _windowChanged.value = false
        }
    }

    /**
     * True when the window was changed and nothing has been fetched since.
     *
     * Changing "the last six months" to "since the beginning" used to do nothing visible until the
     * next launch: the window is read inside the repository at load time, and nothing reloaded. It
     * now redraws immediately from what is already on disk — which for a *narrower* window is the
     * whole answer, and for a wider one is as much of it as has been walked so far. The difference
     * is what this flag is for: the page says a fetch would help and leaves the choice to the
     * reader, rather than spending their rate limit the moment they touch a setting.
     */
    private val _windowChanged = MutableStateFlow(false)
    val windowChanged: StateFlow<Boolean> = _windowChanged.asStateFlow()

    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()

    /**
     * Reaches further back, when the reader has scrolled far enough to mean it.
     *
     * Deliberately driven by scrolling rather than by opening Home. Most people never reach the
     * bottom of the feed, and walking the whole history for them would spend their share of an
     * anonymous rate limit on commits they will not look at — and spend it before the part they
     * will look at can be refreshed. Someone at the end of the list has asked, as plainly as
     * scrolling can ask.
     *
     * Each call fetches a few pages and returns, so the rail grows in steps while the reader keeps
     * scrolling rather than freezing until the whole history has landed. The count and the
     * contributor scoreboard are recomputed from the same reload, which is what makes the stats
     * settle as chunks arrive instead of all at the end.
     */
    fun loadMoreHistory() {
        if (_loadingHistory.value || !_feed.value.hasMoreHistory) return
        _loadingHistory.value = true
        viewModelScope.launch {
            val added = runCatching { github.backfill() }.getOrDefault(0)
            // Reads from disk: the pages just walked are already in the archive, and going back to
            // GitHub here would spend a request to be told what we have just been told. The reload
            // happens even when nothing was added, so that a walk which ended by finding no new
            // commits can clear the invitation to keep scrolling.
            _feed.update { github.load(GitHubRepository.Freshness.Cached) }
            if (added == 0) _exhausted.value = true
            _loadingHistory.value = false
        }
    }

    /**
     * True once a walk came back empty-handed for a reason we cannot distinguish from failure.
     *
     * A refused request and a finished history look the same from here, and retrying a refused one
     * on every scroll would hammer a rate limit that is already exhausted. This stops the automatic
     * retries for the rest of the session; the foot of the feed stays tappable, so a reader who
     * knows they are back online can ask again.
     */
    private val _exhausted = MutableStateFlow(false)
    val historyStalled: StateFlow<Boolean> = _exhausted.asStateFlow()

    fun retryHistory() {
        _exhausted.value = false
        loadMoreHistory()
    }

    companion object {
        /** How often opening Home actually goes and checks GitHub. */
        private const val REVALIDATE_PROBABILITY = 0.2f

        val Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        ServiceLocator.daemon,
                        ServiceLocator.github,
                        ServiceLocator.githubAuth,
                    )
                        as T
            }
    }
}
