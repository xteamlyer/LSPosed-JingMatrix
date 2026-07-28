package org.matrix.vector.manager.ui.screens.modules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.matrix.vector.manager.ui.components.VectorAlertDialog
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.model.InstalledModule
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.components.AppIcon
import org.matrix.vector.manager.ui.components.PackageActionSheet
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.show
import org.matrix.vector.manager.ui.components.PackageActionResult
import org.matrix.vector.manager.ui.components.PanelHeader
import org.matrix.vector.manager.ui.components.SearchField
import org.matrix.vector.manager.ui.theme.VectorMono
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

class ModulesViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ModulesViewModel(
            ServiceLocator.daemon,
            ServiceLocator.modules,
            ServiceLocator.context.packageManager,
        )
            as T
}

/**
 * The module list.
 *
 * Its first job is to answer *what is running*, so enabled modules sort to the top and a disabled
 * row is visibly dimmed on a plainer surface — the state is legible from the shape of the list
 * itself, not only from the position of a switch. The header says the same thing numerically, and
 * the filter turns it into a question that can be asked directly.
 *
 * Each row also carries the module's **reach**: how many apps it is scoped to. That is the fact
 * behind most trips into the scope editor, so showing it here saves the trip — and a module that
 * is enabled but scoped to nothing, which does nothing at all while looking like it works, is
 * called out in the error colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    onModuleClick: (packageName: String, userId: Int) -> Unit,
    viewModel: ModulesViewModel = viewModel(factory = ModulesViewModelFactory()),
) {
    val tabs by viewModel.userModulesTabs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val toggleFailed by viewModel.toggleFailed.collectAsStateWithLifecycle()
    val daemonAvailable by viewModel.daemonAvailable.collectAsStateWithLifecycle()

    val selection by viewModel.selection.collectAsStateWithLifecycle()
    var confirmUninstall by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val actionScope = rememberCoroutineScope()

    /** One sentence for a batch: how many worked, and how many did not if any did not. */
    fun batchResult(messageRes: Int, changed: Int, failed: Int): PackageActionResult =
        if (failed == 0) PackageActionResult(messageRes, changed.toString(), SnackbarTone.Success)
        else
            PackageActionResult(
                R.string.modules_batch_partial,
                "$changed/${changed + failed}",
                SnackbarTone.Failure,
            )

    // Long-press actions all speak through one snackbar, so a slow one (re-optimize) can report
    // twice — that it started, and how it ended.
    fun report(result: PackageActionResult) {
        val text =
            result.argument?.let { context.getString(result.messageRes, it) }
                ?: context.getString(result.messageRes)
        actionScope.launch { snackbars.show(text, result.tone) }
    }
    val failureTemplate = stringResource(R.string.module_toggle_failed)
    val scope = rememberCoroutineScope()
    val backedUp = stringResource(R.string.modules_backup_done)
    val backupFailed = stringResource(R.string.modules_backup_failed)
    val restored = stringResource(R.string.modules_restore_done)
    val restoreFailed = stringResource(R.string.modules_restore_failed)

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) {
            uri ->
            if (uri != null) {
                viewModel.backupTo(uri) { count ->
                    scope.launch {
                        if (count != null) snackbars.show(String.format(backedUp, count), SnackbarTone.Success)
                        else snackbars.show(backupFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    val selectionBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) {
            uri ->
            if (uri != null) {
                viewModel.backupSelectedTo(uri) { count ->
                    scope.launch {
                        if (count != null)
                            snackbars.show(String.format(backedUp, count), SnackbarTone.Success)
                        else snackbars.show(backupFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.restoreFrom(uri) { outcome ->
                    scope.launch {
                        if (outcome != null)
                            snackbars.show(
                                String.format(restored, outcome.restored, outcome.skipped),
                                SnackbarTone.Success,
                            )
                        else snackbars.show(restoreFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    LaunchedEffect(toggleFailed) {
        toggleFailed?.let {
            snackbars.show(String.format(failureTemplate, it), SnackbarTone.Failure)
            viewModel.consumeToggleFailure()
        }
    }

    Scaffold(snackbarHost = { VectorSnackbarHost(snackbars) }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Hoisted above the header: the count in it is the *visible* profile's, so the header
            // has to know which page is showing. Aggregating across profiles made "4 of 6 active"
            // describe a set the user was not looking at.
            val pagerState = rememberPagerState(pageCount = { tabs.size })
            val visible = tabs.getOrNull(pagerState.currentPage)

            // The header always composes, so this band's height never depends on the mode. A
            // selection bar that measured itself would be shorter than the header, and everything
            // below — search field, tabs, the list itself — would jump the moment a module was
            // picked up. Here the bar is laid over a header that has only gone invisible.
            Box {
                ModulesHeader(
                    active = visible?.modules?.count { it.isEnabled } ?: counts.first,
                    total = visible?.modules?.size ?: counts.second,
                    onBackup = { backupLauncher.launch("vector-modules.bak") },
                    onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.alpha(if (selection.isEmpty()) 1f else 0f),
                    search = { ModulesSearch(query, viewModel, filter, sort) },
                )
                if (selection.isNotEmpty()) {
                    SelectionBar(
                        count = selection.size,
                        modifier = Modifier.matchParentSize(),
                        onClose = viewModel::clearSelection,
                        onEnable = {
                            viewModel.setSelectedEnabled(true) { changed, failed ->
                                report(batchResult(R.string.modules_batch_enabled, changed, failed))
                            }
                        },
                        onDisable = {
                            viewModel.setSelectedEnabled(false) { changed, failed ->
                                report(
                                    batchResult(R.string.modules_batch_disabled, changed, failed)
                                )
                            }
                        },
                        onBackup = { selectionBackupLauncher.launch("vector-modules.bak") },
                        onUninstall = { confirmUninstall = true },
                    )
                }
            }

            // No blocking spinner: the pull-to-refresh indicator already reports the reload, and
            // a full-screen spinner on every route in made the list flash.
            if (isLoading && tabs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (tabs.isEmpty() || tabs.all { it.modules.isEmpty() }) {
                EmptyState(daemonAvailable = daemonAvailable, filtered = query.isNotBlank())
                return@Column
            }

            if (tabs.size > 1) {
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(tab.user.name, fontWeight = FontWeight.Medium) },
                        )
                    }
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
              // Pull to re-read the installed packages and the daemon's enabled set. A module
              // installed or removed outside the manager is the common case, and the broadcast
              // that catches it does not fire for every route in.
              PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = { viewModel.loadModules() },
              ) {
                val modules = tabs[page].modules
                // Sections only make sense when the order is by state. Under any other sort the
                // groups would interleave, and a header that lies about what follows it is worse
                // than no header.
                val sectioned = sort == ModuleSort.EnabledFirst && query.isBlank()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                ) {
                    if (sectioned) {
                        val active = modules.filter { it.isEnabled }
                        val inactive = modules.filterNot { it.isEnabled }

                        if (active.isNotEmpty()) {
                            stickyHeader(key = "h:active") {
                                SectionHeader(stringResource(R.string.modules_section_active), active.size)
                            }
                            moduleRows(active, facts, selection, onModuleClick, viewModel::toggleSelected, ::report)
                        }
                        if (inactive.isNotEmpty()) {
                            stickyHeader(key = "h:inactive") {
                                SectionHeader(
                                    stringResource(R.string.modules_section_inactive),
                                    inactive.size,
                                )
                            }
                            moduleRows(inactive, facts, selection, onModuleClick, viewModel::toggleSelected, ::report)
                        }
                    } else {
                        moduleRows(modules, facts, selection, onModuleClick, viewModel::toggleSelected, ::report)
                    }
                }
              }
            }
        }
    }

    if (confirmUninstall) {
        val removed = stringResource(R.string.modules_batch_uninstalled)
        VectorAlertDialog(
            onDismissRequest = { confirmUninstall = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.modules_uninstall_title)) },
            // Names the consequence rather than asking "are you sure": what is lost is the module's
            // own configuration, which no backup on this screen covers.
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.modules_uninstall_body,
                        selection.size,
                        selection.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = false
                        viewModel.uninstallSelected { gone, failed ->
                            report(batchResult(R.string.modules_batch_uninstalled, gone, failed))
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.action_uninstall),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) {
                    Text(stringResource(R.string.logs_cancel))
                }
            },
        )
    }
}

/**
 * What the selection can be done to.
 *
 * Laid over the header rather than replacing it, and inset so it reads as a panel that has come
 * forward over the screen rather than a coloured slab bolted to the top of it. The count takes the
 * place the title held, the actions take the place the backup and restore icons held, so the eye
 * does not have to find anything twice.
 *
 * Uninstall is last and in the error colour, and asks before it does anything — it is the only
 * irreversible thing on this screen.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onBackup: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.modules_selection_clear),
                )
            }
            Text(
                text = pluralStringResource(R.plurals.modules_selected, count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 2.dp),
            )
            SelectionAction(Icons.Rounded.PlayArrow, R.string.modules_batch_enable, onEnable)
            SelectionAction(Icons.Rounded.Block, R.string.modules_batch_disable, onDisable)
            SelectionAction(Icons.Rounded.Backup, R.string.modules_backup, onBackup)
            SelectionAction(
                Icons.Rounded.DeleteOutline,
                R.string.action_uninstall,
                onUninstall,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    descriptionRes: Int,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(descriptionRes),
            tint = tint ?: LocalContentColor.current,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The module search field, as the header's third row. */
@Composable
private fun ModulesSearch(
    query: String,
    viewModel: ModulesViewModel,
    filter: ModuleFilter,
    sort: ModuleSort,
) {
    SearchField(
        query = query,
        onQueryChange = viewModel::setQuery,
        placeholder = stringResource(R.string.modules_search_hint),
    ) {
        ModuleFilterButton(
            filter = filter,
            onFilterChange = viewModel::setFilter,
            sort = sort,
            onSortChange = viewModel::setSort,
        )
    }
}

/** The filter menu that lives in the search field's trailing slot. */
@Composable
private fun ModuleFilterButton(
    filter: ModuleFilter,
    onFilterChange: (ModuleFilter) -> Unit,
    sort: ModuleSort,
    onSortChange: (ModuleSort) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val filtering = filter != ModuleFilter.All || sort != ModuleSort.EnabledFirst

    Box {
        IconButton(onClick = { menuOpen = true }) {
            BadgedBox(
                badge = {
                    // A filter that narrows the list must never be silent — an empty list with
                    // no visible cause reads as "nothing installed".
                    if (filtering) Badge(modifier = Modifier.size(6.dp))
                }
            ) {
                Icon(
                    Icons.Rounded.FilterList,
                    contentDescription = stringResource(R.string.modules_filter),
                    tint =
                        if (filtering) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
LocalizedOverlay {

            ModuleFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    trailingIcon = {
                        if (option == filter) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        onFilterChange(option)
                        menuOpen = false
                    },
                )
            }
            HorizontalDivider()
            ModuleSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    trailingIcon = {
                        if (option == sort) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        onSortChange(option)
                        menuOpen = false
                    },
                )
            }
        }
}
    }
}

