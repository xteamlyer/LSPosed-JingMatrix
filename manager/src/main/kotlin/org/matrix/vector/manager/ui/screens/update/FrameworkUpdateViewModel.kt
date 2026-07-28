package org.matrix.vector.manager.ui.screens.update

import org.matrix.vector.manager.data.github.ZipVariant
import org.matrix.vector.manager.data.github.CanaryArtifact
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.data.repository.FlashStep
import org.matrix.vector.manager.data.repository.FrameworkUpdateState
import org.matrix.vector.manager.di.ServiceLocator

/** Which root implementation is in charge, and whether it can be flashed through. */
data class RootState(val code: Int = ILSPManagerService.ROOT_NONE, val version: String? = null) {

    val canFlash: Boolean
        get() =
            code == ILSPManagerService.ROOT_MAGISK ||
                code == ILSPManagerService.ROOT_KERNELSU ||
                code == ILSPManagerService.ROOT_APATCH

    /**
     * The sentence to show when flashing is not possible.
     *
     * Null when it is, and three different strings when it is not — "nothing installed", "too old"
     * and "two of them" need three different actions from the reader, and collapsing them into one
     * "unsupported" would tell someone with two root managers to go install a root manager.
     */
    @androidx.compose.runtime.Composable
    fun label(): String? =
        when (code) {
            ILSPManagerService.ROOT_TOO_OLD ->
                androidx.compose.ui.res.stringResource(
                    org.matrix.vector.manager.R.string.update_root_too_old
                )
            ILSPManagerService.ROOT_MULTIPLE ->
                androidx.compose.ui.res.stringResource(
                    org.matrix.vector.manager.R.string.update_root_multiple
                )
            ILSPManagerService.ROOT_NONE ->
                androidx.compose.ui.res.stringResource(
                    org.matrix.vector.manager.R.string.update_no_root
                )
            else -> null
        }
}

class FrameworkUpdateViewModel : ViewModel() {

    private val daemon = ServiceLocator.daemon
    private val updates = ServiceLocator.frameworkUpdates
    private val installer = ServiceLocator.frameworkInstaller

    val update: StateFlow<FrameworkUpdateState> = updates.state

    val flash: StateFlow<FlashStep> = installer.state

    private val settings = ServiceLocator.settings

    /**
     * The zip the user has chosen, or the release's own default.
     *
     * Falls back rather than refusing when the remembered variant is not in this release: a
     * release that published only one build must still be installable by someone whose last
     * choice was the other one.
     */
    val chosenZip: StateFlow<CanaryArtifact?> =
        combine(update, settings.updateVariant) { state, variant ->
                val zips = state.available?.zips.orEmpty()
                zips.firstOrNull { it.variant.key == variant } ?: state.available?.defaultZip
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun chooseVariant(variant: ZipVariant) = settings.setUpdateVariant(variant.key)

    val lines: StateFlow<List<String>> =
        installer.lines.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _root = MutableStateFlow(RootState())
    val root: StateFlow<RootState> = _root.asStateFlow()

    init {
        viewModelScope.launch {
            val code = daemon.getRootImplementation().getOrNull() ?: ILSPManagerService.ROOT_NONE
            val version = daemon.getRootImplementationVersion().getOrNull()
            _root.value = RootState(code, version)
        }
        viewModelScope.launch {
            val installed = daemon.getXposedVersionCode().getOrNull() ?: 0L
            updates.refresh(installed)
        }
    }

    fun flash() {
        val zip = chosenZip.value ?: return
        val url = zip.downloadUrl ?: return
        viewModelScope.launch { installer.flash(url, zip.sizeInBytes, zip.name) }
    }

    suspend fun reboot() {
        daemon.reboot()
    }
}
