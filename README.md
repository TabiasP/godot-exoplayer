# Godot ExoPlayer

**Disclaimer: This project is a Work in Progress (WIP).**

This repository integrates Media3 ExoPlayer with Godot XR. It intentionally targets XR: Godot currently exposes the required Android `Surface` through an OpenXR composition layer, so non-XR Android view or texture output is outside this plugin's supported scope.

## Features

- **Android Surface Retrieval:** Utilizes the new capability in Godot 4.4 to obtain Android surfaces from plugins.
- **ExoPlayer Integration:** Embeds ExoPlayer for robust media playback with support for various formats and streaming protocols.
- **Configurable Playback:** Supports plain playback, optional Widevine DRM, cache-backed data sources, repeat mode, playback speed, volume, track selection, subtitles, and player state signals.
- **Reusable Config Resources:** Optional source, DRM, and audio Resources provide inspector-friendly configuration while the dictionary API remains available for advanced extensions.
- **Godot Audio Routing:** Optionally sends decoded PCM through Godot audio players and buses, with queue diagnostics and restoration of caller-owned audio nodes.
- **Godot Engine Compatibility:** Designed specifically for Godot Engine 4.4.

## Getting Started

### Prerequisites

- **Godot Engine 4.4:** Ensure you have the latest version installed.
- **Android Development Environment:** Set up Android Studio or an equivalent environment to build and deploy Android plugins.

### Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/bnjmntmm/godot-exoplayer.git
    ```
2. Build the Addon yourself using Android Studio and Gradle or use the prebuilt inside from [godot_exoplayer](plugin%2Fdemo%2Faddons%2Fgodot_exoplayer)
3. Test out the demo in [main](plugin%2Fdemo%2Fscenes%2Fmain)

### Usage
1. Enable the Plugin: Activate the ExoPlayer plugin in your Godot project settings.
2. Surface Binding: Add an `ExoPlayerCompositionLayer` node, or create an OpenXRCompositionLayer and select `use_android_surface`.
3. Retrieve the Surface: Use the `get_surface()` method to obtain the Android surface from the OpenXRCompositionLayer.
4. Create a player through the `ExoPlayer` autoload:

   ```gdscript
   var surface = $OpenXRCompositionLayer.get_surface()
   var player_id = ExoPlayer.create_url_player(surface, "https://example.com/video.m3u8")
   ExoPlayer.play(player_id)
   ```

   Plain URL playback is the default workflow and does not require DRM configuration.
   Use `create_player(...)` directly when composing custom options, or the dedicated
   `create_drm_player(...)` helper for protected content.

   To route decoded audio through Godot's mixer and audio buses:

   ```gdscript
   var player_id = ExoPlayer.create_url_player(surface, video_uri, {
       "routeAudioToGodot": true,
       "godotAudioPlayer": $AudioStreamPlayer3D
   })
   ```

#### ExoPlayerCompositionLayer Node

The addon registers `ExoPlayerCompositionLayer`, a convenience node that extends `OpenXRCompositionLayerQuad`. Add it under your XR origin, set `video_uri` in the inspector, and enable `create_on_ready` to create the player automatically.

The node exposes the common player config in the inspector, including autoplay, volume, repeat mode, playback speed, cache, Widevine license URL, request headers, and Godot audio routing. It emits player-specific `player_ready`, `player_error`, `video_end`, and `player_state_changed` signals, and exposes helper methods such as `play()`, `pause()`, `seek_to()`, `set_media()`, and `release_player()`.

For reusable inspector configuration, assign any of:

- `ExoPlayerSourceConfig`: URL, media request headers, user agent, redirect policy, cache limit, and HLS date-time inspection.
- `ExoPlayerDrmConfig`: optional Widevine, ClearKey, PlayReady, or custom-UUID DRM plus license request headers. Leave its scheme at `NONE` for clear content.
- `ExoPlayerAudioConfig`: Android or Godot output, spatial split mode, and generator buffer length.

The composition layer waits up to `surface_wait_timeout` for its OpenXR Android surface. The old `creation_delay` remains available for project-specific staging, but is no longer required as the primary surface-readiness mechanism.

For Godot-routed audio, enable `route_audio_to_godot`. If `godot_audio_player_path` points to an `AudioStreamPlayer` or `AudioStreamPlayer3D`, the wrapper assigns an `AudioStreamGenerator` to that player and pushes decoded ExoPlayer PCM into it. If the path is empty, the wrapper creates an internal `AudioStreamPlayer`. For `AudioStreamPlayer3D`, scene settings such as transform, bus, attenuation, and spatial behavior remain under your control; the wrapper only manages the stream and `volume_linear`.

#### Player Options

`create_player(surface, uri, options := {})` accepts an optional dictionary for features that are not needed by every project:

```gdscript
var player_id = ExoPlayer.create_player(surface, video_uri, {
	"autoplay": true,
	"volume": 0.8,
	"repeatMode": 0,
	"playbackSpeed": 1.0,
	"useCache": false,
	"parseProgramDateTime": false,
	"debugLogging": false,
	"routeAudioToGodot": true,
	"godotAudioPlayer": $AudioStreamPlayer3D,
	"godotAudioBufferLength": 0.5,
	"drm": {
		"scheme": "widevine",
		"licenseUrl": license_url,
		"requestHeaders": {
			"Authorization": "Bearer <token>"
		}
	}
})
```

For a clearer protected-content call site, the same DRM setup can be written as:

```gdscript
var player_id = ExoPlayer.create_drm_player(
    surface,
    video_uri,
    license_url,
    {"Authorization": "Bearer <token>"},
    {"autoplay": true}
)
```

Use `set_url(id, uri)` to switch a DRM player back to clear content, or
`set_drm_media(id, uri, license_url, headers)` to change it to protected content.

Supported options:

- `autoplay`: Starts playback after the player is prepared. Defaults to `false`.
- `volume`: Initial player volume from `0.0` to `1.0`. Defaults to `1.0`.
- `repeatMode`: ExoPlayer repeat mode integer. Defaults to off.
- `playbackSpeed`: Initial playback speed. Defaults to `1.0`.
- `useCache`: Enables the plugin cache data source. Defaults to `false`.
- `cacheMaxBytes`: Shared LRU streaming-cache limit. Defaults to 256 MiB; the first active cache configuration owns the shared limit.
- `requestHeaders`: Headers applied to media and manifest requests.
- `userAgent`: Optional HTTP user agent.
- `allowCrossProtocolRedirects`: Allows HTTP-to-HTTPS or HTTPS-to-HTTP redirects. Defaults to `false`.
- `pauseOnAppPause`: Pauses active playback while the Android XR app is backgrounded and resumes only players that were previously playing. Defaults to `true`.
- `parseProgramDateTime`: Reads HLS `#EXT-X-PROGRAM-DATE-TIME` from the manifest. Defaults to `false`.
- `debugLogging`: Enables verbose Media3 logging and track debug output. Defaults to `false`.
- `routeAudioToGodot`: Routes decoded ExoPlayer PCM through Godot instead of Android's audible audio output. Defaults to `false`.
- `godotAudioPlayer`: Optional `AudioStreamPlayer` or `AudioStreamPlayer3D` used for routed audio. The wrapper creates and assigns an `AudioStreamGenerator`; you do not need to create the generator yourself.
- `godotAudioBufferLength`: AudioStreamGenerator buffer length in seconds for routed audio. Defaults to `0.5`.
- `drm`: Optional DRM config. Widevine is currently supported with `scheme = "widevine"` and `licenseUrl`.

