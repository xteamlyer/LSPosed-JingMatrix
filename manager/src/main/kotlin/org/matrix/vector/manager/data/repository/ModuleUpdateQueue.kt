package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.vector.manager.data.model.ReleaseAsset

/**
 * Several module updates, installed one after another.
 *
 * One at a time is not a simplification. `PackageInstaller` sessions are independent, but a phone
 * asked to install four APKs at once spends the whole time contending for the same disk and, in
 * standalone mode, stacks four system confirmation dialogs on top of each other in an order nobody
 * chose. Sequential is also what makes the progress line truthful: there is exactly one download to
 * report on at any moment, which is what [ModuleInstaller] already models.
 *
 * It lives outside the sheet that starts it, on the application scope, because updating four
 * modules takes longer than anyone will keep a bottom sheet open. Closing the sheet is not a
 * cancellation, and reopening it finds the run where it left off.
 */
class ModuleUpdateQueue(
    private val installer: ModuleInstaller,
    private val store: RepoRepository,
    private val modules: ModuleRepository,
    private val scope: CoroutineScope,
) {

    /** One module to update, resolved before the run starts so nothing is looked up mid-flight. */
    data class Item(val packageName: String, val title: String, val asset: ReleaseAsset)

    data class State(
        val queued: List<Item> = emptyList(),
        /** What is being installed right now; null between items and when nothing is running. */
        val current: Item? = null,
        val done: Set<String> = emptySet(),
        val failed: Set<String> = emptySet(),
        val running: Boolean = false,
    ) {
        val total: Int
            get() = queued.size

        val finished: Int
            get() = done.size + failed.size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Starts a run, unless one is already going.
     *
     * A second call during a run is ignored rather than queued behind it. The only way to reach one
     * is to press a button that reports a run in progress, so honouring it would mean acting on a
     * decision made against a screen that had already moved on.
     */
    fun start(items: List<Item>) {
        if (items.isEmpty() || _state.value.running) return
        _state.value = State(queued = items, running = true)
        job =
            scope.launch {
                for (item in items) {
                    _state.update { it.copy(current = item) }
                    val ok = runCatching { installer.install(item.packageName, item.asset) }
                    _state.update {
                        if (ok.getOrDefault(false)) it.copy(done = it.done + item.packageName)
                        else it.copy(failed = it.failed + item.packageName)
                    }
                }
                _state.update { it.copy(current = null, running = false) }
                // Once, at the end, rather than after each install: every version read comes from
                // one daemon call over every installed package, and paying that four times to
                // watch four badges settle a second earlier each is not a trade worth making.
                store.refreshInstalled()
                // Told rather than overheard. A replaced package does broadcast, and the manager
                // does listen — but the broadcast is the system's to deliver and this process is a
                // guest in `com.android.shell`, so a list that only refreshes when the broadcast
                // arrives is a list that sometimes does not. This is the one install path the app
                // itself performed; there is no reason for it to learn about it second-hand.
                modules.notePackagesChanged()
            }
    }

    /**
     * Clears a finished run.
     *
     * Only when it has finished — there is no cancel here on purpose. An install that has reached
     * the platform cannot be recalled, and a stop button that could not stop the thing in front of
     * you would be a lie about what the app controls.
     */
    fun acknowledge() {
        if (_state.value.running) return
        _state.value = State()
        installer.acknowledge()
    }
}
