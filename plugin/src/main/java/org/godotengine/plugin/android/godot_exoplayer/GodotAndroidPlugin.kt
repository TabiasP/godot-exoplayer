package org.godotengine.plugin.android.godot_exoplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.common.util.Log as ExoLogger
import org.godotengine.godot.*
import org.godotengine.godot.plugin.*
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.UUID
import java.util.concurrent.CountDownLatch


@UnstableApi
private class CustomRenderersFactory : DefaultRenderersFactory {
    constructor(context: Context) : super(context)

    override fun createRenderers(eventHandler: Handler,
                                 videoRendererEventListener : VideoRendererEventListener,
                                 audioRendererEventListener : AudioRendererEventListener,
                                 textRendererOutput : TextOutput,
                                 metadataRendererOutput : MetadataOutput
                        ) : Array<Renderer> {
        var renderers = super.createRenderers(eventHandler, videoRendererEventListener, audioRendererEventListener, textRendererOutput, metadataRendererOutput)
        val rendererList = ArrayList<Renderer>(listOf(*renderers))

        renderers = rendererList.toTypedArray()
        return renderers
    }
}
@UnstableApi
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

    // ---- Variables
    private var downloadDirectory: File? = null
    private var downloadCache: Cache? = null
    private var databaseProvider : DatabaseProvider? = null


    // --- ExoPlayer Management ---

    @OptIn(UnstableApi::class)
    @UsedByGodot
    fun createExoPlayerSurface(id: Int, videoUri: String, surface: Surface) = runOnUiThread {
        try {
            ExoLogger.setLogLevel(ExoLogger.LOG_LEVEL_ALL)
            exoPlayers[id]?.release()

            val dataSourceFactory : DataSource.Factory = buildDataSourceFactory(activity as Context)
            val uri = Uri.parse(videoUri)
            val licenseUrl = drmConfigurations[id]?.get("licenseUrl") as? String ?: ""
            // Requested to play the uri
            val mediaItems = buildMediaItems(uri, licenseUrl)

            // ------------------------------------------
            // - Exoplayer
            val playerBuilder : ExoPlayer.Builder = ExoPlayer.Builder(activity as Context)
                .setMediaSourceFactory(buildMediaSourceFactory(activity as Context, dataSourceFactory))
                .setRenderersFactory(CustomRenderersFactory(activity as Context,))

            val player = playerBuilder.build()

            player.setMediaItems(mediaItems)
            player.setVideoSurface(surface)
            player.addListener(createPlayerListener(id))
            player.addAnalyticsListener(object : AnalyticsListener{
                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long
                ) {
                    Log.i("GodotVideoDebug", "Analytics: First frame rendered on $output")
                }
            })

            player.addAnalyticsListener(EventLogger())

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



    private fun buildMediaItems(uri: Uri, drmLicenseUrl: String): List<MediaItem> {
        return listOf(buildMediaItem(uri, drmLicenseUrl))
    }

    private fun buildMediaItem(uri: Uri, drmLicenseUrl: String) : MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
        if (drmLicenseUrl.isNotEmpty()) {
            builder.setDrmConfiguration(
                buildDrmConfiguration(Util.getDrmUuid("widevine"), drmLicenseUrl)
            )
        }
        return builder.build()
    }

    private fun buildDrmConfiguration(uuid: UUID?, drmLicenseUrl: String) : MediaItem.DrmConfiguration? {
        return uuid?.let { MediaItem.DrmConfiguration.Builder(it) }?.setLicenseUri(drmLicenseUrl)?.build()
    }

    private fun getHttpDataSourceFactory(context: Context): HttpDataSource.Factory {
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        CookieHandler.setDefault(cookieManager)
        return DefaultHttpDataSource.Factory()
    }

    @OptIn(UnstableApi::class)
    private fun buildReadOnlyCacheDataSource(upstreamFactory: DefaultDataSource.Factory, cache: Cache): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    }

    private fun getDownloadDirectory(context: Context): File {
        if (downloadDirectory == null) {
            downloadDirectory = context.getExternalFilesDirs(null)?.firstOrNull() ?: context.filesDir
        }
        return downloadDirectory!!
    }
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        if (databaseProvider == null) {
            databaseProvider = StandaloneDatabaseProvider(context)
        }
        return databaseProvider!!
    }

    private fun getDownloadCache(context: Context): Cache {
        if (downloadCache == null) {
            val downloadContentDirectory = File(getDownloadDirectory(context), "downloads")
            val cacheEvictor = NoOpCacheEvictor()
            downloadCache = SimpleCache(
                downloadContentDirectory,
                cacheEvictor,
                getDatabaseProvider(context)!!
            )
        }
        return downloadCache!!
    }

    @OptIn(UnstableApi::class)
    private fun buildDataSourceFactory(context: Context): CacheDataSource.Factory {
        val upStreamFactory : DefaultDataSource.Factory  = DefaultDataSource.Factory(context, getHttpDataSourceFactory(context))
        return buildReadOnlyCacheDataSource(upStreamFactory, getDownloadCache(context))
    }

    private fun buildMediaSourceFactory(context: Context, dataSourceFactory: DataSource.Factory): MediaSource.Factory {
        val drmSessionManagerProvider : DefaultDrmSessionManagerProvider = DefaultDrmSessionManagerProvider()
        drmSessionManagerProvider.setDrmHttpDataSourceFactory(getHttpDataSourceFactory(context))
        return DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory).setDrmSessionManagerProvider(drmSessionManagerProvider)
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
