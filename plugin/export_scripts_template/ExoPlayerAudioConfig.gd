extends Resource
class_name ExoPlayerAudioConfig

enum Output { ANDROID, GODOT }
enum SpatialMode { DUPLICATED_STEREO, SPLIT_STEREO }

@export var output: Output = Output.ANDROID
@export var spatial_mode: SpatialMode = SpatialMode.DUPLICATED_STEREO
@export_range(0.05, 2.0, 0.01, "suffix:s") var buffer_length: float = 0.5

func to_options() -> Dictionary:
	return {
		"routeAudioToGodot": output == Output.GODOT,
		"spatialAudioMode": spatial_mode,
		"godotAudioBufferLength": buffer_length
	}
