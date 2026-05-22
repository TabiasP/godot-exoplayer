@tool
extends OpenXRCompositionLayerQuad
class_name ExoPlayerCompositionLayer

signal player_created(id: int)
signal player_ready(id: int, duration: float)
signal player_error(id: int, error_message: String)
signal video_end(id: int)
signal player_state_changed(id: int, state: int)

@export_group("ExoPlayer")
@export var video_uri: String = ""
@export var create_on_ready: bool = true
@export var creation_delay: float = 0.0
@export var autoplay: bool = false
@export_range(0.0, 1.0, 0.01) var volume: float = 1.0
@export var repeat_mode: int = 0
@export_range(0.1, 4.0, 0.01) var playback_speed: float = 1.0
@export var use_cache: bool = false
@export var parse_program_date_time: bool = false
@export var debug_logging: bool = false

@export_group("DRM")
@export var widevine_license_url: String = ""
@export var widevine_request_headers: Dictionary = {}

@export_group("Godot Audio")
@export var route_audio_to_godot: bool = false
@export var godot_audio_player_path: NodePath
@export_range(0.05, 2.0, 0.01) var godot_audio_buffer_length: float = 0.5

var player_id: int = -1

func _ready() -> void:
	use_android_surface = true
	if Engine.is_editor_hint():
		return

	if _has_exoplayer_singleton():
		_connect_exoplayer_signals()

	if create_on_ready:
		if creation_delay > 0.0:
			await get_tree().create_timer(creation_delay).timeout
		create_player()

func create_player(uri: String = "") -> int:
	if Engine.is_editor_hint():
		return -1
	if not _has_exoplayer_singleton():
		push_warning("ExoPlayer autoload is not available. Enable the godot_exoplayer plugin.")
		return -1

	if player_id > 0:
		release_player()

	var android_surface = get_android_surface()
	if android_surface == null:
		push_warning("Android surface is not available yet.")
		return -1

	var resolved_uri := uri if uri != "" else video_uri
	if resolved_uri == "":
		push_warning("No video URI configured.")
		return -1

	player_id = ExoPlayer.create_player(android_surface, resolved_uri, _build_options())
	if player_id > 0:
		emit_signal("player_created", player_id)
	return player_id

func release_player() -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.release_player(player_id)
	player_id = -1

func play() -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.play(player_id)

func pause() -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.pause(player_id)

func seek_to(position_ms: int) -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.seekTo(player_id, position_ms)

func seek_by(delta_ms: int) -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.seekBy(player_id, delta_ms)

func set_media(uri: String) -> void:
	video_uri = uri
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.setMedia(player_id, uri, _build_options())

func set_player_volume(new_volume: float) -> void:
	volume = clampf(new_volume, 0.0, 1.0)
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.setPlayerVolume(player_id, volume)

func get_player_volume() -> float:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.getPlayerVolume(player_id)
	return volume

func is_player_ready() -> bool:
	return player_id > 0 and _has_exoplayer_singleton() and ExoPlayer.is_player_ready(player_id)

func get_duration() -> float:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.getVideoDuration(player_id)
	return -1.0

func get_current_position() -> int:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.getCurrentPlaybackPosition(player_id)
	return -1

func _exit_tree() -> void:
	if not Engine.is_editor_hint():
		release_player()

func _build_options() -> Dictionary:
	var options := {
		"autoplay": autoplay,
		"volume": volume,
		"repeatMode": repeat_mode,
		"playbackSpeed": playback_speed,
		"useCache": use_cache,
		"parseProgramDateTime": parse_program_date_time,
		"debugLogging": debug_logging,
		"routeAudioToGodot": route_audio_to_godot,
		"godotAudioBufferLength": godot_audio_buffer_length
	}

	if widevine_license_url != "":
		options["drm"] = {
			"scheme": "widevine",
			"licenseUrl": widevine_license_url,
			"requestHeaders": widevine_request_headers
		}

	var godot_audio_player = get_node_or_null(godot_audio_player_path)
	if godot_audio_player is AudioStreamPlayer or godot_audio_player is AudioStreamPlayer3D:
		options["godotAudioPlayer"] = godot_audio_player

	return options

func _connect_exoplayer_signals() -> void:
	if not ExoPlayer.player_ready.is_connected(_on_exoplayer_ready):
		ExoPlayer.player_ready.connect(_on_exoplayer_ready)
	if not ExoPlayer.player_error.is_connected(_on_exoplayer_error):
		ExoPlayer.player_error.connect(_on_exoplayer_error)
	if not ExoPlayer.video_end.is_connected(_on_exoplayer_video_end):
		ExoPlayer.video_end.connect(_on_exoplayer_video_end)
	if not ExoPlayer.player_state_changed.is_connected(_on_exoplayer_state_changed):
		ExoPlayer.player_state_changed.connect(_on_exoplayer_state_changed)

func _on_exoplayer_ready(id: int, duration: float) -> void:
	if id == player_id:
		emit_signal("player_ready", id, duration)

func _on_exoplayer_error(id: int, error_message: String) -> void:
	if id == player_id:
		emit_signal("player_error", id, error_message)

func _on_exoplayer_video_end(id: int) -> void:
	if id == player_id:
		emit_signal("video_end", id)

func _on_exoplayer_state_changed(id: int, state: int) -> void:
	if id == player_id:
		emit_signal("player_state_changed", id, state)

func _has_exoplayer_singleton() -> bool:
	return Engine.has_singleton("ExoPlayer") or has_node("/root/ExoPlayer")
