package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.vector.manager.data.github.FrameworkRelease
import org.matrix.vector.manager.data.github.GitHubRepository

/**
 * Whether a newer build of the framework exists, and which one this reader may be offered.
 *
 * **The channel is derived, not configured.** A preference would be a second source of truth about
 * something the device can simply be asked: someone who flashed a canary once and never changed a
 * setting would keep being offered canaries, and someone on a release build who toggled the setting
 * out of curiosity would be offered nightlies. Neither is what they are running.
 *
 * A build is a canary if either is true:
 *
 * - a `canary-<versionCode>` release exists for exactly this version code, which is the direct
 *   evidence; or
 * - this version code is *higher than the newest stable release*, which catches the canary that has
 *   aged out of the rolling five prereleases and would otherwise look like a release build. It also
 *   correctly classifies a locally built development copy, which is ahead of everything published.
 *
 * A reader on a release build is only ever offered releases. That is the whole point of the
 * distinction: a nightly is not something to be nudged towards by an app you did not ask for advice.
 */
class FrameworkUpdateRepository(private val github: GitHubRepository) {

    private val _state = MutableStateFlow(FrameworkUpdateState())
    val state: StateFlow<FrameworkUpdateState> = _state.asStateFlow()

    suspend fun refresh(installedVersionCode: Long, freshness: GitHubRepository.Freshness = GitHubRepository.Freshness.Revalidate) {
        if (installedVersionCode <= 0) return
        val releases = github.frameworkReleases(freshness)
        if (releases.isEmpty()) return

        val newestStable = releases.firstOrNull { !it.isCanary }
        val onCanary =
            releases.any { it.isCanary && it.versionCode == installedVersionCode } ||
                (newestStable != null && installedVersionCode > newestStable.versionCode)

        // A canary reader sees whichever is newer; a release reader never sees a canary at all.
        val candidates = if (onCanary) releases else releases.filterNot { it.isCanary }
        val newest = candidates.maxByOrNull { it.versionCode }

        _state.value =
            FrameworkUpdateState(
                loaded = true,
                onCanaryChannel = onCanary,
                installedVersionCode = installedVersionCode,
                available = newest?.takeIf { it.versionCode > installedVersionCode },
            )
    }
}

/**
 * What is known about framework updates right now.
 *
 * [available] is null both when nothing newer exists and before anything has been fetched, which
 * the UI must not confuse — hence [loaded]. Home renders no update mark until the answer is known,
 * rather than flashing "up to date" and then contradicting itself.
 */
data class FrameworkUpdateState(
    val loaded: Boolean = false,
    val onCanaryChannel: Boolean = false,
    val installedVersionCode: Long = 0,
    val available: FrameworkRelease? = null,
) {
    val hasUpdate: Boolean
        get() = available != null
}
