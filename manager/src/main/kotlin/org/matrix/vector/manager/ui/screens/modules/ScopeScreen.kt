package org.matrix.vector.manager.ui.screens.modules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.model.AppInfo
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.components.AppIcon
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.show
import org.matrix.vector.manager.ui.components.PackageActionResult
import org.matrix.vector.manager.ui.components.PackageActionSheet
import org.matrix.vector.manager.ui.components.SearchField
import org.matrix.vector.manager.ui.theme.VectorMono

class ScopeViewModelFactory(private val packageName: String, private val userId: Int) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ScopeViewModel(
            modulePackageName = packageName,
            userId = userId,
            daemonClient = ServiceLocator.daemon,
            appRepository = ServiceLocator.apps,
            moduleRepository = ServiceLocator.modules,
            packageManager = ServiceLocator.context.packageManager,
        )
            as T
}

/**
 * Which apps a module may hook.
 *
 * The screen's whole shape follows from one fact: **applying a scope makes the daemon force-stop
 * every affected app.** So edits are a draft the user builds up, and applying is a deliberate act
 * with its cost stated — *3 to add, 1 to remove* — rather than a silent side effect of ticking a
 * box. The previous implementation wrote the entire scope on every tap, so choosing ten apps
 * killed and restarted them ten times over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeScreen(
    packageName: String,
    userId: Int,
    onNavigateBack: () -> Unit,
    viewModel: ScopeViewModel = viewModel(factory = ScopeViewModelFactory(packageName, userId)),
) {
    val apps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pending by viewModel.pendingChanges.collectAsStateWithLifecycle()
    val applying by viewModel.applying.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showSystem by viewModel.showSystemApps.collectAsStateWithLifecycle()
    val showGames by viewModel.showGames.collectAsStateWithLifecycle()
    val recommendedOnly by viewModel.showRecommendedOnly.collectAsStateWithLifecycle()
    val showModules by viewModel.showModules.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sort.collectAsStateWithLifecycle()
    val reversed by viewModel.reverseSort.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scopeSaved = stringResource(R.string.scope_backup_done)
    val scopeFailed = stringResource(R.string.scope_backup_failed)

    fun report(result: PackageActionResult) {
        val text =
            result.argument?.let { context.getString(result.messageRes, it) }
                ?: context.getString(result.messageRes)
        scope.launch { snackbars.show(text, result.tone) }
    }

    val scopeBackupLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                viewModel.backupScopeTo(uri) { ok ->
                    scope.launch {
                        if (ok) snackbars.show(scopeSaved, SnackbarTone.Success)
                        else snackbars.show(scopeFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    val scopeRestoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.restoreScopeFrom(uri) { ok ->
                    if (!ok) scope.launch { snackbars.show(scopeFailed, SnackbarTone.Failure) }
                }
            }
        }
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmStranded by remember { mutableStateOf(false) }

    val staticScopeNotice = stringResource(R.string.scope_static)
    val applied = stringResource(R.string.scope_applied)
    val applyFailed = stringResource(R.string.scope_apply_failed)
    val toggleFailed = stringResource(R.string.scope_toggle_failed)

    LaunchedEffect(message) {
        val text =
            when (message) {
                ScopeMessage.Applied -> applied
                ScopeMessage.ApplyFailed -> applyFailed
                ScopeMessage.ToggleFailed,
                ScopeMessage.AutoIncludeFailed -> toggleFailed
                null -> null
            }
        if (text != null) {
            haptics.performHapticFeedback(
                if (message == ScopeMessage.Applied) HapticFeedbackType.Confirm
                else HapticFeedbackType.Reject
            )
            snackbars.show(
                text,
                if (message == ScopeMessage.Applied) SnackbarTone.Success else SnackbarTone.Failure,
            )
            viewModel.consumeMessage()
        }
    }

    // Leaving a module enabled with nothing to hook does nothing at all but looks like it works.
    fun attemptBack() {
        if (viewModel.wouldStrandModule()) confirmStranded = true else onNavigateBack()
    }

    Scaffold(
        topBar = {
            // One line: back, who this is about, and the switch. The large two-line bar spent a
            // fifth of the screen restating a name the user had just tapped, on a screen whose
            // whole job is a long list.
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.moduleName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            // The column is a fixed slice of one row, and module names are not.
                            // Rather than truncate the end of a name — which is often exactly the
                            // part that distinguishes two builds of the same module — the text
                            // scrolls itself when it does not fit.
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        )
                        Text(
                            text = packageName,
                            style = VectorMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::attemptBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // The master switch, in the bar. It is the single most consequential control
                    // on the screen and it was previously a card competing with the app list for
                    // the same attention; an overflow menu in its place held items that now live
                    // in the search field, next to the list they act on.
                    Switch(
                        checked = state.isEnabled,
                        onCheckedChange = { enable ->
                            haptics.performHapticFeedback(
                                if (enable) HapticFeedbackType.ToggleOn
                                else HapticFeedbackType.ToggleOff
                            )
                            viewModel.setModuleEnabled(enable)
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        snackbarHost = { VectorSnackbarHost(snackbars) },
        bottomBar = {
            // Appears only when there is something to apply, so the cost is stated exactly when
            // it becomes real.
            AnimatedVisibility(
                visible = pending.any,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                ApplyBar(
                    added = pending.added,
                    removed = pending.removed,
                    applying = applying,
                    onDiscard = viewModel::discard,
                    onApply = viewModel::apply,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = { viewModel.searchQuery.value = it },
                placeholder = stringResource(R.string.scope_search_hint),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                // Everything that changes *what the list shows or contains* lives here, beside
                // the list it acts on, rather than behind an overflow menu in the title bar.
                ScopeSelectMenu(
                    hasRecommended = !state.recommended.isEmpty,
                    autoInclude = state.autoInclude,
                    onUseRecommended = viewModel::useRecommended,
                    onSelectAll = viewModel::selectAllVisible,
                    onSelectNone = viewModel::clearAllVisible,
                    onAutoInclude = viewModel::setAutoInclude,
                    onBackup = { scopeBackupLauncher.launch("$packageName-scope.json") },
                    onRestore = { scopeRestoreLauncher.launch(arrayOf("*/*")) },
                )
                ScopeFilterMenu(
                    showSystem = showSystem,
                    showGames = showGames,
                    showModules = showModules,
                    hasRecommended = !state.recommended.isEmpty,
                    recommendedOnly = recommendedOnly,
                    onToggleRecommendedOnly = {
                        viewModel.setRecommendedOnly(!recommendedOnly)
                    },
                    locked = state.recommended.staticScope,
                    onLockedClick = { scope.launch { snackbars.show(staticScopeNotice) } },
                    onToggleSystem = { viewModel.showSystemApps.value = !showSystem },
                    onToggleGames = { viewModel.showGames.value = !showGames },
                    onToggleModules = { viewModel.setShowModules(!showModules) },
                )
                ScopeSortMenu(
                    sort = sortOrder,
                    reversed = reversed,
                    onSort = viewModel::setSort,
                    onReverse = viewModel::toggleReverse,
                )
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(apps, key = { "${it.packageName}:${it.userId}" }) { app ->
                    AppRow(
                        app = app,
                        enabled = !state.recommended.staticScope,
                        origin =
                            when {
                                state.recommended.staticScope && app.isRecommended ->
                                    ScopeOrigin.Locked
                                app.isRecommended && state.autoInclude -> ScopeOrigin.AutoIncluded
                                app.isRecommended -> ScopeOrigin.Requested
                                else -> ScopeOrigin.Chosen
                            },
                        onToggle = { checked ->
                            haptics.performHapticFeedback(
                                if (checked) HapticFeedbackType.ToggleOn
                                else HapticFeedbackType.ToggleOff
                            )
                            viewModel.toggle(app, checked)
                        },
                        onAction = ::report,
                    )
                }
            }
        }
    }

    if (confirmStranded) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmStranded = false },
            title = { Text(stringResource(R.string.scope_empty_title)) },
            text = { Text(stringResource(R.string.scope_empty_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setModuleEnabled(false)
                        confirmStranded = false
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.scope_empty_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStranded = false }) {
                    Text(stringResource(R.string.scope_empty_keep))
                }
            },
        )
    }
}

