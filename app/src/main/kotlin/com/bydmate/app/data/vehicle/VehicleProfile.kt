package com.bydmate.app.data.vehicle

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted per-car profile. Backed by the same `cluster_projection` SharedPreferences file the
 * other cluster keys live in (cheap to add to and avoids fragmenting prefs). Read on cold start
 * by [SteeringWheelKeyService] and by the Car section in Settings.
 *
 * The selected model is the source of truth for the default cluster trigger keycode ONLY when
 * the user has not yet assigned one (i.e. the stored keycode is [NO_TRIGGER_KEYCODE]). Once the
 * user picks a button via the learn dialog, their choice is preserved across model changes.
 */
class VehicleProfile(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getModel(): VehicleModel =
        VehicleModel.fromName(prefs.getString(KEY_MODEL, null))

    /** Persist the user's choice. `null` is not accepted — use [clearModel] to revert to autodetect. */
    fun setModel(model: VehicleModel) {
        prefs.edit().putString(KEY_MODEL, model.name).apply()
    }

    fun clearModel() {
        prefs.edit().remove(KEY_MODEL).apply()
    }

    /**
     * True when the user (or autodetect) has explicitly chosen a model. Lets the settings UI
     * distinguish "user override" from "fresh install, no value yet".
     */
    fun hasExplicitModel(): Boolean = prefs.contains(KEY_MODEL)

    companion object {
        // Reuse the cluster_projection prefs file so we don't fragment storage; this is a related
        // setting (per-model defaults for cluster projection specifically).
        const val PREFS_NAME = "cluster_projection"
        const val KEY_MODEL = "vehicle_model"
    }
}
