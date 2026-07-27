package org.matrix.vector.manager.ui.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.InputChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.theme.VectorLogLine
import org.matrix.vector.manager.data.log.LogLevel
import org.matrix.vector.manager.ui.components.PanelHeader
import org.matrix.vector.manager.ui.components.SearchField
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * The diagnose surface: two log streams, read from the end.
 *
 * Everything expensive about this screen lives in `data/log` — the reader indexes line offsets and
 * materialises at most a couple of thousand rows at a time, so a log of any size opens at the same
 * speed and the pane never holds the file. What is left here is the part that decides whether the
 * screen is any good: a parsed line has a level, a tag and a time, so it can be coloured, filtered
 * and searched instead of dumped, and a tag chip turns "why is this log 4,700 lines of
 * TEESimulator" into one tap.
 *
 * Only the settled page reads. Opening Logs used to index both files whether or not either was
 * visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel(factory = LogsViewModelFactory())) {
    // One pane, one search field, and the source is a control inside it. Two tabs meant two search
    // boxes, two filter states and two scroll positions for what is one question — "what does the
    // log say" — and the answer often has to be looked for in both.
    var currentTab by rememberSaveable { mutableStateOf(LogTab.MODULES) }
    val currentState by viewModel.state(currentTab).collectAsStateWithLifecycle()
    val wordWrap by viewModel.wordWrap.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }

    val saveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri != null) viewModel.saveTo(uri)
        }
    val fileNameTemplate = stringResource(R.string.logs_save_name)
    fun launchSave() {
        saveLauncher.launch(
            String.format(fileNameTemplate, LocalDateTime.now().format(FILE_STAMP))
        )
    }

    val savingLabel = stringResource(R.string.logs_saving)
    val savedLabel = stringResource(R.string.logs_saved)
    val shareLabel = stringResource(R.string.logs_share)
    LaunchedEffect(saveState) {
        when (val s = saveState) {
            is LogSaveState.Saving ->
                snackbars.showSnackbar(savingLabel, duration = SnackbarDuration.Indefinite)
            is LogSaveState.Saved -> {
                snackbars.currentSnackbarData?.dismiss()
                // The document belongs to DocumentsUI, not to us — which is the only reason this
                // can be shared at all. Parasitically the manifest is never installed, so no
                // FileProvider of ours exists at runtime and ACTION_SEND has no content:// URI to
                // hand out. That is also why saving goes through SAF rather than a share sheet.
                val result = snackbars.showSnackbar(savedLabel, actionLabel = shareLabel)
                if (result == SnackbarResult.ActionPerformed) shareZip(context, s.uri)
                viewModel.consumeSaveState()
            }
            is LogSaveState.Failed -> {
                snackbars.currentSnackbarData?.dismiss()
                // Report the daemon's own words. getLogs() can fail for reasons only it knows —
                // a full filesystem, a tombstone it cannot read — and a generic "failed" throws
                // that away.
                snackbars.showSnackbar(
                    if (s.message.isNullOrBlank()) context.getString(R.string.logs_save_failed)
                    else context.getString(R.string.logs_save_failed_reason, s.message)
                )
                viewModel.consumeSaveState()
            }
            LogSaveState.Idle -> Unit
        }
    }

    LaunchedEffect(currentTab) { viewModel.open(currentTab) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // The same header the other two list panels use, so the search field below it does not
            // move when the tab does. It used to be a TopAppBar, which is a different height again.
            PanelHeader(
                title = stringResource(R.string.logs_title),
                modifier =
                    Modifier.partSwipe(currentState) { viewModel.selectPart(currentTab, it) },
                startSubtitle = {
                    WindowCounter(currentState) { viewModel.selectPart(currentTab, it) }
                },
                actions = {
                    // Selected, not shouted. A filled accent with a shadow made a reading
                    // preference look like the most important control on the screen; a quiet
                    // neutral container says pressed-in without competing with anything.
                    FilledIconToggleButton(
                        checked = wordWrap,
                        onCheckedChange = { viewModel.setWordWrap(it) },
                        colors =
                            IconButtonDefaults.filledIconToggleButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor =
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                checkedContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.WrapText,
                            contentDescription = stringResource(R.string.logs_word_wrap),
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.logs_settings),
                        )
                    }
                },
            )
            LogPane(
                tab = currentTab,
                viewModel = viewModel,
                wordWrap = wordWrap,
                onSelectTab = { currentTab = it },
            )
        }
    }

    if (menuOpen) {
        LogSettingsSheet(
            viewModel = viewModel,
            onDismiss = { menuOpen = false },
            onSave = {
                menuOpen = false
                launchSave()
            },
            onRotate = {
                menuOpen = false
                confirmRotate = true
            },
        )
    }

    if (confirmRotate) {
        val rotated = stringResource(R.string.logs_rotate_done)
        val rotateFailed = stringResource(R.string.logs_rotate_failed)
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.logs_rotate_title)) },
            // README principle 3: the dangerous action names its consequence — and here the
            // consequence is not what a delete icon implies. clearLogs() is LogcatMonitor.refresh(),
            // which rotates to a new file rather than truncating.
            text = { Text(stringResource(R.string.logs_rotate_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRotate = false
                        viewModel.rotate(currentTab) { ok ->
                            scope.launch { snackbars.showSnackbar(if (ok) rotated else rotateFailed) }
                        }
                    }
                ) {
                    Text(stringResource(R.string.logs_rotate_confirm))
                }
            },
            // No "save first". Rotating no longer puts anything out of reach: the closed part
            // stays on disk and is a swipe away, so pressing save on the way past was protecting
            // against a loss that does not happen.
            dismissButton = {
                TextButton(onClick = { confirmRotate = false }) {
                    Text(stringResource(R.string.logs_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogPane(
    tab: LogTab,
    viewModel: LogsViewModel,
    wordWrap: Boolean,
    onSelectTab: (LogTab) -> Unit,
) {
    val state by viewModel.state(tab).collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val pan = rememberLogPan()
    val context = LocalContext.current
    var filterOpen by remember { mutableStateOf(false) }

    // The jump buttons float over the list, so the list has to end above them. Measured rather than
    // assumed — README §8 records a hardcoded bottom inset as a bug precisely because a constant
    // stops clearing what it was meant to clear the moment anything about it changes. Both buttons
    // are always present so the height is stable once measured; a container that grew and shrank
    // would move the log under the reader's eye.
    var jumpInset by remember { mutableIntStateOf(0) }
    // Shown whenever the log does not fit, rather than only past a window's worth of lines. A
    // freshly rotated module log is a few hundred lines — far under the window — and still far too
    // long to thumb to the end of, which is where the line everyone opened this screen for lives.
    val showJump by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }

    // The pan extent is a running maximum over the rows measured so far, so it is reset only when
    // the whole reading changes — not while paging, which would snap the offset back mid-scroll.
    LaunchedEffect(wordWrap, state.query) { pan.reset() }

    // Keyed on the inset as well as on the command: the first layout measures the buttons *after*
    // the open-at-the-tail scroll has already run, and without the second pass the newest line —
    // the one line everybody opens this screen to read — sits underneath them.
    LaunchedEffect(state.scroll?.token, jumpInset) {
        val command = state.scroll ?: return@LaunchedEffect
        if (state.rows.isNotEmpty()) {
            listState.scrollToItem(command.position.coerceIn(0, state.rows.lastIndex))
        }
    }

    // Extending the window is driven by where the viewport actually is rather than by a scroll
    // callback, so a fling that overshoots several hundred rows still triggers exactly one step.
    LaunchedEffect(listState, tab) {
        snapshotFlow {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
                    listState.layoutInfo.totalItemsCount,
                )
            }
            .collect { (first, last, total) ->
                if (total > 0) viewModel.onVisibleRows(tab, first, last, total)
            }
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(
            query = state.query.text,
            onQueryChange = { viewModel.setQuery(tab, it) },
            placeholder = stringResource(R.string.logs_search_hint),
            // Identical to the other panels, so the field does not shift when the tab does.
            modifier = Modifier.padding(horizontal = 16.dp),
            trailing = {
                LogSourceToggle(tab = tab, onSelect = onSelectTab)
                IconButton(
                    onClick = {
                        filterOpen = true
                        viewModel.loadFacets(tab)
                    }
                ) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = stringResource(R.string.logs_filter),
                        tint =
                            if (state.query.levels.isNotEmpty() || state.query.tag != null)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            },
        )

        // The active tag is stated once, here, instead of on every line — see the tag column in
        // LogRows, which disappears while this is showing.
        ActiveFilterRow(
            state = state,
            onClearTag = { viewModel.setTag(tab, null) },
            onClearLevel = { viewModel.toggleLevel(tab, it) },
        )


        if (state.droppedLeading > 0) {
            Text(
                stringResource(R.string.logs_dropped, state.droppedLeading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        val scanning = state.status as? LogStatus.Scanning
        if (scanning != null) {
            LinearProgressIndicator(
                progress = { scanning.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            when (val status = state.status) {
                is LogStatus.DaemonUnavailable ->
                    LogEmptyState(
                        Icons.Rounded.CloudOff,
                        stringResource(R.string.logs_state_daemon_title),
                        stringResource(R.string.logs_state_daemon_body),
                    )
                is LogStatus.NoLogFile ->
                    LogEmptyState(
                        Icons.AutoMirrored.Rounded.Article,
                        stringResource(R.string.logs_state_nofile_title),
                        stringResource(R.string.logs_state_nofile_body),
                    )
                is LogStatus.Empty ->
                    LogEmptyState(
                        Icons.AutoMirrored.Rounded.Article,
                        stringResource(R.string.logs_state_empty_title),
                        stringResource(R.string.logs_state_empty_body),
                    )
                is LogStatus.NoMatches ->
                    LogEmptyState(
                        Icons.Rounded.SearchOff,
                        stringResource(R.string.logs_state_nomatches_title),
                        stringResource(R.string.logs_state_nomatches_body),
                    )
                is LogStatus.ReadFailed ->
                    LogEmptyState(
                        Icons.Rounded.WarningAmber,
                        stringResource(R.string.logs_state_failed_title),
                        stringResource(R.string.logs_state_failed_body, status.message ?: ""),
                    )
                is LogStatus.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                else ->
                    LogList(
                        tab = tab,
                        viewModel = viewModel,
                        state = state,
                        listState = listState,
                        pan = pan,
                        wordWrap = wordWrap,
                        showJump = showJump,
                        jumpInset = jumpInset,
                        onJumpInset = { jumpInset = it },
                        onCopy = { copyToClipboard(context, it) },
                    )
            }
        }
    }

    if (filterOpen) {
        LogFilterSheet(
            state = state,
            onDismiss = { filterOpen = false },
            onToggleLevel = { viewModel.toggleLevel(tab, it) },
            onTag = { viewModel.setTag(tab, it) },
            onClear = { viewModel.clearFilter(tab) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogList(
    tab: LogTab,
    viewModel: LogsViewModel,
    state: LogPaneState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    pan: LogPan,
    wordWrap: Boolean,
    showJump: Boolean,
    jumpInset: Int,
    onJumpInset: (Int) -> Unit,
    onCopy: (String) -> Unit,
) {
    // The horizontal gesture goes on the container, not on the list and not on the rows: the list
    // then owns vertical extent exclusively and each row's sideways extent depends only on its own
    // intrinsic width, so nothing is recomputed as the reader scrolls.
    val gesture = panGesture(pan)
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh(tab) },
            modifier = if (wordWrap) Modifier else gesture,
        ) {
            // Text here is selectable the way text anywhere else on the platform is: long press
            // and drag. That is why the rows no longer take the long press for themselves — see
            // LogRows, where copying a whole line moved to a double tap.
            SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        top = 8.dp,
                        bottom = 8.dp + with(density) { if (showJump) jumpInset.toDp() else 0.dp },
                    ),
            ) {
                // Keyed by absolute line number. That is what lets the window be extended upwards
                // without the viewport lurching: the list re-resolves its first visible item by key
                // after rows are inserted above it.
                items(state.rows, key = { it.key }) { row ->
                    LogRowItem(
                        row = row,
                        wordWrap = wordWrap,
                        showTag = state.query.tag == null,
                        pan = pan,
                        query = state.query.text,
                        onTagClick = { viewModel.setTag(tab, it) },
                        onCopy = onCopy,
                    )
                }
            }
            }
        }

        // On a thirty-thousand-line log with no jump affordance the newest line is unreachable in
        // practice, which is the one line everybody opens this screen to read. Both buttons stay
        // put even at an end of the file — hiding one would change the container's height and
        // shift the log under the reader as a side effect of scrolling.
        if (showJump) {
            // Side by side rather than stacked: whatever height these take is height the log
            // cannot use, and one button's worth of dead space at the bottom of every log is
            // already the most this affordance is worth.
            Row(
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .onSizeChanged { onJumpInset(it.height) }
                        .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallFloatingActionButton(onClick = { viewModel.jumpToOldest(tab) }) {
                    Icon(
                        Icons.Rounded.VerticalAlignTop,
                        contentDescription = stringResource(R.string.logs_jump_oldest),
                    )
                }
                SmallFloatingActionButton(onClick = { viewModel.jumpToNewest(tab) }) {
                    Icon(
                        Icons.Rounded.VerticalAlignBottom,
                        contentDescription = stringResource(R.string.logs_jump_newest),
                    )
                }
            }
        }
    }
}



/**
 * Which log is being read, as one button.
 *
 * The verbose log is not a different subject, it is the same one with the framework's own lines
 * left in — module logs plus everything underneath them. So this is a detail control, not a choice
 * between two places: unfold for more, fold for less. A two-segment control spelled out a decision
 * that does not need making, and spent half the search field doing it.
 *
 * Not to be confused with the verbose *logging* switch in the settings sheet. That one tells the
 * daemon whether to write those lines at all; this one only decides which of the two files is on
 * screen.
 */
@Composable
private fun LogSourceToggle(tab: LogTab, onSelect: (LogTab) -> Unit) {
    val verbose = tab == LogTab.VERBOSE
    IconButton(
        onClick = { onSelect(if (verbose) LogTab.MODULES else LogTab.VERBOSE) }
    ) {
        Icon(
            if (verbose) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
            contentDescription =
                stringResource(
                    if (verbose) R.string.logs_source_less else R.string.logs_source_more
                ),
            tint =
                if (verbose) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the log is currently narrowed to, as chips that undo themselves.
 *
 * A filter that is only visible inside the sheet that set it is a filter people forget they applied
 * and then read a log that is quietly missing most of its lines. Stating the tag here is also what
 * lets every row stop repeating it.
 */
@Composable
private fun ActiveFilterRow(
    state: LogPaneState,
    onClearTag: () -> Unit,
    onClearLevel: (LogLevel) -> Unit,
) {
    val tag = state.query.tag
    if (tag == null && state.query.levels.isEmpty()) return

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tag != null) {
            InputChip(
                selected = true,
                onClick = onClearTag,
                label = { Text(tag, style = VectorLogLine, maxLines = 1) },
                avatar = {
                    Icon(
                        Icons.Rounded.Label,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.logs_filter_clear_tag),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        state.query.levels.sortedBy { it.ordinal }.forEach { level ->
            InputChip(
                selected = true,
                onClick = { onClearLevel(level) },
                label = { Text(level.name, style = MaterialTheme.typography.labelMedium) },
                trailingIcon = {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

/**
 * Everything about the log that is a setting rather than a filter.
 *
 * A half sheet, matching the filter sheet next to it, because these are the same kind of thing:
 * something you open, change, and dismiss. They were a dropdown menu, which could hold two verbs
 * and nothing that needed a switch or a sentence — and the verbose control needs both.
 *
 * The verbose switch shows the value **the daemon reports**, not the one the user picked.
 * `ManagerService.isVerboseLog()` ORs the stored preference with `BuildConfig.DEBUG`, so against a
 * debug daemon it snaps straight back, and a control that visibly refuses to move with no
 * explanation is the same failure as showing one state when another is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSettingsSheet(
    viewModel: LogsViewModel,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onRotate: () -> Unit,
) {
    val enabled by viewModel.verboseEnabled.collectAsStateWithLifecycle()
    val enforced by viewModel.verboseEnforced.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.logs_settings),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )

            ListItem(
                // Never disabled. A daemon that overrides the setting is a reason to *say so*, not
                // a reason to take the control away — and the override is now only possible against
                // an older daemon, since this one reports the stored preference as it stands.
                modifier = Modifier.clickable { viewModel.setVerbose(!enabled) },
                headlineContent = { Text(stringResource(R.string.logs_verbose_switch)) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(R.string.logs_verbose_summary),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (enforced) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.logs_verbose_enforced),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        Icons.Rounded.Visibility,
                        contentDescription = null,
                        // The point of this row is a warning, so it is coloured like one.
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = { viewModel.setVerbose(it) })
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            ListItem(
                modifier = Modifier.clickable(onClick = onSave),
                headlineContent = { Text(stringResource(R.string.logs_save)) },
                supportingContent = { Text(stringResource(R.string.logs_save_summary)) },
                leadingContent = { Icon(Icons.Rounded.Save, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onRotate),
                headlineContent = { Text(stringResource(R.string.logs_rotate)) },
                supportingContent = { Text(stringResource(R.string.logs_rotate_summary)) },
                leadingContent = {
                    Icon(
                        Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}


/**
 * Drag the title block sideways to move between rotated parts.
 *
 * The chevrons beside the counter remain the discoverable way to do it; this is the fast one, and
 * it is safe here in a way it was not over the log itself: nothing else in the app bar wants a
 * horizontal drag, so there is no arbitration, no threshold tuning against another gesture and no
 * band of the screen where it does or does not apply.
 *
 * Dragging leftwards moves to the newer part, the way a carousel does.
 */
@Composable
private fun Modifier.partSwipe(state: LogPaneState, onSelectPart: (Int) -> Unit): Modifier {
    if (state.parts.size < 2) return this
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var travelled by remember(state.partIndex) { mutableFloatStateOf(0f) }

    val scroll = rememberScrollableState { delta ->
        travelled += delta
        when {
            travelled <= -threshold && state.partIndex < state.parts.lastIndex -> {
                onSelectPart(state.partIndex + 1)
                travelled = 0f
            }
            travelled >= threshold && state.partIndex > 0 -> {
                onSelectPart(state.partIndex - 1)
                travelled = 0f
            }
        }
        delta
    }
    return this.scrollable(scroll, Orientation.Horizontal)
}

/**
 * Which lines are on screen, and which rotated part they come from.
 *
 * This line was already the only place that says where you are in the file, so it is also where you
 * move between files. A sideways swipe was tried first and was the wrong gesture: it competed with
 * the row-level pan, it had to be fenced into a corner of the screen and into one scroll position
 * to stop it firing by accident, and after all that it was still invisible until it happened. A
 * pair of chevrons on the counter is none of those things — it says how many parts there are, which
 * one you are on, and it cannot be triggered by a drag meant for something else.
 *
 * The range follows the **viewport**, not the loaded window. "Which lines am I looking at" is what a
 * line counter is read to answer; the window's bounds answer a question about the reader's paging
 * strategy, which is nobody's business but the reader's.
 */
@Composable
private fun WindowCounter(state: LogPaneState, onSelectPart: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text =
        when {
            state.status is LogStatus.Ready || state.status is LogStatus.Scanning ->
                if (state.filtered)
                    pluralStringResource(
                        R.plurals.logs_matches,
                        state.visibleLines,
                        state.visibleLines,
                    )
                else
                    stringResource(
                        R.string.logs_window,
                        state.visibleFirst.coerceAtLeast(1),
                        state.visibleLast.coerceAtLeast(state.visibleFirst),
                        state.totalLines,
                    )
            state.status is LogStatus.Loading -> stringResource(R.string.logs_loading)
            else -> null
        }

    val parts = state.parts.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (parts > 1) {
            // Older is to the left, the way earlier is to the left of later everywhere else.
            PartStep(
                icon = Icons.Rounded.ChevronLeft,
                descriptionRes = R.string.logs_part_older,
                enabled = state.partIndex > 0,
                onClick = { onSelectPart(state.partIndex - 1) },
            )
        }
        if (text != null) {
            Text(
                text =
                    if (parts > 1)
                        "$text  ·  " + stringResource(R.string.logs_part, state.partIndex + 1, parts)
                    else text,
                style = VectorMono,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (parts > 1) {
            PartStep(
                icon = Icons.Rounded.ChevronRight,
                descriptionRes = R.string.logs_part_newer,
                enabled = state.partIndex < parts - 1,
                onClick = { onSelectPart(state.partIndex + 1) },
            )
        }
    }
}

/** One step between parts. Dimmed rather than removed at an end, so the row never reflows. */
@Composable
private fun PartStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    descriptionRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(28.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(20.dp),
            tint =
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}

/**
 * The four nothing-to-show states, rendered as four different things.
 *
 * They used to be four strings pushed into the log list itself, so "the daemon is down" arrived
 * looking exactly like a line the daemon had written.
 */
@Composable
private fun LogEmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Levels and tags the file actually contains, with their counts. Never a hardcoded list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFilterSheet(
    state: LogPaneState,
    onDismiss: () -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onTag: (String?) -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.logs_filter_levels),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.logs_filter_clear))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogLevel.selectable.forEach { level ->
                    val count = state.facets?.levels?.get(level) ?: 0
                    FilterChip(
                        selected = level in state.query.levels,
                        onClick = { onToggleLevel(level) },
                        enabled = state.facets == null || count > 0,
                        label = { Text(level.char.toString(), style = VectorMono) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.logs_filter_tags),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            val facets = state.facets
            if (facets == null) {
                Text(
                    stringResource(R.string.logs_filter_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(facets.tags, key = { it.first }) { (tag, count) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = state.query.tag == tag,
                                onClick = { onTag(tag) },
                                label = { Text(tag, style = VectorMono) },
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                count.toString(),
                                style = VectorMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(BuildConfig.MANAGER_PACKAGE_NAME, text))
}

private fun shareZip(context: Context, uri: Uri) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