/** Everything that changes the *selection*, in the search field's trailing slot. */
@Composable
private fun ScopeSelectMenu(
    hasRecommended: Boolean,
    autoInclude: Boolean,
    onUseRecommended: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onAutoInclude: (Boolean) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Rounded.Checklist,
                contentDescription = stringResource(R.string.scope_select),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasRecommended) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scope_use_recommended)) },
                    onClick = {
                        onUseRecommended()
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_select_visible)) },
                onClick = {
                    onSelectAll()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_clear_visible)) },
                onClick = {
                    onSelectNone()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_auto_include)) },
                trailingIcon = {
                    if (autoInclude) Icon(Icons.Rounded.Check, contentDescription = null)
                },
                onClick = { onAutoInclude(!autoInclude) },
            )
            HorizontalDivider()
            // This module's scope alone, separate from the whole-list backup on the module
            // screen — useful when moving one module's configuration between devices.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_backup)) },
                onClick = {
                    onBackup()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_restore)) },
                onClick = {
                    onRestore()
                    open = false
                },
            )
        }
    }
}

/**
 * What the list *contains*.
 *
 * All three were in the legacy manager and all three earn their place: system apps are usually
 * noise but occasionally the target, games are bulk, and other modules are installed apps that are
 * rarely what you are hooking.
 */
