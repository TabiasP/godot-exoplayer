extends Resource
class_name ExoPlayerDrmConfig

enum Scheme { NONE, WIDEVINE, CLEARKEY, PLAYREADY, CUSTOM_UUID }

## DRM is optional. Leave scheme at NONE for normal URL playback.
@export var scheme: Scheme = Scheme.NONE
@export var custom_scheme_uuid: String = ""
@export var license_url: String = ""
@export var request_headers: Dictionary = {}

func is_enabled() -> bool:
	return scheme != Scheme.NONE

func to_options() -> Dictionary:
	if not is_enabled():
		return {"clearDrm": true}
	var scheme_name := "widevine"
	match scheme:
		Scheme.CLEARKEY:
			scheme_name = "clearkey"
		Scheme.PLAYREADY:
			scheme_name = "playready"
		Scheme.CUSTOM_UUID:
			scheme_name = custom_scheme_uuid
	return {
		"clearDrm": false,
		"drm": {
			"scheme": scheme_name,
			"licenseUrl": license_url,
			"requestHeaders": request_headers.duplicate(true)
		}
	}
