package org.matrix.vector.manager.ui.screens.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matrix.vector.manager.data.model.OnlineModule
import org.matrix.vector.manager.data.model.Release
import org.matrix.vector.manager.data.model.ReleaseAsset
import org.matrix.vector.manager.data.model.RepoVersion
import org.matrix.vector.manager.data.repository.InstallStep
import org.matrix.vector.manager.data.repository.ModuleInstaller
import org.matrix.vector.manager.data.repository.RepoRepository
import org.matrix.vector.manager.data.repository.SettingsRepository

/** How the second, richer fetch is going. The page is readable in all three states. */
enum class DetailFetch {
    Loading,
    Loaded,
    Unavailable,
}

/**
 * Everything the detail page shows.
 *
 * [module] is never null once the catalogue is in memory, because the list entry seeds it. That is
 * the whole design of this screen: the catalogue already carries the description, the summary, the
 * scope, the collaborators and the newest release *with its APK*, so the page can paint — and be
 * installed from — before any request is made, and a failed request costs the README rather than
 * the page.
 */
data class RepoDetailsState(
    val module: OnlineModule? = null,
    val releases: List<Release> = emptyList(),
    val installed: RepoVersion? = null,
    val latest: RepoVersion? = null,
    val fetch: DetailFetch = DetailFetch.Loading,
    val channel: StoreChannel = StoreChannel.Stable,
) {
    val upgradable: Boolean
        get() =
            installed != null &&
                latest != null &&
                latest.upgradableOver(installed.versionCode, installed.versionName)
}

class RepoDetailsViewModel(
    private val packageName: String,
    private val repository: RepoRepository,
    private val installer: ModuleInstaller,
    private val settings: SettingsRepository,
    /** Installs outlive this screen; see [install]. */
    private val backgroundScope: CoroutineScope,
) : ViewModel() {

    private val _detail = MutableStateFlow<OnlineModule?>(null)
    private val _fetch = MutableStateFlow(DetailFetch.Loading)

    val installState: StateFlow<InstallStep> = installer.state

    val state: StateFlow<RepoDetailsState> =
        combine(
                repository.catalog,
                _detail,
                _fetch,
                repository.installedVersions,
                settings.updateChannel,
            ) { catalog, detail, fetch, installed, channelPreference ->
                val seed = catalog.modules.firstOrNull { it.name == packageName }
                val module = detail ?: seed
                val channel = StoreChannel.of(channelPreference)
                RepoDetailsState(
                    module = module,
                    releases = releasesFor(module, channel),
                    installed = installed[packageName],
                    latest = latestFor(module, channel),
                    fetch = fetch,
                    channel = channel,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepoDetailsState())

    init {
        fetchDetails()
    }

    fun fetchDetails() {
        viewModelScope.launch {
            _fetch.value = DetailFetch.Loading
            val fetched = repository.details(packageName)
            // A failure is not an error screen. The seeded entry is still on display; all that is
            // missing is the README and the older releases, and the page says so quietly.
            _detail.value = fetched ?: _detail.value
            _fetch.value = if (fetched != null) DetailFetch.Loaded else DetailFetch.Unavailable
        }
    }

    /**
     * Downloads and installs [asset].
     *
     * Deliberately **not** on `viewModelScope`. Navigating back would cancel the transfer halfway
     * through, and the user has already consented to this install — leaving the screen is not a
     * change of mind. The installer's state is a single shared flow, so coming back re-attaches to
     * the progress that kept running.
     */
    fun install(asset: ReleaseAsset) {
        backgroundScope.launch {
            if (installer.install(packageName, asset)) repository.refreshInstalled()
        }
    }

    fun acknowledgeInstall() = installer.acknowledge()

    /**
     * Which releases belong to the current channel.
     *
     * Resolved by [releasesOn] rather than here, so that what this tab lists, what the update badge
     * in the Store list compares against, and what the install bar actually downloads are one rule
     * with one implementation. They were three, and on the prerelease channel they disagreed.
     */
    private fun releasesFor(module: OnlineModule?, channel: StoreChannel): List<Release> =
        module?.releasesOn(channel).orEmpty()

    private fun latestFor(module: OnlineModule?, channel: StoreChannel): RepoVersion? =
        module?.latestOn(channel)
}
