package org.matrix.vector.manager.ui.screens.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.log.CrashRecorder
import org.matrix.vector.manager.data.log.CrashReport
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.StackTrace
import org.matrix.vector.manager.ui.components.stackTraceItems
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.copyToClipboard
import org.matrix.vector.manager.ui.components.show

/**
 * The newest crash, read as a list rather than as a wall of text.
 *
 * The trace itself is [StackTrace]'s doing, and the reasoning about how it is laid out lives
 * there; this screen is the header above it and the copy action beside it.
 *
 * The record is read here rather than passed through the route, because the process is quite likely
 * to have died since the card was drawn — that is, after all, the subject.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashTraceScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val report = remember { CrashRecorder.newest(context) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val frameCopied = stringResource(R.string.crash_frame_copied)

    Scaffold(
        snackbarHost = { VectorSnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crash_trace)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // Every record, not the one on screen. The screen shows the newest because
                    // that is the one being asked about, but a crash loop writes several and a
                    // maintainer wants all of them; and this stays enabled when the newest could
                    // not be parsed, since a record we failed to read is exactly the one worth
                    // getting off the device by hand.
                    IconButton(
                        onClick = {
                            copyToClipboard(context, CrashRecorder.read(context).orEmpty())
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
        },
    ) { padding ->
        if (report == null || report.sections.isEmpty()) {
            Text(
                stringResource(R.string.crash_unreadable),
                modifier = Modifier.padding(padding).padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "when") {
                Text(
                    crashWhen(report),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    report.build,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            stackTraceItems(report.sections) { frame ->
                copyToClipboard(context, frame.line)
                scope.launch { snackbars.show(frameCopied, SnackbarTone.Success) }
            }
        }
    }
}

/**
 * When it happened, and on which thread — shared with the card on the status screen.
 *
 * The thread is dropped rather than left blank when the record does not name one. Only a record
 * written before the header carried a thread is in that state, and it outlives the update that
 * changed the format, since the crashes are kept in the cache directory.
 */
@Composable
internal fun crashWhen(report: CrashReport): String =
    if (report.thread.isEmpty()) report.at
    else stringResource(R.string.crash_when_value, report.at, report.thread)
