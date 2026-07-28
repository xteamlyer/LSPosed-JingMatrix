package org.matrix.vector.manager.data.github

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loads the last quarter of activity on the project's GitHub repository.
 *
 * Offline-first: [load] returns whatever is on disk immediately if the network fails, and the
 * caller renders it with a "showing cached" affordance rather than an error. A framework manager
 * must never be blocked by GitHub being unreachable.
 */
class GitHubRepository(
    private val client: OkHttpClient,
    cacheDir: File,
    /**
     * Supplies the optional sign-in token. Anonymous access is a fully supported mode — this only
     * ever raises the rate limit from 60 to 5000 requests an hour.
     */
    private val tokenProvider: () -> String? = { null },
    /** How far back to reach, in months. User-configurable; see SettingsRepository. */
    private val windowMonthsProvider: () -> Int = { DEFAULT_WINDOW_MONTHS },
) {

    private val snapshotFile = File(cacheDir, "github_feed.json")
    private val peopleFile = File(cacheDir, "github_people.json")

    /**
     * Names we have already tried to resolve to a GitHub account, and what came back.
     *
     * A null value is a remembered *failure*: it is as worth keeping as a success, because the
     * alternative is asking about the same unresolvable name on every load. Persisted so that
     * survives the process, which parasitically is killed often.
     */
    private val resolvedPeople: MutableMap<String, ResolvedPerson?> by lazy {
        runCatching { json.decodeFromString<Map<String, ResolvedPerson?>>(peopleFile.readText()) }
            .getOrDefault(emptyMap())
            .toMutableMap()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * How hard to try for fresh data.
     *
     * Opening Home is not a reason to talk to GitHub. The window it shows moves a few times a
     * week at most, so revalidating on every launch spends the user's battery and their share of
     * an anonymous rate limit to redraw the same rows.
     */
    enum class Freshness {
        /** Disk only. Never touches the network. */
        Cached,
        /** Normal conditional request; a 304 costs nothing against the rate limit. */
        Revalidate,
        /** Ignore caches entirely. Only for an explicit pull-to-refresh. */
        Force,
    }

    suspend fun load(freshness: Freshness = Freshness.Revalidate): CommunityFeed =
        withContext(Dispatchers.IO) {
            val months = windowMonthsProvider().coerceIn(1, 60)
            val windowStart =
                System.currentTimeMillis() / 1000 - months * DAYS_PER_MONTH * 24L * 60 * 60
            val fresh = runCatching { fetch(windowStart, freshness) }.getOrNull()
            if (fresh != null) {
                runCatching {
                    snapshotFile.writeText(
                        json.encodeToString(
                            Snapshot(fresh.totalCommits, fresh.rawCommits, fresh.repo)
                        )
                    )
                }
                return@withContext build(
                    fresh.rawCommits,
                    fresh.repo,
                    windowStart,
                    fromCache = false,
                    offline = false,
                    totalCommits = fresh.totalCommits,
                    freshness = freshness,
                )
            }
            val cached =
                runCatching { json.decodeFromString<Snapshot>(snapshotFile.readText()) }
                    // A file written before the total was stored still parses as a bare list, and
                    // is worth reading — it only costs the "you are here" line until the next fetch.
                    .recoverCatching {
                        Snapshot(
                            commits = json.decodeFromString<List<GhCommit>>(snapshotFile.readText())
                        )
                    }
                    .getOrNull()
                    ?: return@withContext CommunityFeed(
                        fromCache = true,
                        // Nothing on disk and nothing from the network: whether that counts as
                        // offline still depends on whether a request was even attempted.
                        offline = freshness != Freshness.Cached,
                        loaded = true,
                    )
            build(
                cached.commits,
                cached.repo,
                windowStart,
                fromCache = true,
                offline = freshness != Freshness.Cached,
                // Kept with the commits rather than recomputed: it comes from the `Link` header of
                // a request this path did not make. Without it every cached read lost the commit
                // numbering, and "you are here" — which is the whole point of numbering them —
                // silently vanished on the four launches out of five that read from disk.
                totalCommits = cached.totalCommits,
            )
        }

    /**
     * What the feed file holds.
     *
     * The total is stored beside the commits because it cannot be derived from them: it comes from
     * the `Link: rel="last"` header of the commit request, and a cached read makes no request.
     */
    @Serializable
    private data class Snapshot(
        val totalCommits: Long = 0L,
        val commits: List<GhCommit> = emptyList(),
        /**
         * The stars, forks and licence line.
         *
         * Stored for the same reason as the total: it comes from a second request, and a cached
         * read makes none — so without it the "take part" numbers vanished on every launch that
         * deliberately did not fetch, which is most of them.
         */
        val repo: GhRepo? = null,
    )

    private class Fetched(
        val rawCommits: List<GhCommit>,
        val repo: GhRepo?,
        val totalCommits: Long,
    )

    private fun fetch(windowStartEpochSeconds: Long, freshness: Freshness): Fetched {
        val since = iso8601(windowStartEpochSeconds)
        val commits =
            get("$API/$REPO/commits?since=$since&per_page=100", freshness)?.let {
                json.decodeFromString<List<GhCommit>>(it)
            } ?: throw IllegalStateException("commits unavailable")

        // The repo stats are a nice-to-have; a failure here must not lose the commits.
        val repo =
            runCatching { get("$API/$REPO", freshness)?.let { json.decodeFromString<GhRepo>(it) } }
                .getOrNull()

        val total = runCatching { fetchTotalCommits() }.getOrDefault(0L)
        return Fetched(commits, repo, total)
    }

    /**
     * How many commits the default branch has, ever.
     *
     * There is no field for this, but asking for one commit per page makes GitHub report the last
     * page number in its `Link` header, and that number is the count. One cheap request, and it
     * is what makes "you are N commits behind" exact rather than a guess.
     */
    private fun fetchTotalCommits(): Long {
        val request =
            Request.Builder()
                .url("$API/$REPO/commits?per_page=1")
                .header("Accept", "application/vnd.github+json")
                .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
                .build()
        client.newCall(request).execute().use { response ->
            val link = response.header("Link") ?: return 0L
            return LAST_PAGE.find(link)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        }
    }

    private fun get(url: String, freshness: Freshness): String? {
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
                .apply {
                    // OkHttp replays the stored ETag as If-None-Match on its own. A 304 costs
                    // nothing against GitHub's 60/hour budget, so revalidation stays cheap.
                    cacheControl(
                        when (freshness) {
                            Freshness.Force -> CacheControl.FORCE_NETWORK
                            Freshness.Cached -> CacheControl.FORCE_CACHE
                            Freshness.Revalidate ->
                                CacheControl.Builder()
                                    .maxAge(REVALIDATE_MINUTES.toInt(), TimeUnit.MINUTES)
                                    .build()
                        }
                    )
                }
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }

    private fun build(
        raw: List<GhCommit>,
        repo: GhRepo?,
        windowStart: Long,
        fromCache: Boolean,
        offline: Boolean,
        totalCommits: Long,
        freshness: Freshness = Freshness.Cached,
    ): CommunityFeed {
        val commits =
            raw.map { c ->
                    val subject = c.commit.message.lineSequence().first().trim()
                    val primary =
                        c.author?.let {
                            CommitPerson(
                                login = it.login,
                                avatarUrl = it.avatarUrl,
                                profileUrl = it.htmlUrl,
                                isBot = it.type == "Bot" || it.login.endsWith("[bot]"),
                            )
                        }
                        // GitHub links a commit to an account by email, and does not always
                        // manage it — a commit written under an address the account never
                        // verified arrives with no author at all. The trailer path already knows
                        // how to make something of a name and an address, so it is reused here
                        // rather than dropping the person to a bare string.
                        ?: person(c.commit.author.name, c.commit.author.email, freshness)

                    // Everyone credited, author first, deduplicated case-insensitively — the
                    // maintainer often appears in a trailer on their own commits.
                    val authors =
                        (listOf(primary) + coAuthors(c.commit.message, freshness)).distinctBy {
                            it.login.lowercase()
                        }

                    TimelineCommit(
                        sha = c.sha,
                        shortSha = c.sha.take(7),
                        subject = subject.removeSuffix(" (#${prNumber(subject) ?: ""})").trim(),
                        kind = CommitKind.of(subject),
                        pullRequest = prNumber(subject),
                        authors = authors,
                        epochSeconds = parseIso8601(c.commit.author.date),
                        htmlUrl = c.htmlUrl,
                        globalIndex = 0, // assigned after sorting, below

                        // Collaboration counts: a commit the maintainer landed with an outside
                        // co-author is a community contribution and is highlighted as one.
                        isCommunity =
                            authors.any {
                                !it.isBot && !it.login.equals(OWNER, ignoreCase = true)
                            },
                        isBot = primary.isBot,
                    )
                }
                .sortedByDescending { it.epochSeconds }
                // Newest-first, so the head of the list is the newest commit and its distance
                // from the repository root is the total count.
                .mapIndexed { index, commit -> commit.copy(globalIndex = totalCommits - index) }

        // Credit follows people, not commits: a co-author is a contributor. Bots are excluded —
        // automation is not a contributor.
        val contributors =
            commits
                .flatMap { commit -> commit.authors.map { person -> person to commit.epochSeconds } }
                .filterNot { (person, _) -> person.isBot }
                .groupBy { (person, _) -> person.login.lowercase() }
                .map { (_, entries) ->
                    val people = entries.map { it.first }
                    Contributor(
                        login = people.first().login,
                        avatarUrl = people.firstNotNullOfOrNull { it.avatarUrl },
                        profileUrl = people.firstNotNullOfOrNull { it.profileUrl },
                        commits = entries.size,
                        lastEpochSeconds = entries.maxOf { it.second },
                    )
                }
                // Ties break on recency, so among equals the person who contributed most
                // recently is shown first — the row is meant to move.
                .sortedWith(
                    compareByDescending<Contributor> { it.commits }
                        .thenByDescending { it.lastEpochSeconds }
                        .thenBy { it.login }
                )

        return CommunityFeed(
            commits = commits,
            contributors = contributors,
            windowStartEpochSeconds = windowStart,
            repo = repo,
            totalCommits = totalCommits,
            fromCache = fromCache,
            offline = offline,
            loaded = true,
        )
    }

    /**
     * Parses `Co-authored-by: Name <email>` trailers.
     *
     * When the address is a GitHub noreply one the login is exactly the local part, and the
     * numeric prefix — `44231502+byemaxx@users.noreply.github.com` — is the user id, which yields
     * the real avatar. Otherwise all that is known is the name the person signed with, and they
     * are shown under it with a monogram rather than being dropped.
     */
    private fun coAuthors(message: String, freshness: Freshness): List<CommitPerson> =
        CO_AUTHOR.findAll(message)
            .map { match -> person(match.groupValues[1].trim(), match.groupValues[2].trim(), freshness) }
            .toList()

    /**
     * The best account we can make of a name and an email address.
     *
     * Three tiers, cheapest and most certain first: a `@users.noreply.github.com` address *is* the
     * account and needs nothing but parsing; otherwise a handle-shaped name is worth one lookup;
     * otherwise the person is shown under the name they signed with, uncredited but not dropped.
     */
    private fun person(name: String, email: String, freshness: Freshness): CommitPerson {
        val noreply = GITHUB_NOREPLY.find(email)
        if (noreply != null) {
            val id = noreply.groupValues[1].takeIf { it.isNotEmpty() }
            val login = noreply.groupValues[2]
            return CommitPerson(
                login = login,
                avatarUrl =
                    if (id != null) "https://avatars.githubusercontent.com/u/$id?v=4"
                    else "https://github.com/$login.png",
                profileUrl = "https://github.com/$login",
                isBot = login.endsWith("[bot]"),
            )
        }
        return resolvePerson(name, freshness)?.let {
            CommitPerson(
                login = it.login,
                avatarUrl = it.avatarUrl,
                profileUrl = it.profileUrl,
                isBot = it.isBot,
            )
        } ?: CommitPerson(login = name, avatarUrl = null, profileUrl = null)
    }

    /**
     * The canary builds, newest first.
     *
     * These are **prereleases**, not Actions artifacts, and that is the whole point. GitHub gates an
     * artifact download behind an account even for a public repository — `actions/artifacts/<id>/zip`
     * answers 401 to an anonymous caller, while a release asset answers 206 — so sourcing canaries
     * from artifacts meant asking every would-be tester for an OAuth grant to work around a storage
     * decision. CI attaches the same zips to a rolling `canary-<versionCode>` prerelease, and this
     * reads that, so nobody signs in to anything.
     *
     * Filtered to the canary tag rather than taking every prerelease: a hand-cut release candidate
     * is also a prerelease, and it is not a nightly.
     */
    suspend fun canaryBuilds(freshness: Freshness = Freshness.Revalidate): List<CanaryBuild> =
        withContext(Dispatchers.IO) {
            val body =
                get("$API/$REPO/releases?per_page=$CANARY_FETCH", freshness)
                    ?: return@withContext emptyList()

            runCatching { json.decodeFromString<List<GhRelease>>(body) }
                .getOrDefault(emptyList())
                .filter { it.prerelease && it.tagName.startsWith(CANARY_TAG_PREFIX) }
                .take(CANARY_KEEP)
                .map { release ->
                    CanaryBuild(
                        id = release.id,
                        title = release.name ?: release.tagName,
                        branch = release.tagName,
                        shortSha = release.targetCommitish.take(7),
                        epochSeconds = parseIso8601(release.publishedAt.orEmpty()),
                        htmlUrl = release.htmlUrl,
                        artifacts =
                            release.assets.map {
                                CanaryArtifact(
                                    id = it.id,
                                    name = it.name,
                                    sizeInBytes = it.size,
                                    expired = false,
                                    downloadUrl = it.downloadUrl,
                                )
                            },
                    )
                }
        }

    /**
     * Every published build, both channels, newest first.
     *
     * One fetch for both because they come from the same endpoint, and because deciding which
     * channel a reader is on needs to see both: a canary that has aged out of the rolling five is
     * still recognisable as a canary by being *newer than the newest stable release*, and that
     * comparison is impossible with only one of the two lists in hand.
     */
    suspend fun frameworkReleases(freshness: Freshness = Freshness.Revalidate):
        List<FrameworkRelease> =
        withContext(Dispatchers.IO) {
            val body =
                get("$API/$REPO/releases?per_page=$CANARY_FETCH", freshness)
                    ?: return@withContext emptyList()

            runCatching { json.decodeFromString<List<GhRelease>>(body) }
                .getOrDefault(emptyList())
                .mapNotNull { release ->
                    val canary = release.prerelease && release.tagName.startsWith(CANARY_TAG_PREFIX)
                    FrameworkRelease(
                        tag = release.tagName,
                        title = release.name ?: release.tagName,
                        versionCode = release.versionCode() ?: return@mapNotNull null,
                        isCanary = canary,
                        notesMarkdown = release.body,
                        htmlUrl = release.htmlUrl,
                        epochSeconds = parseIso8601(release.publishedAt.orEmpty()),
                        zip =
                            release.assets
                                .firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                                ?.let {
                                    CanaryArtifact(
                                        id = it.id,
                                        name = it.name,
                                        sizeInBytes = it.size,
                                        expired = false,
                                        downloadUrl = it.downloadUrl,
                                    )
                                },
                    )
                }
                .sortedByDescending { it.versionCode }
        }

    /**
     * The build number a release represents, or null when it is not comparable with ours.
     *
     * `canary-3049` states it outright. A stable tag does not, so it is read out of the zip's file
     * name — the CI names them with the same version code — and a release whose number cannot be
     * established at all is dropped rather than compared as zero, which would have made every
     * stable release look older than every canary.
     *
     * **Only releases of this product count, and that is not pedantry.** The version code restarted
     * when LSPosed became Vector: this repository's own release list holds `LSPosed-v1.11.0-7209`
     * beside `Vector-v2.0-3021`, so a plain numeric comparison makes the *older* project look four
     * thousand builds newer. Without this filter the manager offered LSPosed 1.11.0 to a Vector
     * 3049 device and called it an update — a cross-product downgrade, flashed with root. Matching
     * the asset prefix is what keeps the comparison inside one numbering scheme.
     */
    private fun GhRelease.versionCode(): Long? {
        val ours = assets.filter { it.name.startsWith(ZIP_PREFIX, ignoreCase = true) }
        if (ours.isEmpty()) return null
        if (tagName.startsWith(CANARY_TAG_PREFIX)) {
            return tagName.removePrefix(CANARY_TAG_PREFIX).toLongOrNull()
        }
        return ours.firstNotNullOfOrNull { asset ->
            Regex("(\\d{3,})").findAll(asset.name).map { it.value }.lastOrNull()?.toLongOrNull()
        }
    }

    @Serializable
    private data class ResolvedPerson(
        val login: String,
        val avatarUrl: String?,
        val profileUrl: String?,
        val isBot: Boolean,
    )

    /**
     * Turns a name signed in a trailer into a GitHub account, when it can be done safely.
     *
     * GitHub's own web UI resolves these by matching the commit *email* to an account, which it can
     * do because it holds every address a user has ever verified. We cannot: the address is usually
     * private, and `search/users?q=…+in:email` finds nothing for it — checked against
     * `krc440002@gmail.com`, which the web UI resolves and the search API returns zero results for.
     *
     * What is left is asking whether an account exists under that name, and that is only safe for
     * names that are plainly *handles*. `GET /users/Qing` answers 200 with a real account — id
     * 158244, an unrelated person — so probing every display name would eventually attach a
     * stranger's face and profile to someone else's contribution. Requiring a digit, a hyphen or an
     * underscore keeps the shape of a handle (`frknkrc44`) and rejects the shape of a name
     * (`Qing`, `Furkan Karcıoğlu`).
     *
     * The cost of the guard is that a handle made only of letters stays unresolved. That is the
     * right way to be wrong: an unlinked contributor is merely uncredited, a mislinked one is
     * credited to the wrong person.
     */
    private fun resolvePerson(name: String, freshness: Freshness): ResolvedPerson? {
        val key = name.lowercase()
        if (resolvedPeople.containsKey(key)) return resolvedPeople[key]
        if (!HANDLE_SHAPED.matches(name)) return null
        // A cache-only load must not decide that a name is unresolvable. The request would be
        // served FORCE_CACHE, miss, and the miss would be written down as a permanent failure — so
        // the first launch after install, which reads the feed from disk, would poison every name
        // before the network was ever asked.
        if (freshness == Freshness.Cached) return null

        val found =
            runCatching {
                    get("$API_ROOT/users/$name", freshness)?.let {
                        val user = json.decodeFromString<GhUser>(it)
                        ResolvedPerson(
                            login = user.login,
                            avatarUrl = user.avatarUrl,
                            profileUrl = user.htmlUrl,
                            isBot = user.type == "Bot" || user.login.endsWith("[bot]"),
                        )
                    }
                }
                .getOrNull()

        resolvedPeople[key] = found
        runCatching { peopleFile.writeText(json.encodeToString(resolvedPeople.toMap())) }
        return found
    }

    private fun prNumber(subject: String): Int? =
        PR_SUFFIX.find(subject)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun iso8601(epochSeconds: Long): String {
        val cal =
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = epochSeconds * 1000
            }
        return String.format(
            java.util.Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    private fun parseIso8601(value: String): Long =
        runCatching {
                val f =
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                (f.parse(value)?.time ?: 0L) / 1000
            }
            .getOrDefault(0L)

    companion object {
        const val OWNER = "JingMatrix"
        const val REPO = "$OWNER/Vector"
        const val REPO_URL = "https://github.com/$REPO"
        const val ISSUES_URL = "$REPO_URL/issues"
        const val PULLS_URL = "$REPO_URL/pulls"
        const val DISCUSSIONS_URL = "$REPO_URL/discussions"
        const val GOOD_FIRST_ISSUE_URL = "$REPO_URL/issues?q=is%3Aopen+label%3A%22good+first+issue%22"

        /**
         * Canary builds come off CI rather than a release tag, so testing one means going to the
         * workflow's run list and taking the artifact from the most recent master build.
         */
        const val CANARY_URL = "$REPO_URL/actions/workflows/core.yml?query=branch%3Amaster"
        private const val CANARY_TAG_PREFIX = "canary-"

        /**
         * What this product's own release zips are called.
         *
         * The release list still carries the pre-rename LSPosed builds, whose version codes are
         * from a different and higher numbering; see `versionCode()`.
         */
        private const val ZIP_PREFIX = "Vector-"

        /** CI keeps five; a few extra are fetched so a stable release among them costs nothing. */
        private const val CANARY_FETCH = 12
        private const val CANARY_KEEP = 5

        private const val API = "https://api.github.com/repos"
        private const val API_ROOT = "https://api.github.com"

        /**
         * A name shaped like a handle rather than a display name.
         *
         * A digit, a hyphen or an underscore somewhere in it, and nothing that a GitHub login
         * cannot contain. See [resolvePerson] for why the bar is deliberately this high.
         */
        private val HANDLE_SHAPED =
            Regex("^(?=.*[0-9_-])[A-Za-z0-9](?:[A-Za-z0-9_]|-(?=[A-Za-z0-9_])){0,38}$")

        /**
         * Six months: long enough that a quiet stretch does not read as a dead project, short
         * enough that the people row is still a scoreboard rather than a monument. At this
         * project's rate that is ~44 commits, which is one unpaginated request. Overridable in
         * settings.
         */
        const val DEFAULT_WINDOW_MONTHS = 6

        private const val DAYS_PER_MONTH = 30L

        private const val REVALIDATE_MINUTES = 30L

        private val PR_SUFFIX = Regex("""\(#(\d+)\)\s*$""")

        private val LAST_PAGE = Regex("""[?&]page=(\d+)>;\s*rel="last"""")

        private val CO_AUTHOR =
            Regex("""(?im)^\s*Co-authored-by:\s*(.+?)\s*<([^>]+)>\s*$""")

        private val GITHUB_NOREPLY =
            Regex("""^(?:(\d+)\+)?([^@]+)@users\.noreply\.github\.com$""", RegexOption.IGNORE_CASE)
    }
}
