package org.godotengine.plugin.android.godot_exoplayer

import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.common.util.Log as ExoLogger
import org.godotengine.godot.Dictionary
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import java.util.concurrent.CountDownLatch

class GodotAndroidPlugin(godot: Godot) : GodotPlugin(godot) {

    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME

    init {

    }

    // Updated signal declaration with parameters
    override fun getPluginSignals(): MutableSet<SignalInfo> {
        return mutableSetOf(
            SignalInfo("on_player_ready", Integer::class.java, Integer::class.java),
            SignalInfo("on_video_end", Integer::class.java),
            SignalInfo("on_player_error", Integer::class.java, String::class.java)
        )
    }

    // Keep track of multiple ExoPlayer instances by ID.
    private val exoPlayers = mutableMapOf<Int, ExoPlayer>()

    // Map to store DRM Configuratrions keyed by Player ID
    private val drmConfigurations = mutableMapOf<Int, Dictionary>()


    /**
     * Creates or recreates an ExoPlayer instance with a given ID.
     *
     * @param id       Unique identifier for this ExoPlayer instance.
     * @param videoUri The URI of the video to play.
     * @param surface  The Android Surface to render video onto (provided from Godot).
     */

    //needs to be unstable or else there will be red underlines lul
    @OptIn(UnstableApi::class)
    @UsedByGodot
    fun createExoPlayerSurface(id: Int, videoUri: String, surface: Surface) {
        runOnUiThread {
            try {
                // 1) If an ExoPlayer already exists for this id, release it first
                ExoLogger.setLogLevel(ExoLogger.LOG_LEVEL_ALL)
                exoPlayers[id]?.release()
//                audioExtractors[id]?.reset()


                // 3) Create a new ExoPlayer and use the audiosink
                val newExoPlayer = activity?.let { ctx ->
                    ExoPlayer.Builder(ctx)
                        // this would allow us to use our custom audio sink
                        // and get the data frames
//                        .setRenderersFactory(GodotRendererFactory(ctx))
                        .build()
                }
                if (newExoPlayer == null) {
                    Log.e(pluginName, "Failed to create ExoPlayer for id $id")
                    emitSignal("on_player_error",id, "Failed to create ExoPlayer for id $id")
                    return@runOnUiThread
                }
                // 4) Assign the provided Surface
                newExoPlayer.setVideoSurface(surface)
                newExoPlayer.addListener(createPlayerListener(id))
                newExoPlayer.addAnalyticsListener(EventLogger())

                // 5) Prepare the media
                var mediaItem = MediaItem.fromUri(videoUri)


                // add a check if the player has a drm configuration
                if (drmConfigurations.containsKey(id)){

                    val dataDict = drmConfigurations[id]
                    val licenseURL = dataDict?.get("licenseUrl") as String
                    Log.v(pluginName, "Applying Widevine DRM configuration for ExoPlayer($id) with license URL: $licenseURL")

                    val customHeaders = mutableMapOf<String, String>()
                    dataDict.forEach { (key, value) ->
                        if (key != "licenseUrl" && value is String) {
                            customHeaders[key as String] = value
                        }
                    }


                    // create drm config
                    val drmConfig = MediaItem.DrmConfiguration
                        .Builder(C.WIDEVINE_UUID)
                        .setLicenseRequestHeaders(customHeaders)

                        .setLicenseUri(dataDict?.get("licenseUrl") as String)
                        .build()

                    // create mediaitem with the drm config
                    mediaItem = MediaItem.Builder().setUri(videoUri).setDrmConfiguration(drmConfig).build()
                }

                newExoPlayer.setMediaItem(mediaItem)


                newExoPlayer.prepare()

                // 6) (Optional) Start playback immediately
                //newExoPlayer.play()

                // 7) Store in the map
                exoPlayers[id] = newExoPlayer

                Log.v(pluginName, "ExoPlayer($id) created and set up with video: $videoUri")

            } catch (e: Exception) {
                Log.e(pluginName, "Error creating ExoPlayer($id): ${e.message}")
                emitSignal("on_player_error", id,"Error creating ExoPlayer($id): ${e.message}")
            }
        }
    }

