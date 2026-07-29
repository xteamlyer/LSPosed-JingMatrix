package org.matrix.vector.manager.ui.theme

import java.util.Locale

/** Someone who translated the app, and where to find them if they want to be found. */
data class Translator(val name: String, val url: String? = null)

/**
 * Who to thank for each language.
 *
 * Deliberately a reviewed file rather than a translatable string. The obvious design is a
 * `translator` key per language that whoever claims the language fills in with their own name —
 * except that a name here is rendered beside a *link*, and translation rights on Crowdin are
 * cheaper to obtain than commit rights on this repository. A URL that arrives through the
 * translation pipeline is a URL nobody reviewed, shown inside the app under the project's name.
 *
 * Adding yourself is one line in this file, in the same pull request that brings your translation
 * down from Crowdin, and a maintainer sees it. That is the whole point.
 *
 * Empty is a correct state and the rows simply carry no credit line: at the time of writing every
 * language here was machine-assisted and none of them has an author who should be named for it.
 */
val TRANSLATORS: Map<String, List<Translator>> =
    mapOf(
        // "de" to listOf(Translator("Your Name", "https://github.com/you")),
    )

/**
 * The credits for a language, tolerating the two ways Android and Java disagree about a tag.
 *
 * Indonesian is `in` in a resource folder and `id` in a language tag, and Hebrew is `iw` against
 * `he`; matching on the bare language as well as the full tag means a contributor can key their
 * entry either way and it still finds them.
 */
fun translatorsFor(locale: Locale): List<Translator> =
    TRANSLATORS[locale.toLanguageTag()]
        ?: TRANSLATORS[locale.language]
        ?: TRANSLATORS[locale.toLanguageTag().substringBefore('-')]
        ?: emptyList()

/** Where a translation is actually made. */
const val CROWDIN_URL = "https://crowdin.com/project/lsposed_jingmatrix"
