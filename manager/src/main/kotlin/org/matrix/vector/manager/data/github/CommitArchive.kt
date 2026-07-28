package org.matrix.vector.manager.data.github

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Every commit the app has ever seen, kept on disk.
 *
 * The activity feed used to be one request — the newest hundred commits within a date window — and
 * "from the start of the project" cannot be answered that way: `/commits` returns at most a hundred
 * per request and there is no widening the window past that. The history has to be walked backwards
 * a page at a time and remembered, which is what this is.
 *
 * ## Why an append-only file
 *
 * A commit that is not at the tip never changes. Its message, its author and its date are fixed the
 * moment it is buried under another commit, so the overwhelming majority of this archive is
 * immutable and rewriting it would be pure waste. New pages are *appended*, one JSON object per
 * line, which costs the length of the chunk rather than the length of the history — the difference
 * between writing 20 KB and rewriting 600 KB, every time, on a phone.
 *
 * The head is the exception. The newest commits can still be amended, rebased or force-pushed away,
 * so the refresh rewrites that window on every run. Appending a second copy of a commit is
 * therefore normal rather than a bug, and [read] resolves it: later lines win, so the freshest
 * record of any SHA is the one that survives. The file is compacted when the duplicates outgrow the
 * real content, which for a normal refresh rhythm is rarely.
 *
 * ## Why SHA is the key
 *
 * It is the only identifier git guarantees. Dates collide — several commits share a second, and the
 * page boundary is *chosen* by date, so the same commit legitimately arrives twice — and positions
 * shift as new work lands. Keying on the SHA makes an overlapping page harmless, which in turn lets
 * the backfill cursor be a date rather than a page number. That matters: page numbers are
 * invalidated by every new commit, dates are not.
 */
class CommitArchive(private val file: File, private val stateFile: File, private val json: Json) {

    /**
     * Where the backwards walk has reached.
     *
     * [oldestSeenEpochSeconds] is the cursor — the next request asks for commits at or before it —
     * and [complete] records that a request came back empty, which is the only signal that the
     * history has genuinely run out. All of it is persisted because the walk is spread across
     * sessions: an anonymous client gets sixty requests an hour, and a few thousand commits is
     * thirty of them, so finishing in one sitting is neither possible nor polite.
     *
     * [pageWithinCursor] is what makes the walk correct on a repository that has been squashed or
     * imported. This one has 100+ commits stamped with the same second — `2023-02-26T08:48:49Z` —
     * and a cursor alone cannot get past them: asking for commits at or before that second returns
     * the same hundred every time, and the walk stalls a fifth of the way through history while
     * looking exactly like success. Inside such a plateau the walk pages by number instead, which
     * is safe here in a way it is not in general: the page window is anchored by `until` at a
     * moment in the past, so new commits land outside it and cannot shift it.
     */
    @Serializable
    data class State(
        val oldestSeenEpochSeconds: Long = 0,
        val complete: Boolean = false,
        /** Purely for the log line that explains a slow or stalled backfill. */
        val pagesFetched: Int = 0,
        /** Which page of the current cursor's timestamp to ask for next; 1 unless in a plateau. */
        val pageWithinCursor: Int = 1,
        /**
         * The walk that wrote this file.
         *
         * A cursor left behind by an older, wronger walk is worse than no cursor at all — the one
         * that stalled on the plateau above saved `complete: true`, and honouring that would mean
         * never looking again. Anything not written by the current walk is re-walked from the top.
         */
        val algorithm: Int = 0,
    )

    companion object {
        /** Bump when the walk changes in a way that invalidates a saved cursor. */
        const val ALGORITHM = 1
    }

    fun state(): State =
        runCatching { json.decodeFromString<State>(stateFile.readText()) }
            .getOrDefault(State())
            .let { if (it.algorithm == ALGORITHM) it else State(algorithm = ALGORITHM) }

    fun writeState(state: State) {
        runCatching { stateFile.writeText(json.encodeToString(state.copy(algorithm = ALGORITHM))) }
    }

    /**
     * Everything held, newest first, one record per SHA.
     *
     * Later lines win, which is what makes appending a rewritten head window correct rather than
     * corrupting: the newest copy of a commit is the one that ends up furthest down the file.
     */
    fun read(): List<GhCommit> {
        parsed?.let {
            return it
        }
        return parse().also { parsed = it }
    }

    /**
     * The parsed file, held for as long as it is unchanged.
     *
     * One load reads this twice — once to lay out the feed, once to know what the backfill already
     * has — and a backfill reads it again after appending. At three thousand commits that is three
     * passes over four megabytes of JSON per visit to the foot of the list, all of it to reproduce
     * a list that has not changed. Every writer here invalidates it, so there is no way to hold a
     * stale copy.
     */
    @Volatile private var parsed: List<GhCommit>? = null

    private fun parse(): List<GhCommit> {
        if (!file.isFile) return emptyList()
        val byShaLatestWins = LinkedHashMap<String, GhCommit>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { json.decodeFromString<GhCommit>(line) }
                    .getOrNull()
                    // A truncated final line — a process killed mid-append — costs that one commit
                    // and nothing else. It is why this is a line format and not one JSON document.
                    ?.let { byShaLatestWins[it.sha] = it }
            }
        }
        return byShaLatestWins.values.sortedByDescending { it.commit.author.date }
    }

    /** Appends a chunk. Duplicates are expected and are resolved on read, not here. */
    fun append(commits: List<GhCommit>) {
        if (commits.isEmpty()) return
        parsed = null
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(commits.joinToString("\n", postfix = "\n") { json.encodeToString(it) })
        }
    }

    /**
     * Rewrites the file with one line per commit, dropping the superseded copies.
     *
     * Only worth doing when the duplicates have grown past the content itself, which takes a great
     * many refreshes — the head window is a hundred commits and a full history is thousands.
     */
    fun compactIfWasteful() {
        if (!file.isFile) return
        val lines = runCatching { file.readLines().count { it.isNotBlank() } }.getOrDefault(0)
        val unique = read()
        if (lines <= unique.size * 2) return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(unique.joinToString("\n", postfix = "\n") { json.encodeToString(it) })
            tmp.renameTo(file)
            parsed = unique
        }
    }
}
