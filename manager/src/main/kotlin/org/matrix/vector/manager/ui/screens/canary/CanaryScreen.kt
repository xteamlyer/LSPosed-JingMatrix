package org.matrix.vector.manager.ui.screens.canary

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.github.CanaryBuild
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.github.SignInState
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * Canary builds: what CI has produced since the last release, and how to try one.
 *
 * **Nobody signs in here.** This screen used to list Actions artifacts and ask for a GitHub account
 * before it would hand one over, because GitHub gates artifact downloads behind auth even on a
 * public repository — `actions/artifacts/<id>/zip` answers 401 anonymously where a release asset
 * answers 206. That was a trust cost imposed by a storage decision: users of a root-level framework
 * were being asked to grant an OAuth app something in order to work around where the zips happened
 * to live. CI now attaches the same zips to a rolling `canary-<versionCode>` prerelease, whose
 * assets any anonymous caller can fetch, so the ask is gone and so is the block that carried it.
 *
 * It also works for the users who most need it: the people who cannot reach GitHub's login page are
 * exactly the ones a canary programme loses first, and a release asset is served from a different
 * host than the login flow.
 *
 * The Actions page is still one tap away for anyone who wants the build log or a commit older than
 * the five CI keeps, filtered the way the project README filters it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanaryScreen(onNavigateBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    var builds by remember { mutableStateOf<List<CanaryBuild>?>(null) }
    LaunchedEffect(Unit) { builds = ServiceLocator.github.canaryBuilds() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_test_canary)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenUrl(GitHubRepository.CANARY_URL) }) {
                        Icon(
                            Icons.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.canary_open_actions),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val list = builds
            when {
                list == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                list.isEmpty() -> CanaryEmpty(onOpenUrl = onOpenUrl)
                else ->
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(list, key = { it.id }) { build ->
                            BuildRow(build = build, onOpenUrl = onOpenUrl)
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        item {
                            // The raw runs, for anyone who wants the build log or a commit older
                            // than the five CI keeps.
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                OutlinedButton(
                                    onClick = { onOpenUrl(GitHubRepository.CANARY_URL) }
                                ) {
                                    Icon(
                                        Icons.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.canary_open_actions))
                                }
                            }
                        }
                    }
            }
        }
    }
}

/**
 * Nothing published yet.
 *
 * Says what to do about it rather than reporting an absence and stopping: before CI has pushed its
 * first prerelease this is the normal state, not a fault.
 */
@Composable
private fun CanaryEmpty(onOpenUrl: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Science,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.canary_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { onOpenUrl(GitHubRepository.CANARY_URL) }) {
            Text(stringResource(R.string.canary_open_actions))
        }
    }
}

@Composable
private fun BuildRow(build: CanaryBuild, onOpenUrl: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(
            text = build.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(build.shortSha, style = VectorMono, color = colors.onSurfaceVariant)
            Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = colors.outlineVariant)
            Text(
                build.branch,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }

        build.artifacts.filterNot { it.expired }.forEach { artifact ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        artifact.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatSize(artifact.sizeInBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (artifact.downloadUrl != null) {
                    // Handed to the browser rather than fetched here: the download URL redirects to
                    // a signed blob URL, and the browser is already the thing that knows how to
                    // resume, store and surface a large file the user then installs by hand.
                    TextButton(onClick = { onOpenUrl(artifact.downloadUrl) }) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.canary_download))
                    }
                }
            }
        }

        if (build.artifacts.none { !it.expired }) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.canary_expired),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }

        build.htmlUrl?.let { url ->
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onOpenUrl(url) }) {
                    Text(stringResource(R.string.canary_open_run))
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f kB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
