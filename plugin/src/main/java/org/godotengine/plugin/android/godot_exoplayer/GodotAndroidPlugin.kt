package org.godotengine.plugin.android.godot_exoplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Log as ExoLogger
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.util.EventLogger
import org.godotengine.godot.Dictionary
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.math.min

@UnstableApi
class GodotAndroidPlugin(godot: Godot) : GodotPlugin(godot) {

    private data class DrmConfig(
        val scheme: String,
        val licenseUrl: String,
        val requestHeaders: Map<String, String>
    )

    private data class PlayerConfig(
        val uri: Uri,
        val autoplay: Boolean,
        val volume: Float,
        val repeatMode: Int,
        val playbackSpeed: Float,
        val useCache: Boolean,
        val parseProgramDateTime: Boolean,
        val debugLogging: Boolean,
        val routeAudioToGodot: Boolean,
        val drm: DrmConfig?
    )

    private class GodotAudioBuffer {
        private val chunks = ArrayDeque<FloatArray>()
        private var headOffsetFrames = 0
        private var queuedFrames = 0
        private var sampleRate = 0
        private var channelCount = 0
        private var encoding = C.ENCODING_INVALID
        private val maxQueuedFrames = 96_000

        @Synchronized
        fun configure(sampleRate: Int, channelCount: Int, encoding: Int) {
            this.sampleRate = sampleRate
            this.channelCount = channelCount
            this.encoding = encoding
            clear()
        }

        @Synchronized
        fun append(buffer: ByteBuffer) {
            if (sampleRate <= 0 || channelCount <= 0) return
            val bytesPerSample = bytesPerSample(encoding)
            if (bytesPerSample <= 0) return

            val source = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            val frameCount = source.remaining() / (bytesPerSample * channelCount)
            if (frameCount <= 0) return

            val frames = FloatArray(frameCount * OUTPUT_CHANNELS)
            for (frame in 0 until frameCount) {
                var left = 0f
                var right = 0f
                for (channel in 0 until channelCount) {
                    val sample = readSample(source, encoding)
                    if (channel == 0) {
                        left = sample
                        right = sample
                    } else if (channel == 1) {
                        right = sample
                    }
                }
                val out = frame * OUTPUT_CHANNELS
                frames[out] = left
                frames[out + 1] = right
            }

            chunks.addLast(frames)
            queuedFrames += frameCount
            trimToLimit()
        }

        @Synchronized
        fun poll(maxFrames: Int): FloatArray {
            val requestedFrames = maxFrames.coerceAtLeast(0)
            if (requestedFrames == 0 || queuedFrames == 0) return FloatArray(0)

            val framesToRead = min(requestedFrames, queuedFrames)
            val output = FloatArray(framesToRead * OUTPUT_CHANNELS)
            var framesRead = 0

            while (framesRead < framesToRead && chunks.isNotEmpty()) {
                val chunk = chunks.first()
                val availableFrames = (chunk.size / OUTPUT_CHANNELS) - headOffsetFrames
                val copyFrames = min(framesToRead - framesRead, availableFrames)
                System.arraycopy(
                    chunk,
                    headOffsetFrames * OUTPUT_CHANNELS,
                    output,
                    framesRead * OUTPUT_CHANNELS,
                    copyFrames * OUTPUT_CHANNELS
                )
                framesRead += copyFrames
                queuedFrames -= copyFrames
                headOffsetFrames += copyFrames

                if (headOffsetFrames >= chunk.size / OUTPUT_CHANNELS) {
                    chunks.removeFirst()
                    headOffsetFrames = 0
                }
            }

            return output
        }

        @Synchronized
        fun clear() {
            chunks.clear()
            headOffsetFrames = 0
            queuedFrames = 0
        }

        @Synchronized
        fun formatDictionary() = Dictionary().apply {
            put("sampleRate", sampleRate)
            put("channelCount", channelCount)
            put("encoding", encoding)
            put("queuedFrames", queuedFrames)
        }