    /**
     * Play (or resume) the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun play(id: Int) {
        runOnUiThread {
            exoPlayers[id]?.play() ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to play")
        }
    }

    /**
     * Pause the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun pause(id: Int) {
        runOnUiThread {
            exoPlayers[id]?.pause() ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to pause")
        }
    }
    /**
     * Get all the tracks that are available for the ExoPlayer with the given ID.
     */
    @OptIn(UnstableApi::class)
    @UsedByGodot
    fun getResolutions(id: Int): Array<String> {
        val resolutions = ArrayList<String>()
        val latch = CountDownLatch(1)
        runOnUiThread {
            exoPlayers[id]?.let { player ->
                val tracks = player.currentTracks
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val width = format.width
                            val height = format.height
                            val bitrate = format.bitrate
                            if (width > 0 && height > 0) {
                                val kbps = bitrate / 1000
                                resolutions.add("${width}x${height} - ${kbps} kbps")
                            }
                        }
                    }
                }
            }
            latch.countDown()
        }
        latch.await()
        return resolutions.toTypedArray()
    }
    /**
     * Set the resolution of the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun setResolution(id: Int, width: Int, height: Int) {
        runOnUiThread {
            exoPlayers[id]?.let { player ->
                val currentParams = player.trackSelectionParameters
                player.trackSelectionParameters = currentParams
                    .buildUpon()
                    .setMaxVideoSize(width, height)
                    .build()
                Log.v(pluginName, "ExoPlayer($id) track selection updated to max size ${width}x$height")
            } ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to set resolution")
        }
    }

    /**
     * Returns an array of strings representing all available audio tracks for the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun getAudioTracks(id: Int): Array<Dictionary> {
        val audioTracks = ArrayList<Dictionary>()
        val latch = CountDownLatch(1)
        runOnUiThread {
            exoPlayers[id]?.let { player ->
                val tracks = player.currentTracks
                // Iterate over all track groups
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            // Use language if available; if not, mark as undefined ("und")
                            val language = format.language ?: "und"
                            val channels = format.channelCount
                            val sampleRate = format.sampleRate
                            // Build a descriptive string (you can adjust the details as needed)
                            val trackDict = Dictionary()
                            trackDict["index"] = i
                            trackDict["language"] = language
                            trackDict["channels"] = channels
                            trackDict["sampleRate"] = sampleRate
                            audioTracks.add(trackDict)
                        }
                    }
                }
            }
            latch.countDown()
        }
        latch.await()
        return audioTracks.toTypedArray()
    }
    /**
     * Function that sets the audio track for the ExoPlayer with the given ID based on the provided index.
     * The index corresponds to the order of the audio tracks as returned by getAudioTracks.
     */
    @UsedByGodot
    fun setAudioTrack(id: Int, audioTrackIndex: Int){
        runOnUiThread{
            val player = exoPlayers[id]
            if (player == null) {
                Log.e(pluginName, "ExoPlayer($id) not found when attempting to setAudioTrack")
                return@runOnUiThread
            }
            val tracks = player.currentTracks
            var mergedIndex: Int = 0
            var selectedGroupIndex: Int? = null
            var selectedTrackInGroup: Int? = null
            // Iterate over audio groups and their tracks to locate the one at the given merged index.
            for (groupIndex in tracks.groups.indices) {
                val group = tracks.groups[groupIndex]
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (trackIndex in 0 until group.length) {
                        if (mergedIndex == audioTrackIndex) {
                            selectedGroupIndex = groupIndex
                            selectedTrackInGroup = trackIndex
                            break
                        }
                        mergedIndex++
                    }
                    if (selectedGroupIndex != null) break
                }
            }
            if (selectedGroupIndex == null || selectedTrackInGroup == null){
                Log.e(pluginName, "Audio track index $audioTrackIndex not found for ExoPlayer($id)")
                return@runOnUiThread
            }

            //retrieve the format of the selected track
            val selectedFormat = tracks.groups[selectedGroupIndex].getTrackFormat(selectedTrackInGroup)
            // Use the track's language metadata if available, otherwise default to "und" (undefined).
            val selectedLanguage = selectedFormat.language ?: "und"

            // Update the player's track selection parameters to select the desired audio track.
            val currentParams = player.trackSelectionParameters
            player.trackSelectionParameters = currentParams.buildUpon()
                .setPreferredAudioLanguages(selectedLanguage).build()
            Log.v(pluginName, "ExoPlayer($id) track selection updated to audio track $audioTrackIndex")
        }
    }


    /**
     * Set the volume of the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun setVolume(id: Int, volume: Float){
        runOnUiThread{
            val player = exoPlayers[id]
            if (player == null) {
                Log.e(pluginName, "ExoPlayer($id) not found when attempting to setVolume")
                return@runOnUiThread
            }
            val clampedVolume = volume.coerceIn(0f, 1f)
            player.volume = clampedVolume
        }
    }
    /**
     * Get the Volume of the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun getVolume(id: Int): Float {
        return exoPlayers[id]?.volume ?: -1f
    }

    /**
     * Seek to a specific position in the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun seekTo(id: Int, positionMs: Long){
        runOnUiThread{
            exoPlayers[id]?.seekTo(positionMs) ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to seek")
        }
    }


    /**
     * Seek by a specific amount of time in the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun seekBy(id: Int, deltaMs: Long) {
        runOnUiThread {
            val player = exoPlayers[id] ?: run {
                Log.e(pluginName, "ExoPlayer($id) not found for seekBy")
                return@runOnUiThread
            }
            val current = player.currentPosition
            val duration = player.duration
            val newPosition = if (duration != androidx.media3.common.C.TIME_UNSET) {
                (current + deltaMs).coerceIn(0, duration)
            } else {
                (current + deltaMs).coerceAtLeast(0)
            }
            player.seekTo(newPosition)
        }
    }

    /**
     * Returns the playback position in the current content or ad,
     * in milliseconds, or the prospective position in milliseconds
     */

    @UsedByGodot
    fun getCurrentPosition(id: Int): Long {
        return runCatching {
            var currentPosition: Long = -1
            val latch = CountDownLatch(1)
            runOnUiThread {
                currentPosition = exoPlayers[id]?.currentPosition ?: -1
                latch.countDown()
            }
            latch.await() // Wait until the UI thread completes the operation
            currentPosition
        }.getOrElse { -1 }
    }


    /**
     * Returns the duration of the current content or ad in milliseconds
     * , or C. TIME_UNSET if the duration is not known.
     */
    @UsedByGodot
    fun getDuration(id: Int): Float {
        return exoPlayers[id]?.let { player ->
            if (player.playbackState == Player.STATE_READY && player.duration != androidx.media3.common.C.TIME_UNSET) {
                player.duration.toFloat()
            } else {
                -1f
            }
        } ?: -1f
    }

    /**
     * Set a license URL for Widevine DRM.
     *
     */

    @UsedByGodot
    fun setupWidevine(id: Int, data : Dictionary ) {
        drmConfigurations[id] = data

    }


    /**
     * Optional: Stop or release a specific ExoPlayer instance.
     * This is useful if you want to free resources for one player but keep others running.
     */
    @UsedByGodot
    fun releaseExoPlayer(id: Int) {
        runOnUiThread {
            exoPlayers[id]?.let { exo ->
                exo.release()
                exoPlayers.remove(id)
                Log.v(pluginName, "ExoPlayer($id) released and removed.")
            } ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to release")
        }
    }

    private fun createPlayerListener(id:Int) = object : Player.Listener{
        override fun onPlaybackStateChanged(playbackState: Int) {
            when(playbackState) {
                Player.STATE_READY -> {
                    exoPlayers[id]?.let { player ->
                        val duration =
                            if (player.duration == C.TIME_UNSET) -1 else player.duration
                        Log.v(pluginName, "ExoPlayer($id) ready, duration: $duration")

                        // SOME DEBUG INFOS
                        val tracks = player.currentTracks
                        val debugInfo = StringBuilder()
                        debugInfo.append("ExoPlayer($id) debug info:\n")
                        debugInfo.append("Total track groups: ${tracks.groups.size}\n")
                        for (group in tracks.groups) {
                            debugInfo.append("Track group (type: ${group.type}, length: ${group.length}):\n")
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                debugInfo.append("  Track $i: mime=${format.sampleMimeType}, codecs=${format.codecs}, bitrate=${format.bitrate}bps")
                                if (format.width > 0 && format.height > 0) {
                                    debugInfo.append(", resolution=${format.width}x${format.height}")
                                }
                                if (group.type == C.TRACK_TYPE_VIDEO) {
                                    debugInfo.append(", frameRate=${format.frameRate}")
                                }
                                if (group.type == C.TRACK_TYPE_AUDIO) {
                                    debugInfo.append(", channels=${format.channelCount}, sampleRate=${format.sampleRate}")
                                }
                                debugInfo.append("\n")
                            }
                        }
                        Log.v(pluginName, debugInfo.toString())

                        //

                        emitSignal("on_player_ready", id, duration.toInt())
                    }
                }

                Player.STATE_ENDED -> {
                    emitSignal("on_video_end", id)
                }

                Player.STATE_BUFFERING -> {
                    Log.v(pluginName, "ExoPlayer($id) buffering")
                }

                Player.STATE_IDLE -> {
                    Log.v(pluginName, "ExoPlayer($id) idle")
                }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            emitSignal("on_player_error", id, error.message)
        }

    }

    /**
     * Cleanup if needed (called when Godot unloads the plugin or the activity is destroyed).
     * Releases *all* ExoPlayer instances.
     */
    override fun onMainDestroy() {
        runOnUiThread {
            exoPlayers.values.forEach { it.release() }
            exoPlayers.clear()
        }
        super.onMainDestroy()
    }
}
