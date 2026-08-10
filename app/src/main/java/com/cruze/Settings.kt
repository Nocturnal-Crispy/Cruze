package com.cruze

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * User preferences, backed by SharedPreferences and observable from Compose.
 *
 * A singleton because plain functions like [fmtDist] and the foreground service both need to
 * read these, and neither can reach a ViewModel. Every value here is wired to real behaviour —
 * a settings screen full of switches that do nothing is worse than no settings screen.
 */
object Settings {

    private lateinit var prefs: SharedPreferences

    /** Miles, feet and mph, or kilometres, metres and km/h. */
    var metric by mutableStateOf(false)
        private set

    var darkTheme by mutableStateOf(true)
        private set

    var voiceGuidance by mutableStateOf(true)
        private set

    var keepScreenOn by mutableStateOf(true)
        private set

    var avoidTolls by mutableStateOf(true)
        private set

    var avoidFerries by mutableStateOf(true)
        private set

    var avoidUnpaved by mutableStateOf(true)
        private set

    /** Name broadcast to the group, so it need not be retyped every ride. */
    var riderName by mutableStateOf("")
        private set

    var fallDetection by mutableStateOf(true)
        private set

    /** Seconds to cancel a false alarm before the group is told. */
    var fallCountdownSec by mutableStateOf(30)
        private set

    /** Metres behind the leader before a rider counts as lost. */
    var lostRiderThresholdM by mutableStateOf(1600.0)
        private set

    var defaultStyle by mutableStateOf("CURVY")
        private set

    var defaultLayer by mutableStateOf("DARK")
        private set

    /**
     * Group relay. Blank means "use whichever public relay is working" — see [PUBLIC_RELAYS].
     * A rider who wants their own ntfy server sets it here and it is then used exclusively.
     */
    var relayUrl by mutableStateOf("")
        private set

    /**
     * The public ntfy instances used when no relay has been chosen — **all of them, at once**,
     * not one with the others in reserve. See [com.cruze.sync.NtfyTransport] for why: riders
     * cannot agree to switch relays over a channel that has just broken, so switching splits
     * the group. Talking on all of them cannot.
     *
     * ntfy.sh is deliberately NOT here: its anonymous tier has a daily message quota that a
     * single phone exhausts in well under an hour of riding — one position every 12 s is about
     * 300 messages an hour against a 250-a-day cap — and when it runs out, positions simply
     * stop with no warning. These run stock ntfy, which rate-limits by burst but sets no daily
     * cap, so a full day's riding fits. Three, because one being down must be survivable and
     * every extra one costs another publish per position.
     */
    val PUBLIC_RELAYS = listOf(
        "https://ntfy.envs.net",
        "https://ntfy.adminforge.de",
        "https://ntfy.hostux.net",
    )

    /** Only meaningful as "the first of the public set"; nothing picks a single relay any more. */
    val DEFAULT_RELAY: String get() = PUBLIC_RELAYS.first()

    /** True when the rider has not named their own server, so failover is allowed to move. */
    val usingPublicRelay: Boolean get() = relayUrl.isBlank()

    /** Retired default, migrated away from on load because it cannot survive a ride. */
    private const val EXHAUSTED_RELAY = "https://ntfy.sh"

    fun load(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("cruze", Context.MODE_PRIVATE)
        metric = prefs.getBoolean("metric", false)
        darkTheme = prefs.getBoolean("darkTheme", true)
        voiceGuidance = prefs.getBoolean("voiceGuidance", true)
        keepScreenOn = prefs.getBoolean("keepScreenOn", true)
        avoidTolls = prefs.getBoolean("avoidTolls", true)
        avoidFerries = prefs.getBoolean("avoidFerries", true)
        avoidUnpaved = prefs.getBoolean("avoidUnpaved", true)
        riderName = prefs.getString("riderName", "").orEmpty()
        fallDetection = prefs.getBoolean("fallDetection", true)
        fallCountdownSec = prefs.getInt("fallCountdownSec", 30)
        lostRiderThresholdM = prefs.getFloat("lostRiderThresholdM", 1600f).toDouble()
        defaultStyle = prefs.getString("defaultStyle", "CURVY").orEmpty()
        defaultLayer = prefs.getString("defaultLayer", "DARK").orEmpty()
        // Existing installs are pinned to the old default in their prefs; move them off it,
        // or they keep riding into a daily quota that stops position sharing mid-ride.
        relayUrl = prefs.getString("relayUrl", "").orEmpty()
            .let { if (it == EXHAUSTED_RELAY) "" else it }
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        if (::prefs.isInitialized) prefs.edit().apply(block).apply()
    }

    fun updateMetric(v: Boolean) { metric = v; edit { putBoolean("metric", v) } }
    fun updateDarkTheme(v: Boolean) { darkTheme = v; edit { putBoolean("darkTheme", v) } }
    fun updateVoiceGuidance(v: Boolean) { voiceGuidance = v; edit { putBoolean("voiceGuidance", v) } }
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; edit { putBoolean("keepScreenOn", v) } }
    fun updateAvoidTolls(v: Boolean) { avoidTolls = v; edit { putBoolean("avoidTolls", v) } }
    fun updateAvoidFerries(v: Boolean) { avoidFerries = v; edit { putBoolean("avoidFerries", v) } }
    fun updateAvoidUnpaved(v: Boolean) { avoidUnpaved = v; edit { putBoolean("avoidUnpaved", v) } }
    fun updateRiderName(v: String) { riderName = v; edit { putString("riderName", v) } }
    fun updateFallDetection(v: Boolean) { fallDetection = v; edit { putBoolean("fallDetection", v) } }

    fun updateFallCountdown(v: Int) {
        // Too short to react in gloves is worse than useless; too long defeats the point.
        val clamped = v.coerceIn(10, 120)
        fallCountdownSec = clamped
        edit { putInt("fallCountdownSec", clamped) }
    }

    fun updateLostRiderThreshold(metres: Double) {
        val clamped = metres.coerceIn(200.0, 16000.0)
        lostRiderThresholdM = clamped
        edit { putFloat("lostRiderThresholdM", clamped.toFloat()) }
    }

    fun updateDefaultStyle(v: String) { defaultStyle = v; edit { putString("defaultStyle", v) } }
    fun updateDefaultLayer(v: String) { defaultLayer = v; edit { putString("defaultLayer", v) } }

    /** Blank clears it back to the public relays and their failover. */
    fun updateRelayUrl(v: String) {
        val clean = v.trim().trimEnd('/')
        relayUrl = clean
        edit { putString("relayUrl", clean) }
    }
}
