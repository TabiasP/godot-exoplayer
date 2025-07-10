package org.godotengine.plugin.android.godot_exoplayer

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.Log as ExoLogger
import org.godotengine.godot.*
import org.godotengine.godot.plugin.*
import java.util.concurrent.CountDownLatch

class GodotAndroidPlugin(godot: Godot) : GodotPlugin(godot) {

    // --- Plugin name and signals ---

    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME

    override fun getPluginSignals() = mutableSetOf(
        SignalInfo("on_player_ready", Integer::class.java, Integer::class.java),
        SignalInfo("on_video_end", Integer::class.java),
        SignalInfo("on_player_error", Integer::class.java, String::class.java)
    )

    // --- State ---

    private val exoPlayers = mutableMapOf<Int, ExoPlayer>()
    private val drmConfigurations = mutableMapOf<Int, Dictionary>()

    // --- ExoPlayer Management ---

    @OptIn(UnstableApi::class)
    @UsedByGodot
    fun createExoPlayerSurface(id: Int, videoUri: String, surface: Surface) = runOnUiThread {
        try {
            ExoLogger.setLogLevel(ExoLogger.LOG_LEVEL_ALL)
            exoPlayers[id]?.release()

            val player = activity?.let { ExoPlayer.Builder(it).build() }
                ?: return@runOnUiThread emitAndLogError(id, "Failed to create ExoPlayer for id $id")

            player.setVideoSurface(surface)
            player.addListener(createPlayerListener(id))
            player.addAnalyticsListener(EventLogger())

            // ---- BEGIN: DRM-specific setup ----
            var mediaSourceFactory = DefaultMediaSourceFactory(activity as Context)
            val mediaItem = MediaItem.fromUri(videoUri)

            if (drmConfigurations.containsKey(id)){
                val dataDict = drmConfigurations[id]
                val licenseUrl = dataDict?.get("licenseUrl") as? String ?: ""
                val customHeaders = mutableMapOf<String, String>()
                dataDict?.forEach { (key, value) ->
                    if (key != "licenseUrl" && value is String) {
                        customHeaders[key as String] = value
                    }
                }
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                val drmCallback = HttpMediaDrmCallback(licenseUrl, httpDataSourceFactory)
                customHeaders.forEach { (k,v) -> drmCallback.setKeyRequestProperty(k,v) }
                val drmManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { FrameworkMediaDrm.newInstance(it) }
                    .build(drmCallback)
                mediaSourceFactory = mediaSourceFactory.setDrmSessionManagerProvider { drmManager }
                Log.v(pluginName, "ExoPlayer($id) configured with Widevine DRM, license URL: $licenseUrl")
            } else {
                Log.v(pluginName, "\"No DRM config for ExoPlayer($id), using non-DRM MediaItem\"")
            }
            // ---- END: DRM-specific setup ----
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
            player.prepare()
            exoPlayers[id] = player

            Log.v(pluginName, "ExoPlayer($id) created and set up with video: $videoUri")
        } catch (e: Exception) {
            emitAndLogError(id, "Error creating ExoPlayer($id): ${e.message}")
        }
    }

    @UsedByGodot
    fun releaseExoPlayer(id: Int) = runOnUiThread {
        exoPlayers.remove(id)?.release()?.also {
            Log.v(pluginName, "ExoPlayer($id) released and removed.")
        } ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to release")
    }

    // --- Playback Controls ---

    @UsedByGodot
    fun play(id: Int) = runOnUiThread { exoPlayers[id]?.play() ?: logNotFound(id, "play") }

    @UsedByGodot
    fun pause(id: Int) = runOnUiThread { exoPlayers[id]?.pause() ?: logNotFound(id, "pause") }

    @UsedByGodot
    fun seekTo(id: Int, positionMs: Long) = runOnUiThread { exoPlayers[id]?.seekTo(positionMs) ?: logNotFound(id, "seek") }

