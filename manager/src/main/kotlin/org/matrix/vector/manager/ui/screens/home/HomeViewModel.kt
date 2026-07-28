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
import kotlinx.coroutines.flow.combine
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
    }

    private suspend fun refreshStatus(service: ILSPManagerService?) {
        if (service == null || !daemon.isAlive) {
            _status.value = FrameworkStatus(state = FrameworkState.Inactive)
            return
        }

        val versionName = daemon.getXposedVersionName().getOrNull()
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
                versionName = versionName,
                versionCode = versionCode,
                apiVersion = api,
                issues = issues,
                dex2oatCompatibility = dex2oat,
                sepolicyLoaded = sepolicy,
                systemServerInjected = systemServer,
            )

        if (versionCode > 0) {
            viewModelScope.launch { ServiceLocator.frameworkUpdates.refresh(versionCode) }
        }
    }

    /**
     * The rail, laid out: commits with their elapsed-time gaps, month boundaries, named silences,
     * and the marker showing where the reader's own build sits in the history.
     */
    val feedItems: StateFlow<List<org.matrix.vector.manager.data.github.FeedItem>> =
        combine(_feed, _status) { feed, status ->
                // The framework's version when the daemon is up, otherwise this manager's own.
                // Both are `git rev-list --count` on the same repository, so either locates a
                // build on the timeline correctly — and without the fallback the marker would
                // simply never appear for anyone running the manager standalone.
                val installed =
                    if (status.versionCode > 0) status.versionCode
                    else org.matrix.vector.manager.BuildConfig.VERSION_CODE.toLong()
                org.matrix.vector.manager.data.github.FeedLayout.build(feed, installed)
            }
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

    private val _hiddenIcon = MutableStateFlow(false)
    val hiddenIcon: StateFlow<Boolean> = _hiddenIcon.asStateFlow()

    private suspend fun refreshToggles() {
        _statusNotification.value = daemon.enableStatusNotification().getOrDefault(false)
    }

    fun setStatusNotification(enabled: Boolean) {
        viewModelScope.launch {
            daemon.setEnableStatusNotification(enabled).onSuccess {
                _statusNotification.value = enabled
            }
        }
    }

    fun setHiddenIcon(hidden: Boolean) {
        viewModelScope.launch {
            // The old implementation wrote the inverse of what it read — it set the daemon flag
            // from one source of truth and read its state back from Settings.Global — so the
            // switch could show a state the framework did not hold. It now reports only what it
            // successfully wrote.
            daemon.setHiddenIcon(hidden).onSuccess { _hiddenIcon.value = hidden }
        }
    }

    fun refreshFeed(freshness: GitHubRepository.Freshness) {
        viewModelScope.launch {
            _refreshing.value = true
            _feed.update { github.load(freshness) }
            _refreshing.value = false
        }
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