When `routeAudioToGodot` is enabled, ExoPlayer's Android audio output stays muted and `setPlayerVolume` controls the Godot audio player volume. The Kotlin plugin exposes `pollAudioFrames(id, maxFrames)`, `getAudioFormat(id)`, and `clearAudioBuffer(id)` for the wrapper's polling path.

`get_audio_bridge_stats(id)` returns the current format, queued frames, dropped frames, and underrun frames. Godot routing is intended for bus effects, visualization, or spatial placement. It uses a separate Godot audio clock, so applications should validate latency and A/V synchronization on their target XR hardware.

The old helper still works for simple Widevine usage:

```gdscript
var player_id = ExoPlayer.create_exoplayer_instance(surface, video_uri, license_url)
```

#### Controls and Queries

The wrapper exposes playback controls such as `play`, `pause`, `seekTo`, `seekBy`, `setMedia`, `setPlayerVolume`, `setRepeatMode`, and `setPlaybackSpeed`.

It also exposes `getVideoResolutions`, `setVideoResolution`, `getAvailableAudioTracks`, `setAudioTrack`, `getAvailableTextTracks`, `setTextTrack`, `getCurrentPlaybackPosition`, `getVideoDuration`, and `getProgramDateTime`.

The preferred structured track API is `get_video_tracks`, `get_audio_tracks`, and `get_subtitle_tracks`, paired with `select_video_track`, `select_audio_track`, and `select_subtitle_track`. Track dictionaries contain their exact index, selected/supported state, format metadata, and type-specific properties. The older resolution and camelCase helpers remain for compatibility.

Signals:

- `player_created(id)` — the native player was successfully constructed.
- `player_ready(id, duration)`
- `player_error(id, error_message)`
- `video_end(id)`
- `player_state_changed(id, state)`
- `subtitle_cues(id, cues)` — selected subtitle text for rendering in Godot UI.

### Limitations
- Currently experimental and may contain bugs or incomplete features.
- Only supports Android OpenXR composition-layer surfaces; this is intentionally an XR plugin.
- Godot-routed audio requires target-device validation for latency and A/V drift under XR load.
- May lack complete documentation and features
- Only supports Version 4.4 and onwards (hopefully)


### Used Addons
- 

### Contributing
Contributions are welcome! If you encounter issues or have suggestions, feel free to open an issue or submit a pull request. As this is an experimental project, active collaboration will help shape its development.