    @UsedByGodot
    fun seekBy(id: Int, deltaMs: Long) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            val newPos = if (player.duration != C.TIME_UNSET)
                (player.currentPosition + deltaMs).coerceIn(0, player.duration)
            else (player.currentPosition + deltaMs).coerceAtLeast(0)
            player.seekTo(newPos)
        } ?: logNotFound(id, "seekBy")
    }

    // --- Volume ---

    @UsedByGodot
    fun setVolume(id: Int, volume: Float) = runOnUiThread {
        exoPlayers[id]?.volume = (volume.coerceIn(0f, 1f)
            ?: logNotFound(id, "setVolume")) as Float
    }

    @UsedByGodot
    fun getVolume(id: Int): Float = exoPlayers[id]?.volume ?: -1f

    // --- Tracks & Resolutions ---

    @OptIn(UnstableApi::class)
    @UsedByGodot
    fun getResolutions(id: Int): Array<String> = runAndWaitUI {
        exoPlayers[id]?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_VIDEO }
            ?.flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    val f = group.getTrackFormat(i)
                    if (f.width > 0 && f.height > 0)
                        "${f.width}x${f.height} - ${f.bitrate / 1000} kbps"
                    else null
                }
            } ?: emptyList()
    }.toTypedArray()

    @UsedByGodot
    fun setResolution(id: Int, width: Int, height: Int) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(width, height).build()
            Log.v(pluginName, "ExoPlayer($id) set max size ${width}x$height")
        } ?: logNotFound(id, "setResolution")
    }

    @UsedByGodot
    fun getAudioTracks(id: Int): Array<Dictionary> = runAndWaitUI {
        val audioTracks = ArrayList<Dictionary>()
        exoPlayers[id]?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }?.forEach { group ->
            (0 until group.length).forEach { i ->
                val f = group.getTrackFormat(i)
                audioTracks.add(Dictionary().apply {
                    put("index", i)
                    put("language", f.language ?: "und")
                    put("channels", f.channelCount)
                    put("sampleRate", f.sampleRate)
                })
            }
        }
        audioTracks
    }.toTypedArray()

    @UsedByGodot
    fun setAudioTrack(id: Int, audioTrackIndex: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setAudioTrack")
        val tracks = player.currentTracks
        var idx = 0
        var selectedGroupIndex: Int? = null
        var selectedTrackInGroup: Int? = null

        for ((groupIndex, group) in tracks.groups.withIndex()) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    if (idx == audioTrackIndex) {
                        selectedGroupIndex = groupIndex
                        selectedTrackInGroup = trackIndex
                        break
                    }
                    idx++
                }
                if (selectedGroupIndex != null) break
            }
        }
        if (selectedGroupIndex == null || selectedTrackInGroup == null)
            return@runOnUiThread

        val language = tracks.groups[selectedGroupIndex].getTrackFormat(selectedTrackInGroup).language ?: "und"
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(language).build()
        Log.v(pluginName, "ExoPlayer($id) set audio track $audioTrackIndex")
    }

    // --- Position & Duration ---

    @UsedByGodot
    fun getCurrentPosition(id: Int): Long = runCatching {
        runAndWaitUI { exoPlayers[id]?.currentPosition ?: -1L }
    }.getOrElse { -1L }

    @UsedByGodot
    fun getDuration(id: Int): Float = exoPlayers[id]?.let { player ->
        if (player.playbackState == Player.STATE_READY && player.duration != C.TIME_UNSET)
            player.duration.toFloat() else -1f
    } ?: -1f

    // --- DRM Setup ---

    @UsedByGodot
    fun setupWidevine(id: Int, data: Dictionary) {
        drmConfigurations[id] = data
    }

    // --- Player Listener ---

    private fun createPlayerListener(id: Int) = object : Player.Listener {
        @OptIn(UnstableApi::class)
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    exoPlayers[id]?.let { player ->
                        val duration = if (player.duration == C.TIME_UNSET) -1 else player.duration
                        Log.v(pluginName, "ExoPlayer($id) ready, duration: $duration")
                        logTrackDebugInfo(id, player)
                        emitSignal("on_player_ready", id, duration.toInt())
                    }
                }
                Player.STATE_ENDED -> emitSignal("on_video_end", id)
                Player.STATE_BUFFERING -> Log.v(pluginName, "ExoPlayer($id) buffering")
                Player.STATE_IDLE -> Log.v(pluginName, "ExoPlayer($id) idle")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            emitSignal("on_player_error", id, error.message)
        }
    }

    // --- Plugin Cleanup ---

    override fun onMainDestroy() {
        runOnUiThread {
            exoPlayers.values.forEach { it.release() }
            exoPlayers.clear()
        }
        super.onMainDestroy()
    }

    // --- Helpers ---

    private fun buildMediaItem(id: Int, videoUri: String): MediaItem {
        val drmConfig = drmConfigurations[id]?.let { dataDict ->
            val licenseURL = dataDict["licenseUrl"] as? String ?: ""
            Log.v(pluginName, "Applying Widevine DRM for ExoPlayer($id), license: $licenseURL")
            val headers = dataDict.entries.filter { it.key != "licenseUrl" && it.value is String }
                .associate { it.key as String to it.value as String }
            MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setMultiSession(true)
                .setLicenseRequestHeaders(headers)
                .setLicenseUri(licenseURL)
                .build()
        }
        return if (drmConfig != null)
            MediaItem.Builder().setUri(videoUri).setDrmConfiguration(drmConfig).build()
        else MediaItem.fromUri(videoUri).also {
            Log.v(pluginName, "No DRM config for ExoPlayer($id), using plain MediaItem")
        }
    }

    private fun <T> runAndWaitUI(action: () -> T): T {
        var result: T? = null
        val latch = CountDownLatch(1)
        runOnUiThread {
            result = action()
            latch.countDown()
        }
        latch.await()
        return result!!
    }

    private fun emitAndLogError(id: Int, msg: String) {
        Log.e(pluginName, msg)
        emitSignal("on_player_error", id, msg)
    }

    private fun logNotFound(id: Int, action: String): Unit {
        return emitAndLogError(id, "ExoPlayer($id) not found when trying to $action")
    }

    private fun logTrackDebugInfo(id: Int, player: ExoPlayer) {
        val tracks = player.currentTracks
        val debugInfo = buildString {
            append("ExoPlayer($id) debug info:\n")
            append("Total track groups: ${tracks.groups.size}\n")
            for (group in tracks.groups) {
                append("Track group (type: ${group.type}, length: ${group.length}):\n")
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    append("  Track $i: mime=${format.sampleMimeType}, codecs=${format.codecs}, bitrate=${format.bitrate}bps")
                    if (format.width > 0 && format.height > 0) append(", resolution=${format.width}x${format.height}")
                    if (group.type == C.TRACK_TYPE_VIDEO) append(", frameRate=${format.frameRate}")
                    if (group.type == C.TRACK_TYPE_AUDIO) append(", channels=${format.channelCount}, sampleRate=${format.sampleRate}")
                    append("\n")
                }
            }
        }
        Log.v(pluginName, debugInfo)
    }
}
