package com.bydmate.app.cluster

import android.content.Context
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.split.SplitPreferencesImpl

/** A BYDMate feature that takes a steering key before the automation rules see it. */
enum class SteeringKeyOwner { VOICE, KNOB, PROJECTION, SPLIT }

/** What each key-owning feature has switched on and on which key. */
data class SteeringKeyOwners(
    val projectionEnabled: Boolean,
    val projectionKey: Int,
    val voiceEnabled: Boolean,
    val voiceKey: Int,
    val voiceCompanions: Set<Int>,
    val knobEnabled: Boolean,
    val splitEnabled: Boolean,
    val splitKey: Int,
    val splitCompanions: Set<Int> = emptySet(),
)

/**
 * Who owns [keyCode], in SteeringWheelKeyService's own order (voice, knob, projection, split);
 * null = free. The companion codes of the voice and split buttons' presses go to their owner as well.
 */
fun steeringKeyOwner(keyCode: Int, o: SteeringKeyOwners): SteeringKeyOwner? = when {
    o.voiceEnabled && (keyCode == o.voiceKey || keyCode in o.voiceCompanions) -> SteeringKeyOwner.VOICE
    o.knobEnabled && keyCode == VOLUME_KNOB_PRESS_KEYCODE -> SteeringKeyOwner.KNOB
    o.projectionEnabled && keyCode == o.projectionKey -> SteeringKeyOwner.PROJECTION
    o.splitEnabled && o.splitKey > 0 && (keyCode == o.splitKey || keyCode in o.splitCompanions) ->
        SteeringKeyOwner.SPLIT
    else -> null
}

/**
 * Owner of [keyCode] for the split key chooser: a feature's SAVED key counts whether or not the
 * feature is on, so switching it on later cannot let its earlier filter stage eat the split key.
 */
fun splitKeyChooserOwner(keyCode: Int, o: SteeringKeyOwners): SteeringKeyOwner? = when {
    keyCode == o.voiceKey || keyCode in o.voiceCompanions -> SteeringKeyOwner.VOICE
    keyCode == VOLUME_KNOB_PRESS_KEYCODE -> SteeringKeyOwner.KNOB
    keyCode == o.projectionKey -> SteeringKeyOwner.PROJECTION
    else -> null
}

/** Reads the owners from the features' prefs (cluster, "voice", "bydmate_split"). */
fun readSteeringKeyOwners(context: Context): SteeringKeyOwners {
    val clusterPrefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    val voicePrefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    val split = SplitPreferencesImpl(context)
    return SteeringKeyOwners(
        projectionEnabled = clusterPrefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false),
        projectionKey = clusterPrefs.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE),
        voiceEnabled = voicePrefs.getBoolean("voice_enabled", false),
        voiceKey = voicePrefs.getInt("voice_keycode", DEFAULT_VOICE_KEYCODE),
        voiceCompanions = voiceCompanionsFromCsv(voicePrefs.getString(SettingsRepository.KEY_VOICE_COMPANIONS, null)),
        knobEnabled = clusterPrefs.getBoolean(ClusterProjectionManager.KEY_KNOB_PLAY_PAUSE, false),
        splitEnabled = split.isFeatureEnabled(),
        splitKey = split.getSteeringKeyCode(),
        splitCompanions = split.getSteeringKeyCompanions(),
    )
}
