package com.shaun.agentbox.ui

import android.content.Context

/**
 * Persisted feature switches for AgentBox floating controls and keep-alive helpers.
 *
 * These options are intentionally stored outside Compose state so foreground services
 * can read the same source of truth after being started/recreated by Android.
 */
data class FloatingFeatureOptions(
    val circularFloatingWindow: Boolean = true,
    val transparentFloatingWindow: Boolean = false,
    val disguisedAudioKeepAlive: Boolean = false
)

class FloatingFeatureConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): FloatingFeatureOptions = FloatingFeatureOptions(
        circularFloatingWindow = prefs.getBoolean(KEY_CIRCULAR_FLOATING_WINDOW, true),
        transparentFloatingWindow = prefs.getBoolean(KEY_TRANSPARENT_FLOATING_WINDOW, false),
        disguisedAudioKeepAlive = prefs.getBoolean(KEY_DISGUISED_AUDIO_KEEP_ALIVE, false)
    )

    fun save(options: FloatingFeatureOptions) {
        prefs.edit()
            .putBoolean(KEY_CIRCULAR_FLOATING_WINDOW, options.circularFloatingWindow)
            .putBoolean(KEY_TRANSPARENT_FLOATING_WINDOW, options.transparentFloatingWindow)
            .putBoolean(KEY_DISGUISED_AUDIO_KEEP_ALIVE, options.disguisedAudioKeepAlive)
            .apply()
    }

    fun update(transform: (FloatingFeatureOptions) -> FloatingFeatureOptions): FloatingFeatureOptions {
        val updated = transform(load())
        save(updated)
        return updated
    }

    companion object {
        private const val PREFS_NAME = "floating_feature_options"
        private const val KEY_CIRCULAR_FLOATING_WINDOW = "circular_floating_window"
        private const val KEY_TRANSPARENT_FLOATING_WINDOW = "transparent_floating_window"
        private const val KEY_DISGUISED_AUDIO_KEEP_ALIVE = "disguised_audio_keep_alive"
    }
}
