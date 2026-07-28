package org.matrix.vector.manager.ui.components

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.theme.VectorMono

/** What a long press did, and how it went. */
data class PackageActionResult(
    val messageRes: Int,
    val argument: String? = null,
    val tone: SnackbarTone = SnackbarTone.Neutral,
)

/**
 * The long-press sheet for a package, whether it is a module or an app in a module's scope.
 *
 * A sheet rather than a dropdown menu, for two reasons. It can say *which* package it is about — a
 * menu that floats over a list gives no way to tell whether it belongs to the row under your thumb
 * or the one above it, which matters a great deal when one of the actions is "uninstall". And it
 * has room to explain the action that needs explaining, instead of offering a bare verb.
 *
 * Everything here needs the daemon, because the manager has no privilege of its own — it runs as
 * `com.android.shell` and cannot force-stop or uninstall anything itself. Each action is a Binder
 * call, so each reports back rather than assuming it worked.
 *
 * **Re-optimize is the one that is not obvious.** ART inlines small methods into their callers
 * during ahead-of-time compilation, and an inlined method can no longer be hooked — so a module
 * that works on one device silently does nothing on another that happened to compile the target
 * more aggressively. Re-optimizing the app clears that, and it is the first thing to try when a
 * hook "just doesn't fire". It is slow and it is per-app, which is why it belongs on a long press
 * rather than in a settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageActionSheet(
    packageName: String,
    userId: Int,
    appName: String,
    applicationInfo: ApplicationInfo,
    isModule: Boolean,
    onDismiss: () -> Unit,
    onResult: (PackageActionResult) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val daemon = ServiceLocator.daemon
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun finish(block: suspend () -> PackageActionResult) {
        onDismiss()
        scope.launch { onResult(block()) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
LocalizedOverlay {

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(applicationInfo = applicationInfo, contentDescription = null, size = 44.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = packageName,
                    style = VectorMono,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(4.dp))

        ActionRow(
            icon = Icons.AutoMirrored.Rounded.Launch,
            title = stringResource(R.string.action_launch),
        ) {
            finish {
                // The launcher activity has to be resolved *as that user* — the manager's own
                // package manager cannot see another profile's activities.
                val intent =
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(packageName)
                val resolved =
                    daemon.queryIntentActivitiesAsUser(intent, 0, userId).getOrDefault(emptyList())
                val target = resolved.firstOrNull()
                if (target == null) {
                    PackageActionResult(R.string.action_no_launcher, tone = SnackbarTone.Failure)
                } else {
                    val launch =
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(target.activityInfo.packageName, target.activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    daemon.startActivityAsUserWithFeature(launch, userId)
                    PackageActionResult(R.string.action_launched)
                }
            }
        }

        ActionRow(icon = Icons.Rounded.Info, title = stringResource(R.string.action_app_info)) {
            finish {
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                daemon.startActivityAsUserWithFeature(intent, userId)
                PackageActionResult(R.string.action_opened_info)
            }
        }

        ActionRow(icon = Icons.Rounded.Stop, title = stringResource(R.string.action_force_stop)) {
            finish {
                daemon.forceStopPackage(packageName, userId)
                PackageActionResult(
                    R.string.action_force_stopped,
                    appName,
                    tone = SnackbarTone.Success,
                )
            }
        }

        ActionRow(
            icon = Icons.Rounded.Bolt,
            title = stringResource(R.string.action_optimize),
            subtitle = stringResource(R.string.action_optimize_summary),
            tint = colors.primary,
        ) {
            finish {
                // Slow — this recompiles the app — so the caller is told it started and told
                // again when it finishes.
                onResult(
                    PackageActionResult(
                        R.string.action_optimizing,
                        appName,
                        tone = SnackbarTone.Working,
                    )
                )
                val ok = daemon.optimizePackage(packageName).getOrDefault(false)
                PackageActionResult(
                    if (ok) R.string.action_optimized else R.string.action_optimize_failed,
                    appName,
                    tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                )
            }
        }

        if (isModule) {
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            ActionRow(
                icon = Icons.Rounded.DeleteOutline,
                title = stringResource(R.string.action_uninstall),
                tint = colors.error,
            ) {
                finish {
                    val ok = daemon.uninstallPackage(packageName, userId).getOrDefault(false)
                    PackageActionResult(
                        if (ok) R.string.action_uninstalled else R.string.action_uninstall_failed,
                        appName,
                        tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
}

/**
 * One action, with its icon in a tinted disc.
 *
 * The disc is what lets a destructive action look destructive: an error-red glyph on a bare row is
 * easy to miss, the same glyph on a red disc is not.
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = tint ?: colors.onSurfaceVariant

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tint == colors.error) colors.error else colors.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
