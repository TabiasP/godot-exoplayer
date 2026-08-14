extends Resource
class_name ExoPlayerSourceConfig

## Reusable network and cache settings for an ExoPlayer media source.
@export var uri: String = ""
@export var request_headers: Dictionary = {}
@export var user_agent: String = ""
@export var allow_cross_protocol_redirects: bool = false
@export var use_cache: bool = false
@export_range(8, 4096, 1, "suffix:MB") var cache_max_size_mb: int = 256
@export var parse_program_date_time: bool = false
@export var pause_on_app_pause: bool = true

func to_options() -> Dictionary:
	return {
		"requestHeaders": request_headers.duplicate(true),
		"userAgent": user_agent,
		"allowCrossProtocolRedirects": allow_cross_protocol_redirects,
		"useCache": use_cache,
		"cacheMaxBytes": cache_max_size_mb * 1024 * 1024,
		"parseProgramDateTime": parse_program_date_time,
		"pauseOnAppPause": pause_on_app_pause
	}
