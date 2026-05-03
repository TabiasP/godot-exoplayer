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
2. Surface Binding: Create a OpenXRCompositionLayer and select `use_android_surface`.
3. Retrieve the Surface: Use the `get_surface()` method to obtain the Android surface from the OpenXRCompositionLayer.
4. Create a player through the `ExoPlayer` autoload:

   ```gdscript
   var surface = $OpenXRCompositionLayer.get_surface()
   var player_id = ExoPlayer.create_player(surface, "https://example.com/video.m3u8")
   ExoPlayer.play(player_id)
   ```

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
- `drm`: Optional DRM config. Widevine is currently supported with `scheme = "widevine"` and `licenseUrl`.

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