@Composable
private fun ScopeFilterMenu(
    showSystem: Boolean,
    showGames: Boolean,
    showModules: Boolean,
    hasRecommended: Boolean,
    recommendedOnly: Boolean,
    onToggleRecommendedOnly: () -> Unit,
    locked: Boolean,
    onLockedClick: () -> Unit,
    onToggleSystem: () -> Unit,
    onToggleGames: () -> Unit,
    onToggleModules: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Anything other than the defaults is narrowing the list, and must not be silent.
    val filtering = !locked && (showSystem || !showGames || !showModules || recommendedOnly)

    Box {
        // Under a static scope the list is already exactly the module's own fixed set, so there is
        // nothing to filter. The control stays present but visibly dead, and says why when
        // pressed — removing it entirely would just raise the same question silently.
        IconButton(onClick = { if (locked) onLockedClick() else open = true }) {
            BadgedBox(badge = { if (filtering) Badge(modifier = Modifier.size(6.dp)) }) {
                Icon(
                    Icons.Rounded.FilterList,
                    contentDescription = stringResource(R.string.modules_filter),
                    tint =
                        when {
                            locked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            filtering -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasRecommended) {
                // The static-scope view, on request. Offered only when the module actually asked
                // for something — otherwise it would narrow the list to nothing.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scope_recommended_only)) },
                    trailingIcon = {
                        if (recommendedOnly) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = { onToggleRecommendedOnly() },
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_system_apps)) },
                trailingIcon = {
                    if (showSystem) Icon(Icons.Rounded.Check, contentDescription = null)
                },
                onClick = { onToggleSystem() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_games)) },
                trailingIcon = {
                    if (showGames) Icon(Icons.Rounded.Check, contentDescription = null)
                },
                onClick = { onToggleGames() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_modules)) },
                trailingIcon = {
                    if (showModules) Icon(Icons.Rounded.Check, contentDescription = null)
                },
                onClick = { onToggleModules() },
            )
        }
    }
}

/** What order it is in. Four keys and a reverse, as the legacy manager had. */
@Composable
private fun ScopeSortMenu(
    sort: ScopeSort,
    reversed: Boolean,
    onSort: (ScopeSort) -> Unit,
    onReverse: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Rounded.Sort,
                contentDescription = stringResource(R.string.scope_sort),
                tint =
                    if (sort != ScopeSort.Relevance || reversed)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScopeSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    trailingIcon = {
                        if (option == sort) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        onSort(option)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.scope_sort_reverse)) },
                trailingIcon = {
                    if (reversed) Icon(Icons.Rounded.Check, contentDescription = null)
                },
                onClick = { onReverse() },
            )
        }
    }
}

