package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient

/**
 * The single source of truth for which modules are enabled.
 *
 * It observes the binder rather than being poked once at construction. That ordering was the
 * previous bug: the repository fired its first fetch from an `init` block, which ran before any
 * daemon connection existed, so it always failed and was never retried — the enabled set stayed
 * empty for the life of the process.
 */
class ModuleRepository(
    private val daemonClient: DaemonClient,
    private val scope: CoroutineScope,
) {

    private val _enabledModulesState = MutableStateFlow<Set<String>>(emptySet())
    val enabledModulesState: StateFlow<Set<String>> = _enabledModulesState.asStateFlow()

    init {
        scope.launch {
            // Re-reads whenever a binder arrives, including a reconnect.
            ServiceLocator.service.collect { service ->
                if (service == null) _enabledModulesState.update { emptySet() } else refresh()
            }
        }
    }

    fun refresh() {
        scope.launch {
            daemonClient
                .getEnabledModules()
                .onSuccess { enabled -> _enabledModulesState.update { enabled.toSet() } }
        }
    }

    /**
     * Asks the daemon to enable or disable a module, and reports whether it agreed.
     *
     * The local set is only updated when the daemon confirms, so the switch can never show a state
     * the framework does not actually hold. Callers surface the `false` — silently snapping the
     * control back leaves the user with no idea what happened.
     */
    suspend fun toggleModule(packageName: String, enable: Boolean): Boolean {
        val accepted = daemonClient.setModuleEnabled(packageName, enable).getOrDefault(false)
        if (!accepted) return false

        _enabledModulesState.update { current ->
            if (enable) current + packageName else current - packageName
        }
        return true
    }
}
