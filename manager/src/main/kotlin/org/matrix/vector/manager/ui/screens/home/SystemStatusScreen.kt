package org.matrix.vector.manager.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.model.XposedApi
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.show
import kotlinx.coroutines.launch
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * What the legacy home screen's information card was, done properly.
 *
 * Every row is copyable, and a row that reports a *problem* carries the explanation with it rather
 * than just a red word — the user of a root framework needs to know what broke and what it costs
 * them, not merely that something did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemStatusScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val device = viewModel.device
    val context = LocalContext.current
    val statusNotification by viewModel.statusNotification.collectAsStateWithLifecycle()
    val hiddenIcon by viewModel.hiddenIcon.collectAsStateWithLifecycle()

    val rows = buildRows(status, device, context)
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)

    Scaffold(
        snackbarHost = { VectorSnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_status)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            copy(context, rows.joinToString("\n") { "${it.first}: ${it.second}" })
                            scope.launch { snackbars.show(copied, SnackbarTone.Success) }
                        }
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy_all),
                        )
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status.issues.isNotEmpty()) {
                items(status.issues, key = { it.name }) { issue -> IssueCard(issue) }
                item { Spacer(Modifier.height(4.dp)) }
            }
            items(rows, key = { it.first }) { (label, value) -> InfoRow(label, value) }

            // Framework behaviour, set from the screen that reports on the framework.
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
            }
            item {
                FrameworkToggle(
                    title = stringResource(R.string.status_notification),
                    subtitle = stringResource(R.string.status_notification_summary),
                    checked = statusNotification,
                    onCheckedChange = viewModel::setStatusNotification,
                )
            }
            item {
                FrameworkToggle(
                    title = stringResource(R.string.hidden_icon),
                    subtitle = stringResource(R.string.hidden_icon_summary),
                    checked = hiddenIcon,
                    onCheckedChange = viewModel::setForcedLauncherIcons,
                )
            }
        }
    }
}

@Composable
private fun IssueCard(issue: HealthIssue) {
    val (title, summary) =
        when (issue) {
            HealthIssue.SepolicyNotLoaded ->
                R.string.issue_sepolicy_title to R.string.issue_sepolicy_summary
            HealthIssue.SystemServerNotInjected ->
                R.string.issue_system_server_title to R.string.issue_system_server_summary
            HealthIssue.Dex2oatWrapperBroken ->
                R.string.issue_dex2oat_title to R.string.issue_dex2oat_summary
        }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = VectorMono, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun buildRows(
    status: FrameworkStatus,
    device: DeviceInfo,
    context: Context,
): List<Pair<String, String>> {
    val unknown = "—"
    return listOf(
        stringResource(R.string.info_framework_version) to
            buildString {
                append(status.versionLabel ?: unknown)
                // The exact build, not just its number. Two builds share a version code whenever
                // they sit at the same depth on different branches, and a working tree with
                // uncommitted changes says so — which is exactly what a bug report needs and what
                // a screenshot of this page could not previously give.
                status.commit?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
            },
        // Named separately from the framework, because they are flashed separately and are not
        // always the same build. When these two lines disagree, that is the answer to a whole
        // class of "it behaves oddly" reports.
        stringResource(R.string.info_manager_version) to
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  ·  ${BuildConfig.VERSION_HASH}",
        // Named by which scale the number is on. The two share a field and nothing else: 93 is a
        // legacy Xposed API, 101 is a libxposed one, and calling both "Xposed API" is how a reader
        // ends up comparing versions that were never comparable.
        stringResource(
            if (status.apiVersion?.let { XposedApi.isLibxposed(it) } == true)
                R.string.info_api_version_libxposed
            else R.string.info_api_version
        ) to (status.apiVersion?.toString() ?: unknown),
        stringResource(R.string.info_manager_package) to context.packageName,
        stringResource(R.string.info_selinux) to
            stringResource(
                if (status.sepolicyLoaded) R.string.info_loaded else R.string.info_not_loaded
            ),
        stringResource(R.string.info_system_server) to
            stringResource(
                if (status.systemServerInjected) R.string.info_injected
                else R.string.info_not_injected
            ),
        stringResource(R.string.info_dex2oat) to dex2oatLabel(status.dex2oatCompatibility),
        stringResource(R.string.info_android) to
            "${device.androidRelease} (API ${device.sdkInt})",
        stringResource(R.string.info_device) to device.device,
        stringResource(R.string.info_abi) to device.abi,
    )
}

@Composable
private fun dex2oatLabel(compatibility: Int): String =
    when (compatibility) {
        ILSPManagerService.DEX2OAT_OK -> stringResource(R.string.info_supported)
        ILSPManagerService.DEX2OAT_CRASHED -> "crashed"
        ILSPManagerService.DEX2OAT_MOUNT_FAILED -> "mount failed"
        ILSPManagerService.DEX2OAT_SELINUX_PERMISSIVE -> "SELinux permissive"
        ILSPManagerService.DEX2OAT_SEPOLICY_INCORRECT -> "SEPolicy incorrect"
        else -> stringResource(R.string.info_unsupported)
    }

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(BuildConfig.MANAGER_PACKAGE_NAME, text))
}

@Composable
private fun FrameworkToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
