package org.matrix.vector.manager.ui.screens.update

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.repository.FlashStep
import org.matrix.vector.manager.ui.screens.repo.StoreHtmlPane
import org.matrix.vector.manager.ui.theme.VectorLogLine

/**
 * What is in the update, and what happened when it was installed.
 *
 * One screen for both because they are one act with a pause in it: the reader is deciding whether
 * to flash, and then watching the flash. Splitting them would mean navigating away from the notes
 * at the moment they become most relevant — when the installer complains about something.
 *
 * The output pane is the same monospace treatment the Logs panel uses, because it is the same kind
 * of thing and a reader who has seen one should recognise the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameworkUpdateScreen(
    onNavigateBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: FrameworkUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val update by viewModel.update.collectAsStateWithLifecycle()
    val flash by viewModel.flash.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val root by viewModel.root.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val release = update.available

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = release?.title ?: stringResource(R.string.update_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        if (release != null) {
                            Text(
                                text =
                                    stringResource(
                                        if (release.isCanary) R.string.update_channel_canary
                                        else R.string.update_channel_release
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    release?.htmlUrl?.let { url ->
                        IconButton(onClick = { onOpenUrl(url) }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = stringResource(R.string.store_open_release),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            UpdateBar(
                canFlash = release?.zip?.downloadUrl != null && root.canFlash,
                // Null unless root itself is the obstacle; "nothing to install" is not a root
                // problem and must not borrow its sentence.
                rootLabel = root.label(),
                flash = flash,
                onFlash = { viewModel.flash() },
                onReboot = { scope.launch { viewModel.reboot() } },
            )
        },
    ) { padding ->
        // What occupies the pane, decided per step rather than "log as soon as anything starts".
        // A download produces no installer output, so switching at the start of one left a
        // full-height empty box for the whole transfer. The notes are the most relevant thing
        // there is to read while the release they describe is being fetched.
        val showLog = flash !is FlashStep.Idle && (flash !is FlashStep.Downloading || lines.isNotEmpty())

        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!showLog) {
                val html =
                    remember(release?.notesMarkdown) {
                        release?.notesMarkdown?.takeIf { it.isNotBlank() }?.let {
                            releaseMarkdownToHtml(it)
                        }
                    }
                when {
                    html != null ->
                        StoreHtmlPane(
                            html = html,
                            modifier = Modifier.fillMaxSize(),
                            onOpenUrl = onOpenUrl,
                        )
                    release == null -> Empty(stringResource(R.string.update_none))
                    else -> Empty(stringResource(R.string.update_no_notes))
                }
            } else {
                InstallLog(lines, terminal = flash is FlashStep.Done || flash is FlashStep.Failed)
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The installer's output, live.
 *
 * Auto-follows the tail, because the interesting line during a flash is always the newest one, and
 * a reader who has scrolled up is a reader reading something — so it only follows while the list is
 * already at the bottom.
 */
@Composable
private fun InstallLog(lines: List<String>, terminal: Boolean) {
    val state = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        // Only while the tail is already in view. This used to scroll unconditionally, which
        // yanked a reader who had scrolled up to study an earlier line back to the bottom on
        // every single line the installer printed.
        val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= lines.size - 2) state.animateScrollToItem(lines.lastIndex)
    }

    // A flash can end without saying anything: no root implementation, or an installer that could
    // not be started at all. An empty box would read as "still working" at exactly the moment the
    // reader needs to know it stopped.
    if (lines.isEmpty() && terminal) {
        Empty(stringResource(R.string.update_no_output))
        return
    }

    LazyColumn(
        state = state,
        modifier =
            Modifier.fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(lines) { line ->
            Text(
                text = line,
                style = VectorLogLine,
                color = MaterialTheme.colorScheme.onSurface,
                // Installers print progress bars and paths that are wider than any phone; wrapping
                // them turns one line of output into four and makes the log unreadable.
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun UpdateBar(
    canFlash: Boolean,
    rootLabel: String?,
    flash: FlashStep,
    onFlash: () -> Unit,
    onReboot: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        when (flash) {
            is FlashStep.Downloading -> {
                Text(
                    text =
                        stringResource(
                            R.string.update_downloading,
                            formatSize(flash.bytes),
                            formatSize(flash.total),
                        ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (flash.total > 0) {
                    LinearProgressIndicator(
                        progress = { flash.bytes.toFloat() / flash.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            FlashStep.Flashing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.update_flashing),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            FlashStep.Done ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.update_done),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.update_reboot_why),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = onReboot) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.update_reboot))
                    }
                }
            is FlashStep.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.error)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = failureText(flash.code),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onFlash) { Text(stringResource(R.string.retry)) }
                }
            FlashStep.Idle ->
                Column {
                    // Only when root is genuinely the problem. This used to print the
                    // no-root sentence whenever the button was disabled, so a perfectly rooted
                    // device with nothing to install was told it had no root — while the daemon
                    // log on the same device read "Root implementation: KernelSU".
                    if (rootLabel != null) {
                        Text(
                            text = rootLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onFlash,
                        enabled = canFlash,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Rounded.SystemUpdateAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_install))
                    }
                }
        }
    }
}

@Composable
private fun failureText(code: Int): String =
    when (code) {
        ILSPManagerService.INSTALL_NO_ROOT -> stringResource(R.string.update_no_root)
        ILSPManagerService.INSTALL_NOT_EXECUTED -> stringResource(R.string.update_failed_start)
        ILSPManagerService.INSTALL_NO_SUCH_FILE -> stringResource(R.string.update_failed_download)
        else -> stringResource(R.string.update_failed_exit, code)
    }

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f kB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