        private fun trimToLimit() {
            while (queuedFrames > maxQueuedFrames && chunks.isNotEmpty()) {
                val chunk = chunks.first()
                val availableFrames = (chunk.size / OUTPUT_CHANNELS) - headOffsetFrames
                val framesToDrop = min(queuedFrames - maxQueuedFrames, availableFrames)
                queuedFrames -= framesToDrop
                headOffsetFrames += framesToDrop
                if (headOffsetFrames >= chunk.size / OUTPUT_CHANNELS) {
                    chunks.removeFirst()
                    headOffsetFrames = 0
                }
            }
        }

        private fun bytesPerSample(encoding: Int): Int {
            return when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> 0
            }
        }

        private fun readSample(buffer: ByteBuffer, encoding: Int): Float {
            return when (encoding) {
                C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128f
                C.ENCODING_PCM_16BIT -> (buffer.short / 32768f).coerceIn(-1f, 1f)
                C.ENCODING_PCM_24BIT -> {
                    val value = (buffer.get().toInt() and 0xFF) or
                        ((buffer.get().toInt() and 0xFF) shl 8) or
                        (buffer.get().toInt() shl 16)
                    (value / 8388608f).coerceIn(-1f, 1f)
                }
                C.ENCODING_PCM_32BIT -> {
                    (buffer.int / 2147483648f).coerceIn(-1f, 1f)
                }
                C.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                else -> 0f
            }
        }