@Composable
private fun ModulesHeader(
    active: Int,
    total: Int,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    search: @Composable () -> Unit,
) {
    PanelHeader(
        title = stringResource(R.string.nav_modules),
        modifier = modifier,
        actions = {
            // Both shown rather than hidden behind an overflow. There are exactly two, they are
            // opposites, and a menu holding two items costs a tap to say what a glance could.
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Rounded.Restore,
                    contentDescription = stringResource(R.string.modules_restore),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onBackup) {
                Icon(
                    Icons.Rounded.Backup,
                    contentDescription = stringResource(R.string.modules_backup),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        description = {
            if (total > 0) {
                Text(
                    text = stringResource(R.string.modules_active_of, active, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        search = search,
    )
}

/**
 * A module, as a row.
 *
 * No card and no tinted background. Three states have to be distinguishable at a glance — running,
 * off, and unable to run — and painting the whole row for each turned the list into stacked blocks
 * of colour that fought the icons and the text. **The module's own name carries the state
 * instead**: the accent colour when it is running, muted when it is off, the error colour when it
 * cannot run at all. One word does the work a whole surface was doing badly.
 *
 * The icon is left exactly as the module ships it. Wrapping it in a coloured well made every
 * module look like it belonged to Vector rather than to its author.
 *
 * Three columns for the three questions the row answers: what it is (icon, and the API it needs),
 * what it does (name and description), and how it is configured — version at the top of the right
 * column and reach at the bottom, so both edges of the row are anchored and the counts line up
 * down the list.
 */
@Composable
private fun ModuleRow(
    module: InstalledModule,
    facts: ModuleFacts?,
    selected: Boolean,
    onClick: () -> Unit,
    onIconClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val incompatible = facts?.incompatible == true

    val nameColor by
        animateColorAsState(
            when {
                incompatible -> colors.error
                module.isEnabled -> colors.primary
                else -> colors.onSurfaceVariant
            },
            label = "moduleNameColor",
        )

    var expanded by rememberSaveable(module.packageName) { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                // Intrinsic height so the right column can push its lower item to the bottom of
                // whatever the description made this row.
                .height(IntrinsicSize.Min)
                // A module that is off recedes rather than merely changing colour: the list is
                // read first as "what is running", and everything else should sit behind that.
                .alpha(if (module.isEnabled || incompatible) 1f else 0.45f)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The icon is the switch. Double-tapping it turns the module on or off without leaving the
        // list, which is what someone flipping several modules actually wants; a single tap only
        // says what state it is in, because a one-tap toggle here would fire every time a thumb
        // brushed the list.
        Column(
            modifier = Modifier.combinedClickable(onClick = onIconClick, onLongClick = onLongClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(
                    applicationInfo = module.applicationInfo,
                    contentDescription = null,
                    size = 56.dp,
                )
                // The tick covers the icon rather than sitting beside it. A selected row has to be
                // unmistakable at a glance across a screen of them, and the icon is the one part
                // of the row the eye is already using to tell the rows apart.
                if (selected) {
                    Box(
                        modifier =
                            Modifier.size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            ApiBadge(module = module, incompatible = incompatible)
        }

        Spacer(Modifier.width(16.dp))

        // Only this column opens the scope. The row used to be one target, so a tap anywhere —
        // including the icon someone was aiming at — navigated away.
        Column(
            Modifier.weight(1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Text(
                text = module.appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = nameColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (incompatible) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.modules_incompatible, module.minVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            } else if (module.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        // The only prose on this screen, and the thing that says what the module
                        // actually does — so it gets room.
                        text = module.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        // Whether there is more to read is a property of this description at this
                        // width, which only the layout knows — so the control appears only when
                        // it would do something.
                        onTextLayout = { truncated = it.hasVisualOverflow || expanded },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (truncated) {
                        Icon(
                            imageVector =
                                if (expanded) Icons.Rounded.ExpandLess
                                else Icons.Rounded.ExpandMore,
                            contentDescription =
                                stringResource(
                                    if (expanded) R.string.modules_collapse
                                    else R.string.modules_expand
                                ),
                            tint = colors.primary,
                            modifier =
                                Modifier.padding(start = 4.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable { expanded = !expanded },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = module.versionName.ifBlank { "" },
                style = VectorMono,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            ScopePreview(module = module, facts = facts)
        }
    }
}

/**
 * Who the module actually touches.
 *
 * A count answers a question nobody asked; three recognisable icons answer "does this touch
 * anything I care about" without opening anything. The remainder collapses to a number, and a
 * module that is running with nothing to hook still says so in words, because that state is a
 * mistake rather than a fact.
 */
@Composable
private fun ScopePreview(module: InstalledModule, facts: ModuleFacts?) {
    val colors = MaterialTheme.colorScheme
    val reach = facts?.scopeCount ?: -1
    val framework = facts?.scopeFramework == true
    // Nothing to depict, so nothing is drawn. A row saying "no apps" spent a line telling the
    // user about an absence, and it said it about every module that hooks only the framework.
    if (reach <= 0 && !framework) return

    val preview = facts?.scopePreview.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (framework) {
            // The framework is a scope target with no icon, so it gets a mark of its own rather
            // than silently becoming part of a number.
            Icon(
                Icons.Rounded.Android,
                contentDescription = stringResource(R.string.modules_scope_framework),
                tint = colors.primary,
                modifier = Modifier.padding(start = 3.dp).size(20.dp),
            )
        }
        preview.forEach { info ->
            AppIcon(
                applicationInfo = info,
                contentDescription = null,
                size = 20.dp,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
        val remainder = reach - preview.size
        if (remainder > 0) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.modules_scope_more, remainder),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * `API 101` / `Xposed 93`, with the scale small and quiet and the number carrying the colour.
 *
 * The scale name is context that rarely changes; the number is the fact being checked. A module
 * that declares no API at all shows `API ?` rather than a sentence — it is the same shape as every
 * other badge, so the missing value reads as missing rather than as a different kind of thing.
 */
@Composable
private fun ApiBadge(module: InstalledModule, incompatible: Boolean) {
    val colors = MaterialTheme.colorScheme
    val undeclared = !module.declaresApiVersion

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text =
                stringResource(
                    if (module.isLegacy) R.string.modules_api_scale_legacy
                    else R.string.modules_api_scale_modern
                ),
            // Barely there: the scale is context, and it repeats down every row. The number is
            // the only part anyone reads twice.
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Text(
            text = if (undeclared) "?" else module.minVersion.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (incompatible || undeclared) colors.error else colors.primary,
        )
    }
}

@Composable
private fun EmptyState(daemonAvailable: Boolean, filtered: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Extension,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text =
                    stringResource(
                        when {
                            !daemonAvailable -> R.string.modules_no_daemon
                            filtered -> R.string.modules_no_match
                            else -> R.string.modules_empty
                        }
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun ModuleFilter.labelRes(): Int =
    when (this) {
        ModuleFilter.All -> R.string.modules_filter_all
        ModuleFilter.Active -> R.string.modules_filter_active
        ModuleFilter.Inactive -> R.string.modules_filter_inactive
    }

private fun ModuleSort.labelRes(): Int =
    when (this) {
        ModuleSort.EnabledFirst -> R.string.modules_sort_enabled
        ModuleSort.Name -> R.string.modules_sort_name
        ModuleSort.RecentlyUpdated -> R.string.modules_sort_recent
        ModuleSort.WidestScope -> R.string.modules_sort_scope
    }

/** Emits one row per module, plus its divider. */
private fun androidx.compose.foundation.lazy.LazyListScope.moduleRows(
    modules: List<InstalledModule>,
    facts: Map<ModuleKey, ModuleFacts>,
    selection: Set<ModuleKey>,
    onModuleClick: (String, Int) -> Unit,
    onSelect: (InstalledModule) -> Unit,
    onAction: (PackageActionResult) -> Unit,
) {
    items(modules, key = { "${it.packageName}:${it.userId}" }) { module ->
        ModuleListItem(
            module = module,
            facts = facts[ModuleKey(module.packageName, module.userId)],
            selected = ModuleKey(module.packageName, module.userId) in selection,
            selectionActive = selection.isNotEmpty(),
            onClick = { onModuleClick(module.packageName, module.userId) },
            onSelect = { onSelect(module) },
            onAction = onAction,
        )
        // Inset from both ends. A full-bleed rule cuts the list into slabs; a short one reads as
        // a breath between rows, which is all it is for.
        HorizontalDivider(
            modifier = Modifier.padding(start = 108.dp, end = 32.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

/**
 * A module row, with the sheet its long press opens.
 *
 * Swipe-to-toggle used to live here and is gone: a horizontal drag on a row inside a vertically
 * scrolling list competes with the scroll for every gesture that is not perfectly straight.
 *
 * **The icon is the selection handle.** Tapping it picks the module up; from there the same tap
 * on any other icon adds to the set and the bar at the top acts on all of them at once. Enabling
 * eight modules used to be eight round trips through a row, and there was no way at all to remove
 * or back up more than one.
 */
@Composable
private fun ModuleListItem(
    module: InstalledModule,
    facts: ModuleFacts?,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onAction: (PackageActionResult) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    ModuleRow(
        module = module,
        facts = facts,
        selected = selected,
        // Once anything is selected the whole row joins the selection, because that is what every
        // other list on the platform does and reaching for a 56dp icon to add the ninth module
        // would be its own small ordeal.
        onClick = if (selectionActive) onSelect else onClick,
        onIconClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect()
        },
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            menuOpen = true
        },
    )

    if (menuOpen) {
        PackageActionSheet(
            packageName = module.packageName,
            userId = module.userId,
            appName = module.appName,
            applicationInfo = module.applicationInfo,
            isModule = true,
            onDismiss = { menuOpen = false },
            onResult = onAction,
        )
    }
}

/** A pinned label saying which half of the list you are in, and how big it is. */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = VectorMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
