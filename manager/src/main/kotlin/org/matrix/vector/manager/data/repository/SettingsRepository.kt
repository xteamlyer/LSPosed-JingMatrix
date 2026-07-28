package org.matrix.vector.manager.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_settings", Context.MODE_PRIVATE)

    // Theme Settings
    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _amoledBlack = MutableStateFlow(prefs.getBoolean("amoled_black", false))
    val amoledBlack: StateFlow<Boolean> = _amoledBlack.asStateFlow()

    /**
     * The colour every other colour is derived from, when dynamic colour is off.
     *
     * Stored as an ARGB int rather than a preset index so that a colour picked from the wheel
     * survives a reinstall and does not depend on the preset list staying the same order.
     */
    private val _seedColor = MutableStateFlow(prefs.getInt("seed_color", DEFAULT_SEED_COLOR))
    val seedColor: StateFlow<Int> = _seedColor.asStateFlow()

    fun setSeedColor(argb: Int) {
        prefs.edit().putInt("seed_color", argb).apply()
        _seedColor.value = argb
    }

    // Updates & Network
    private val _updateChannel =
        MutableStateFlow(prefs.getString("update_channel", "stable") ?: "stable")
    val updateChannel: StateFlow<String> = _updateChannel.asStateFlow()

    private val _dohEnabled = MutableStateFlow(prefs.getBoolean("doh_enabled", false))
    val dohEnabled: StateFlow<Boolean> = _dohEnabled.asStateFlow()

    // --- Home activity feed ---

    /**
     * How far back the Home activity feed reaches, in months.
     *
     * Six is the default: long enough that a quiet stretch does not read as a dead project, short
     * enough that the contributor row still moves. A busy fork may want less, someone tracking a
     * slow-moving release may want more, so it is theirs to set.
     */
    private val _activityWindowMonths = MutableStateFlow(prefs.getInt("activity_window_months", 6))
    val activityWindowMonths: StateFlow<Int> = _activityWindowMonths.asStateFlow()

    /**
     * Whether GitHub links leave the app.
     *
     * Off by default: the built-in viewer keeps the user in Vector, which matters most in
     * parasitic mode where "the app" is really the shell process and handing off to a browser is a
     * jarring context switch out of something that does not look like an app to the system.
     */
    private val _openLinksExternally =
        MutableStateFlow(prefs.getBoolean("open_links_externally", false))
    val openLinksExternally: StateFlow<Boolean> = _openLinksExternally.asStateFlow()

    /**
     * How the contributor row is ordered: by how much someone has done, or by how recently.
     *
     * Both are honest and they honour different people. Volume puts the maintainer first forever,
     * which is accurate and unchanging; recency puts whoever last landed something at the front,
     * which is what makes a first contribution visible the day it happens.
     */
    private val _contributorOrder =
        MutableStateFlow(prefs.getString("contributor_order", "commits") ?: "commits")
    val contributorOrder: StateFlow<String> = _contributorOrder.asStateFlow()

    fun setContributorOrder(key: String) {
        prefs.edit().putString("contributor_order", key).apply()
        _contributorOrder.value = key
    }

    /**
     * The language the app is shown in, as a BCP-47 tag, or empty for whatever the system says.
     *
     * Not `setApplicationLocales`: that API is keyed on an installed package, and parasitically
     * this one is never installed. Asking the framework would change the host's language or
     * nothing at all. See LocalizedContent for how the override is applied instead.
     */
    private val _appLocale = MutableStateFlow(prefs.getString("app_locale", "") ?: "")
    val appLocale: StateFlow<String> = _appLocale.asStateFlow()

    fun setAppLocale(tag: String) {
        prefs.edit().putString("app_locale", tag).apply()
        _appLocale.value = tag
    }

    /**
     * Modules the reader has told us to stop nagging about.
     *
     * In the manager's own preferences rather than in the daemon's module database, because this is
     * a fact about *this reader's opinion of the catalogue*, not about the module: the daemon has
     * never heard of the catalogue, does not know a remote version exists, and would have to be
     * taught the whole notion to store one boolean. Muting also has to survive a module being
     * uninstalled and reinstalled, which a daemon-side per-module row would not.
     */
    private val _mutedUpdates =
        MutableStateFlow(prefs.getStringSet("muted_updates", emptySet())?.toSet() ?: emptySet())
    val mutedUpdates: StateFlow<Set<String>> = _mutedUpdates.asStateFlow()

    fun setUpdatesMuted(packageName: String, muted: Boolean) {
        val next =
            if (muted) _mutedUpdates.value + packageName else _mutedUpdates.value - packageName
        // A fresh set, not the one handed out: SharedPreferences keeps the instance it is given and
        // documents that mutating it afterwards is undefined.
        prefs.edit().putStringSet("muted_updates", HashSet(next)).apply()
        _mutedUpdates.value = next
    }

    /** Which living surface the status header draws. See AmbienceKind. */
    private val _headerAmbience =
        MutableStateFlow(prefs.getString("header_ambience", DEFAULT_AMBIENCE) ?: DEFAULT_AMBIENCE)
    val headerAmbience: StateFlow<String> = _headerAmbience.asStateFlow()

    /**
     * How big, and how fast, each ambience draws itself.
     *
     * Per kind rather than global: a comfortable glyph size for the code rain says nothing about
     * how large a maze cell should be, and someone who has tuned one and switches away should find
     * it as they left it. Written straight through on every gesture — these are a handful of bytes
     * and the alternative is losing the adjustment to the next process death.
     */
    private val _updateVariant =
        MutableStateFlow(prefs.getString("update_variant", "release") ?: "release")

    /**
     * Which build of the framework to install, "release" or "debug".
     *
     * Remembered because someone who wants debug builds wants them every time — a maintainer
     * chasing a bug report is not making a fresh decision on each update — and because the choice
     * is otherwise invisible until the download size appears.
     */
    val updateVariant: StateFlow<String> = _updateVariant.asStateFlow()

    fun setUpdateVariant(key: String) {
        prefs.edit().putString("update_variant", key).apply()
        _updateVariant.value = key
    }

    fun ambienceScale(kind: String): Float = prefs.getFloat("ambience_scale_$kind", 1f)

    fun setAmbienceScale(kind: String, value: Float) {
        prefs.edit().putFloat("ambience_scale_$kind", value).apply()
    }

    fun ambienceSpeed(kind: String): Float = prefs.getFloat("ambience_speed_$kind", 1f)

    fun setAmbienceSpeed(kind: String, value: Float) {
        prefs.edit().putFloat("ambience_speed_$kind", value).apply()
    }

    fun setHeaderAmbience(key: String) {
        prefs.edit().putString("header_ambience", key).apply()
        _headerAmbience.value = key
    }

    fun setActivityWindowMonths(months: Int) {
        prefs.edit().putInt("activity_window_months", months).apply()
        _activityWindowMonths.value = months
    }

    fun setOpenLinksExternally(enabled: Boolean) {
        prefs.edit().putBoolean("open_links_externally", enabled).apply()
        _openLinksExternally.value = enabled
    }

    // --- Logs ---

    /**
     * Whether log lines wrap rather than pan sideways.
     *
     * Persisted because it is a reading preference, not a transient view state: parasitically the
     * manager lives inside `com.android.shell`, whose process is killed routinely, so anything held
     * only in a ViewModel resets far more often than a user would expect.
     */
    private val _logWordWrap = MutableStateFlow(prefs.getBoolean("log_word_wrap", true))
    val logWordWrap: StateFlow<Boolean> = _logWordWrap.asStateFlow()

    fun setLogWordWrap(enabled: Boolean) {
        prefs.edit().putBoolean("log_word_wrap", enabled).apply()
        _logWordWrap.value = enabled
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setAmoledBlack(enabled: Boolean) {
        prefs.edit().putBoolean("amoled_black", enabled).apply()
        _amoledBlack.value = enabled
    }

    fun setUpdateChannel(channel: String) {
        prefs.edit().putString("update_channel", channel).apply()
        _updateChannel.value = channel
    }

    fun setDohEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("doh_enabled", enabled).apply()
        _dohEnabled.value = enabled
    }

    private companion object {
        /** The Winged Victory's patina. Kept as a literal so this file needs no UI imports. */
        const val DEFAULT_SEED_COLOR = 0xFF6ABFCF.toInt()

        /**
         * Matches an `AmbienceKind` key. It used to read "ripple", a surface that was replaced long
         * ago — harmless, because an unknown key falls back, but a stored default that names
         * nothing is a lie waiting to be believed by whoever reads the preferences next.
         */
        const val DEFAULT_AMBIENCE = "maze"
    }
}