        companion object {
            private const val OUTPUT_CHANNELS = 2
        }
    }

    private class GodotAudioSink(private val audioBuffer: GodotAudioBuffer) : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            audioBuffer.configure(sampleRateHz, channelCount, encoding)
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            audioBuffer.append(buffer)
        }
    }

    private class GodotAudioRenderersFactory(
        context: Context,
        private val audioBuffer: GodotAudioBuffer
    ) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(GodotAudioSink(audioBuffer))))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
    }

    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME

    override fun getPluginSignals() = mutableSetOf(
        SignalInfo("on_player_ready", Integer::class.java, Integer::class.java),
        SignalInfo("on_video_end", Integer::class.java),
        SignalInfo("on_player_error", Integer::class.java, String::class.java),
        SignalInfo("on_player_state_changed", Integer::class.java, Integer::class.java)
    )

    private val exoPlayers = mutableMapOf<Int, ExoPlayer>()
    private val drmConfigurations = mutableMapOf<Int, Dictionary>()
    private val playerConfigurations = mutableMapOf<Int, PlayerConfig>()
    private val programDateTimes = mutableMapOf<Int, String>()
    private val audioBuffers = mutableMapOf<Int, GodotAudioBuffer>()

    private var downloadDirectory: File? = null
    private var downloadCache: Cache? = null
    private var databaseProvider: DatabaseProvider? = null

    // --- ExoPlayer Management ---

    @UsedByGodot
    fun createExoPlayer(id: Int, surface: Surface, config: Dictionary) = runOnUiThread {
        try {
            val playerConfig = buildPlayerConfig(id, config)
            val context = activity as Context
            val dataSourceFactory = buildDataSourceFactory(context, playerConfig.useCache)

            ExoLogger.setLogLevel(if (playerConfig.debugLogging) ExoLogger.LOG_LEVEL_ALL else ExoLogger.LOG_LEVEL_WARNING)
            exoPlayers.remove(id)?.release()
            programDateTimes[id] = ""

            val playerBuilder = ExoPlayer.Builder(context)
                .setMediaSourceFactory(buildMediaSourceFactory(context, dataSourceFactory))
            if (playerConfig.routeAudioToGodot) {
                playerBuilder.setRenderersFactory(
                    GodotAudioRenderersFactory(context, audioBuffers.getOrPut(id) { GodotAudioBuffer() })
                )
            } else {
                audioBuffers.remove(id)
            }
            val player = playerBuilder.build()

            configurePlayer(id, player, playerConfig, surface)
            player.prepare()

            exoPlayers[id] = player
            playerConfigurations[id] = playerConfig

            if (playerConfig.autoplay) {
                player.play()
            }
            if (playerConfig.parseProgramDateTime) {
                parseProgramDateTime(id, playerConfig.uri, dataSourceFactory)
            }

            Log.v(pluginName, "ExoPlayer($id) created with media: ${playerConfig.uri}")
        } catch (e: Exception) {
            emitAndLogError(id, "Error creating ExoPlayer($id): ${e.message}")
        }
    }

    @UsedByGodot
    fun createExoPlayerSurface(id: Int, videoUri: String, surface: Surface) {
        createExoPlayer(id, surface, Dictionary().apply {
            put("uri", videoUri)
        })
    }

    @UsedByGodot
    fun releaseExoPlayer(id: Int) = runOnUiThread {
        exoPlayers.remove(id)?.release()?.also {
            playerConfigurations.remove(id)
            programDateTimes.remove(id)
            audioBuffers.remove(id)
            Log.v(pluginName, "ExoPlayer($id) released and removed.")
        } ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to release")
    }

    @UsedByGodot
    fun getProgramDateTime(id: Int): String {
        return programDateTimes[id] ?: ""
    }

    // --- Playback Controls ---

    @UsedByGodot
    fun play(id: Int) = runOnUiThread { exoPlayers[id]?.play() ?: logNotFound(id, "play") }

    @UsedByGodot
    fun pause(id: Int) = runOnUiThread { exoPlayers[id]?.pause() ?: logNotFound(id, "pause") }

    @UsedByGodot
    fun seekTo(id: Int, positionMs: Long) = runOnUiThread {
        audioBuffers[id]?.clear()
        exoPlayers[id]?.seekTo(positionMs) ?: logNotFound(id, "seek")
    }

    @UsedByGodot
    fun seekBy(id: Int, deltaMs: Long) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            val newPos = if (player.duration != C.TIME_UNSET) {
                (player.currentPosition + deltaMs).coerceIn(0, player.duration)
            } else {
                (player.currentPosition + deltaMs).coerceAtLeast(0)
            }
            audioBuffers[id]?.clear()
            player.seekTo(newPos)
        } ?: logNotFound(id, "seekBy")
    }

    @UsedByGodot
    fun setRepeatMode(id: Int, mode: Int) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            player.repeatMode = mode
            playerConfigurations[id]?.let { playerConfigurations[id] = it.copy(repeatMode = mode) }
            Log.v(pluginName, "ExoPlayer($id) set repeat mode to $mode")
        } ?: logNotFound(id, "setRepeatMode")
    }

    @UsedByGodot
    fun setPlaybackSpeed(id: Int, speed: Float) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            val safeSpeed = speed.coerceAtLeast(0.1f)
            player.playbackParameters = PlaybackParameters(safeSpeed)
            playerConfigurations[id]?.let { playerConfigurations[id] = it.copy(playbackSpeed = safeSpeed) }
            Log.v(pluginName, "ExoPlayer($id) set playback speed to $safeSpeed")
        } ?: logNotFound(id, "setPlaybackSpeed")
    }

    @UsedByGodot
    fun setMedia(id: Int, config: Dictionary) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setMedia")
        try {
            val playerConfig = buildPlayerConfig(id, config, playerConfigurations[id])
            if (playerConfig.routeAudioToGodot != playerConfigurations[id]?.routeAudioToGodot) {
                throw IllegalArgumentException("routeAudioToGodot cannot be changed with setMedia; recreate the player")
            }
            val dataSourceFactory = buildDataSourceFactory(activity as Context, playerConfig.useCache)

            audioBuffers[id]?.clear()
            player.setMediaItem(buildMediaItem(playerConfig))
            player.prepare()
            playerConfigurations[id] = playerConfig
            programDateTimes[id] = ""

            if (playerConfig.parseProgramDateTime) {
                parseProgramDateTime(id, playerConfig.uri, dataSourceFactory)
            }
            if (playerConfig.autoplay) {
                player.play()
            }

            Log.v(pluginName, "ExoPlayer($id) media changed to: ${playerConfig.uri}")
        } catch (e: Exception) {
            emitAndLogError(id, "Error changing ExoPlayer($id) media: ${e.message}")
        }
    }

    @UsedByGodot
    fun changeVideoUrl(id: Int, videoUri: String) {
        setMedia(id, Dictionary().apply {
            put("uri", videoUri)
        })
    }

    // --- Volume ---

    @UsedByGodot
    fun setVolume(id: Int, volume: Float) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            val safeVolume = volume.coerceIn(0f, 1f)
            player.volume = if (playerConfigurations[id]?.routeAudioToGodot == true) 0f else safeVolume
            playerConfigurations[id]?.let { playerConfigurations[id] = it.copy(volume = safeVolume) }
        } ?: logNotFound(id, "setVolume")
    }

    @UsedByGodot
    fun getVolume(id: Int): Float = runAndWaitUI { playerConfigurations[id]?.volume ?: exoPlayers[id]?.volume ?: -1f }

    @UsedByGodot
    fun pollAudioFrames(id: Int, maxFrames: Int): FloatArray {
        return audioBuffers[id]?.poll(maxFrames) ?: FloatArray(0)
    }

    @UsedByGodot
    fun getAudioFormat(id: Int): Dictionary {
        return audioBuffers[id]?.formatDictionary() ?: Dictionary().apply {
            put("sampleRate", 0)
            put("channelCount", 0)
            put("encoding", C.ENCODING_INVALID)
            put("queuedFrames", 0)
        }
    }

    @UsedByGodot
    fun clearAudioBuffer(id: Int) {
        audioBuffers[id]?.clear()
    }

    // --- Tracks & Resolutions ---

    @UsedByGodot
    fun getResolutions(id: Int): Array<String> = runAndWaitUI {
        exoPlayers[id]?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_VIDEO }
            ?.flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    val f = group.getTrackFormat(i)
                    if (f.width > 0 && f.height > 0) {
                        "${f.width}x${f.height} - ${f.bitrate / 1000} kbps"
                    } else {
                        null
                    }
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
        buildTrackList(id, C.TRACK_TYPE_AUDIO) { format, index ->
            Dictionary().apply {
                put("index", index)
                put("language", format.language ?: "und")
                put("channels", format.channelCount)
                put("sampleRate", format.sampleRate)
            }
        }
    }.toTypedArray()

    @UsedByGodot
    fun setAudioTrack(id: Int, audioTrackIndex: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setAudioTrack")
        val selection = findTrackSelection(player, C.TRACK_TYPE_AUDIO, audioTrackIndex) ?: return@runOnUiThread
        val language = player.currentTracks.groups[selection.first].getTrackFormat(selection.second).language ?: "und"
        audioBuffers[id]?.clear()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(language).build()
        Log.v(pluginName, "ExoPlayer($id) set audio track $audioTrackIndex")
    }

    @UsedByGodot
    fun getTextTracks(id: Int): Array<Dictionary> = runAndWaitUI {
        buildTrackList(id, C.TRACK_TYPE_TEXT) { format, index ->
            Dictionary().apply {
                put("index", index)
                put("language", format.language ?: "und")
                put("label", format.label ?: "")
            }
        }
    }.toTypedArray()

    @UsedByGodot
    fun setTextTrack(id: Int, textTrackIndex: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setTextTrack")
        if (textTrackIndex == -1) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            Log.v(pluginName, "ExoPlayer($id) disabled text tracks")
            return@runOnUiThread
        }

        val selection = findTrackSelection(player, C.TRACK_TYPE_TEXT, textTrackIndex) ?: return@runOnUiThread
        val language = player.currentTracks.groups[selection.first].getTrackFormat(selection.second).language ?: "und"
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages(language).build()
        Log.v(pluginName, "ExoPlayer($id) set text track $textTrackIndex")
    }

    // --- Position & Duration ---

    @UsedByGodot
    fun getCurrentPosition(id: Int): Long = runCatching {
        runAndWaitUI { exoPlayers[id]?.currentPosition ?: -1L }
    }.getOrElse { -1L }

    @UsedByGodot
    fun getDuration(id: Int): Float = runAndWaitUI {
        exoPlayers[id]?.let { player ->
            if (player.playbackState == Player.STATE_READY && player.duration != C.TIME_UNSET) {
                player.duration.toFloat()
            } else {
                -1f
            }
        } ?: -1f
    }

    // --- DRM Setup ---

    @UsedByGodot
    fun setupWidevine(id: Int, data: Dictionary) {
        drmConfigurations[id] = data
    }

    // --- Plugin Cleanup ---

    override fun onMainDestroy() {
        runOnUiThread {
            exoPlayers.values.forEach { it.release() }
            exoPlayers.clear()
            playerConfigurations.clear()
            programDateTimes.clear()
            audioBuffers.clear()
        }
        super.onMainDestroy()
    }

    // --- Helpers ---

    private fun configurePlayer(id: Int, player: ExoPlayer, config: PlayerConfig, surface: Surface) {
        player.setMediaItem(buildMediaItem(config))
        player.setVideoSurface(surface)
        player.volume = if (config.routeAudioToGodot) 0f else config.volume
        player.repeatMode = config.repeatMode
        player.playbackParameters = PlaybackParameters(config.playbackSpeed)
        player.addListener(createPlayerListener(id))
        if (config.debugLogging) {
            player.addAnalyticsListener(EventLogger())
        }
    }

    private fun buildPlayerConfig(id: Int, data: Dictionary, defaults: PlayerConfig? = null): PlayerConfig {
        val uriString = getString(data, "uri") ?: defaults?.uri?.toString()
            ?: throw IllegalArgumentException("Missing required config key: uri")

        return PlayerConfig(
            uri = Uri.parse(uriString),
            autoplay = getBoolean(data, "autoplay", defaults?.autoplay ?: false),
            volume = getFloat(data, "volume", defaults?.volume ?: 1f).coerceIn(0f, 1f),
            repeatMode = getInt(data, "repeatMode", defaults?.repeatMode ?: Player.REPEAT_MODE_OFF),
            playbackSpeed = getFloat(data, "playbackSpeed", defaults?.playbackSpeed ?: 1f).coerceAtLeast(0.1f),
            useCache = getBoolean(data, "useCache", defaults?.useCache ?: false),
            parseProgramDateTime = getBoolean(data, "parseProgramDateTime", defaults?.parseProgramDateTime ?: false),
            debugLogging = getBoolean(data, "debugLogging", defaults?.debugLogging ?: false),
            routeAudioToGodot = getBoolean(data, "routeAudioToGodot", defaults?.routeAudioToGodot ?: false),
            drm = getDictionary(data, "drm")?.let { buildDrmConfig(it) } ?: legacyDrmConfig(id) ?: defaults?.drm
        )
    }

    private fun buildMediaItem(config: PlayerConfig): MediaItem {
        val builder = MediaItem.Builder().setUri(config.uri)
        config.drm?.let { builder.setDrmConfiguration(buildDrmConfiguration(it)) }
        return builder.build()
    }

    private fun buildDrmConfiguration(config: DrmConfig): MediaItem.DrmConfiguration {
        val uuid = getDrmUuid(config.scheme)
            ?: throw IllegalArgumentException("Unsupported DRM scheme: ${config.scheme}")
        return MediaItem.DrmConfiguration.Builder(uuid)
            .setLicenseUri(config.licenseUrl)
            .setLicenseRequestHeaders(config.requestHeaders)
            .build()
    }

    private fun buildDrmConfig(data: Dictionary): DrmConfig {
        val licenseUrl = getString(data, "licenseUrl")
            ?: throw IllegalArgumentException("Missing required DRM config key: licenseUrl")
        return DrmConfig(
            scheme = getString(data, "scheme") ?: "widevine",
            licenseUrl = licenseUrl,
            requestHeaders = getStringMap(getDictionary(data, "requestHeaders"))
        )
    }

    private fun legacyDrmConfig(id: Int): DrmConfig? {
        val data = drmConfigurations[id] ?: return null
        val licenseUrl = getString(data, "licenseUrl") ?: return null
        if (licenseUrl.isEmpty()) return null
        return DrmConfig(
            scheme = getString(data, "scheme") ?: "widevine",
            licenseUrl = licenseUrl,
            requestHeaders = getStringMap(getDictionary(data, "requestHeaders"))
        )
    }

    private fun getDrmUuid(scheme: String): UUID? {
        return when (scheme.lowercase()) {
            "widevine" -> Util.getDrmUuid("widevine")
            else -> null
        }
    }

    private fun getHttpDataSourceFactory(headers: Map<String, String> = emptyMap()): HttpDataSource.Factory {
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        CookieHandler.setDefault(cookieManager)

        val factory = DefaultHttpDataSource.Factory()
        if (headers.isNotEmpty()) {
            factory.setDefaultRequestProperties(headers)
        }
        return factory
    }

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
            downloadCache = SimpleCache(
                downloadContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return downloadCache!!
    }

    private fun buildDataSourceFactory(context: Context, useCache: Boolean): DataSource.Factory {
        val upstreamFactory = DefaultDataSource.Factory(context, getHttpDataSourceFactory())
        return if (useCache) {
            buildReadOnlyCacheDataSource(upstreamFactory, getDownloadCache(context))
        } else {
            upstreamFactory
        }
    }

    private fun buildMediaSourceFactory(context: Context, dataSourceFactory: DataSource.Factory): MediaSource.Factory {
        val drmSessionManagerProvider = DefaultDrmSessionManagerProvider()
        drmSessionManagerProvider.setDrmHttpDataSourceFactory(getHttpDataSourceFactory())
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    private fun parseProgramDateTime(id: Int, uri: Uri, dataSourceFactory: DataSource.Factory) {
        try {
            val dataSource = dataSourceFactory.createDataSource()
            val inputStream = DataSourceInputStream(dataSource, DataSpec(uri))
            val text = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            programDateTimes[id] = Regex("""#EXT-X-PROGRAM-DATE-TIME:(.*)""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?: ""
        } catch (e: Exception) {
            Log.w(pluginName, "Could not parse program-date-time: ${e.message}")
            programDateTimes[id] = ""
        }
    }

    private fun createPlayerListener(id: Int) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    exoPlayers[id]?.let { player ->
                        val duration = if (player.duration == C.TIME_UNSET) -1 else player.duration
                        Log.v(pluginName, "ExoPlayer($id) ready, duration: $duration")
                        if (playerConfigurations[id]?.debugLogging == true) {
                            logTrackDebugInfo(id, player)
                        }
                        emitSignal("on_player_ready", id, duration.toInt())
                        emitSignal("on_player_state_changed", id, Player.STATE_READY)
                    }
                }
                Player.STATE_ENDED -> {
                    emitSignal("on_video_end", id)
                    emitSignal("on_player_state_changed", id, Player.STATE_ENDED)
                }
                Player.STATE_BUFFERING -> {
                    Log.v(pluginName, "ExoPlayer($id) buffering")
                    emitSignal("on_player_state_changed", id, Player.STATE_BUFFERING)
                }
                Player.STATE_IDLE -> {
                    Log.v(pluginName, "ExoPlayer($id) idle")
                    emitSignal("on_player_state_changed", id, Player.STATE_IDLE)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            emitSignal("on_player_error", id, error.message)
        }
    }

    private fun buildTrackList(
        id: Int,
        trackType: Int,
        itemBuilder: (androidx.media3.common.Format, Int) -> Dictionary
    ): ArrayList<Dictionary> {
        val tracks = ArrayList<Dictionary>()
        var index = 0
        exoPlayers[id]?.currentTracks?.groups?.filter { it.type == trackType }?.forEach { group ->
            (0 until group.length).forEach { i ->
                tracks.add(itemBuilder(group.getTrackFormat(i), index))
                index++
            }
        }
        return tracks
    }

    private fun findTrackSelection(player: ExoPlayer, trackType: Int, requestedIndex: Int): Pair<Int, Int>? {
        var index = 0
        for ((groupIndex, group) in player.currentTracks.groups.withIndex()) {
            if (group.type != trackType) continue
            for (trackIndex in 0 until group.length) {
                if (index == requestedIndex) {
                    return Pair(groupIndex, trackIndex)
                }
                index++
            }
        }
        return null
    }

    private fun getString(data: Dictionary, key: String): String? {
        return data[key] as? String
    }

    private fun getDictionary(data: Dictionary, key: String): Dictionary? {
        return data[key] as? Dictionary
    }

    private fun getBoolean(data: Dictionary, key: String, defaultValue: Boolean): Boolean {
        return when (val value = data[key]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getInt(data: Dictionary, key: String, defaultValue: Int): Int {
        return when (val value = data[key]) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getFloat(data: Dictionary, key: String, defaultValue: Float): Float {
        return when (val value = data[key]) {
            is Float -> value
            is Double -> value.toFloat()
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getStringMap(data: Dictionary?): Map<String, String> {
        if (data == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in data.keys) {
            val stringKey = key as? String ?: continue
            val stringValue = data[key] as? String ?: continue
            result[stringKey] = stringValue
        }
        return result
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

    private fun logNotFound(id: Int, action: String) {
        emitAndLogError(id, "ExoPlayer($id) not found when trying to $action")
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
