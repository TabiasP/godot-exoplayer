package org.godotengine.plugin.android.godot_exoplayer

import android.net.Uri

/** Immutable native configuration for one XR ExoPlayer session. */
internal data class PlayerConfig(
    val uri: Uri,
    val autoplay: Boolean,
    val volume: Float,
    val repeatMode: Int,
    val playbackSpeed: Float,
    val useCache: Boolean,
    val cacheMaxBytes: Long,
    val requestHeaders: Map<String, String>,
    val userAgent: String?,
    val allowCrossProtocolRedirects: Boolean,
    val pauseOnAppPause: Boolean,
    val parseProgramDateTime: Boolean,
    val debugLogging: Boolean,
    val routeAudioToGodot: Boolean,
    val drm: DrmConfig?
)

/** Optional DRM configuration; a null value always means clear playback. */
internal data class DrmConfig(
    val scheme: String,
    val licenseUrl: String,
    val requestHeaders: Map<String, String>
)