/**
 * How a row came to be in the scope, which decides the ring around its icon.
 *
 * Three different mechanisms can put an app in a module's scope and they behave differently when
 * the world changes — one is fixed forever, one keeps adding apps behind your back, one is only
 * ever what you ticked. Before this they all rendered as an identical checkbox, so a scope the
 * module controls looked exactly like a scope the user controls.
 */
private enum class ScopeOrigin {
    /** The module fixed this scope; the user cannot change it. */
    Locked,
    /** The module asked for it, and auto-include will keep re-adding it. */
    AutoIncluded,
    /** The module asked for it, but it is the user's choice. */
    Requested,
    /** Nothing asked for it; it is in the scope because someone ticked it. */
    Chosen,
}

@Composable
private fun ScopeOrigin.color(): Color =
    when (this) {
        // Locked reads as "not yours to change", so it borrows the disabled-ish outline rather
        // than a colour that invites a tap.
        ScopeOrigin.Locked -> MaterialTheme.colorScheme.outline
        ScopeOrigin.AutoIncluded -> MaterialTheme.colorScheme.tertiary
        ScopeOrigin.Requested -> MaterialTheme.colorScheme.primary
        ScopeOrigin.Chosen -> Color.Transparent
    }

private fun ScopeOrigin.labelRes(): Int =
    when (this) {
        ScopeOrigin.Locked -> R.string.scope_origin_locked
        ScopeOrigin.AutoIncluded -> R.string.scope_origin_auto
        ScopeOrigin.Requested -> R.string.scope_recommended
        ScopeOrigin.Chosen -> R.string.scope_origin_chosen
    }

@Composable
private fun AppRow(
    app: AppInfo,
    enabled: Boolean,
    origin: ScopeOrigin,
    onToggle: (Boolean) -> Unit,
    onAction: (PackageActionResult) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val ring = origin.color()

    ListItem(
        modifier =
            Modifier.combinedClickable(
                    onClick = { if (enabled) onToggle(!app.isSelectedInScope) },
                    onLongClick = {
                        // The long press is where re-optimize lives, and re-optimize is the fix
                        // for a hook that silently never fires because ART inlined its target.
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        menuOpen = true
                    },
                )
                .semantics { role = Role.Checkbox },
        leadingContent = {
            // The ring is drawn outside the icon rather than tinting it: an app icon is the user's
            // own landmark for finding a row and recolouring it would destroy that.
            AppIcon(
                applicationInfo = app.applicationInfo,
                contentDescription = null,
                size = 36.dp,
                modifier =
                    Modifier.border(width = 2.dp, color = ring, shape = CircleShape).padding(4.dp),
            )
        },
        headlineContent = { Text(app.appName) },
        supportingContent = {
            Column {
                Text(
                    app.packageName,
                    style = VectorMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (origin != ScopeOrigin.Chosen) {
                    Text(
                        text = stringResource(origin.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ring,
                    )
                }
            }
        },
        trailingContent = { Checkbox(checked = app.isSelectedInScope, onCheckedChange = null) },
        colors =
            ListItemDefaults.colors(
                containerColor = Color.Transparent
            ),
    )
    if (menuOpen) {
        PackageActionSheet(
            packageName = app.packageName,
            userId = app.userId,
            appName = app.appName,
            applicationInfo = app.applicationInfo,
            isModule = false,
            onDismiss = { menuOpen = false },
            onResult = onAction,
        )
    }
}

/** States what applying will actually do, before it does it. */
@Composable
private fun ApplyBar(
    added: Int,
    removed: Int,
    applying: Boolean,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.scope_pending, added, removed),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    // The consequence, stated before the act.
                    text = stringResource(R.string.scope_apply_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDiscard, enabled = !applying) {
                Text(stringResource(R.string.scope_discard))
            }
            Button(onClick = onApply, enabled = !applying) {
                Text(stringResource(R.string.scope_apply))
            }
        }
    }
}

private fun ScopeSort.labelRes(): Int =
    when (this) {
        ScopeSort.Relevance -> R.string.scope_sort_relevance
        ScopeSort.Name -> R.string.scope_sort_name
        ScopeSort.PackageName -> R.string.scope_sort_package
        ScopeSort.InstallTime -> R.string.scope_sort_installed
        ScopeSort.UpdateTime -> R.string.scope_sort_updated
    }
