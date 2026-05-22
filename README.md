# Godot ExoPlayer

**Disclaimer: This project is a Work in Progress (WIP).**

This repository explores the integration of ExoPlayer with Godot Engine 4.4. The goal is to leverage the new Android surface retrieval feature introduced in Godot 4.4 to embed ExoPlayer as a media player within Godot applications. Expect potential instability and incomplete features as this is an experimental project under active development.

## Features

- **Android Surface Retrieval:** Utilizes the new capability in Godot 4.4 to obtain Android surfaces from plugins.
- **ExoPlayer Integration:** Embeds ExoPlayer for robust media playback with support for various formats and streaming protocols.
- **Configurable Playback:** Supports plain playback, optional Widevine DRM, cache-backed data sources, repeat mode, playback speed, volume, track selection, subtitles, and player state signals.
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
   var player_id = ExoPlayer.create_player(surface, "https://example.com/video.m3u8")
   ExoPlayer.play(player_id)
   ```

#### ExoPlayerCompositionLayer Node

The addon registers `ExoPlayerCompositionLayer`, a convenience node that extends `OpenXRCompositionLayerQuad`. Add it under your XR origin, set `video_uri` in the inspector, and enable `create_on_ready` to create the player automatically.

The node exposes the common player config in the inspector, including autoplay, volume, repeat mode, playback speed, cache, Widevine license URL, request headers, and Godot audio routing. It emits player-specific `player_ready`, `player_error`, `video_end`, and `player_state_changed` signals, and exposes helper methods such as `play()`, `pause()`, `seek_to()`, `set_media()`, and `release_player()`.

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

Supported options:

- `autoplay`: Starts playback after the player is prepared. Defaults to `false`.
- `volume`: Initial player volume from `0.0` to `1.0`. Defaults to `1.0`.
- `repeatMode`: ExoPlayer repeat mode integer. Defaults to off.
- `playbackSpeed`: Initial playback speed. Defaults to `1.0`.
- `useCache`: Enables the plugin cache data source. Defaults to `false`.
- `parseProgramDateTime`: Reads HLS `#EXT-X-PROGRAM-DATE-TIME` from the manifest. Defaults to `false`.
- `debugLogging`: Enables verbose Media3 logging and track debug output. Defaults to `false`.
- `routeAudioToGodot`: Routes decoded ExoPlayer PCM through Godot instead of Android's audible audio output. Defaults to `false`.
- `godotAudioPlayer`: Optional `AudioStreamPlayer` or `AudioStreamPlayer3D` used for routed audio. The wrapper creates and assigns an `AudioStreamGenerator`; you do not need to create the generator yourself.
- `godotAudioBufferLength`: AudioStreamGenerator buffer length in seconds for routed audio. Defaults to `0.5`.
- `drm`: Optional DRM config. Widevine is currently supported with `scheme = "widevine"` and `licenseUrl`.

When `routeAudioToGodot` is enabled, ExoPlayer's Android audio output stays muted and `setPlayerVolume` controls the Godot audio player volume. The Kotlin plugin exposes `pollAudioFrames(id, maxFrames)`, `getAudioFormat(id)`, and `clearAudioBuffer(id)` for the wrapper's polling path.

The old helper still works for simple Widevine usage:

```gdscript
var player_id = ExoPlayer.create_exoplayer_instance(surface, video_uri, license_url)
```

#### Controls and Queries

The wrapper exposes playback controls such as `play`, `pause`, `seekTo`, `seekBy`, `setMedia`, `setPlayerVolume`, `setRepeatMode`, and `setPlaybackSpeed`.

It also exposes `getVideoResolutions`, `setVideoResolution`, `getAvailableAudioTracks`, `setAudioTrack`, `getAvailableTextTracks`, `setTextTrack`, `getCurrentPlaybackPosition`, `getVideoDuration`, and `getProgramDateTime`.

Signals:

- `player_ready(id, duration)`
- `player_error(id, error_message)`
- `video_end(id)`
- `player_state_changed(id, state)`

### Limitations
- Currently experimental and may contain bugs or incomplete features.
- Only supports Android platforms.
- May lack complete documentation and features
- Only supports Version 4.4 and onwards (hopefully)


### Used Addons
- 

### Contributing
Contributions are welcome! If you encounter issues or have suggestions, feel free to open an issue or submit a pull request. As this is an experimental project, active collaboration will help shape its development.

