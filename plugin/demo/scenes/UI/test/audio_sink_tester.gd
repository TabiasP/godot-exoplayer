extends Control
class_name AudioSinkTester

# UI Elements
@onready var status_indicator: ColorRect = $PanelContainer/MarginContainer/VBoxContainer/Header/StatusIndicator
@onready var status_text: Label = $PanelContainer/MarginContainer/VBoxContainer/Header/StatusText
@onready var mix_rate_label: Label = $PanelContainer/MarginContainer/VBoxContainer/Content/DiagnosticPanel/VBoxContainer/MixRateValue
@onready var playback_active_label: Label = $PanelContainer/MarginContainer/VBoxContainer/Content/DiagnosticPanel/VBoxContainer/PlaybackActiveValue
@onready var frames_pushed_label: Label = $PanelContainer/MarginContainer/VBoxContainer/Content/DiagnosticPanel/VBoxContainer/FramesPushedValue
@onready var mode_selector: HBoxContainer = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/ModeSelector
@onready var waveform_selector: HBoxContainer = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/WaveformSelector
@onready var freq_slider: HSlider = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/FreqSlider
@onready var freq_value: Label = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/FreqLabel/FreqValue
@onready var volume_slider: HSlider = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/VolumeSlider
@onready var volume_value: Label = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/VolumeLabel/VolumeValue
@onready var start_button: Button = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/PlaybackControls/StartButton
@onready var stop_button: Button = $PanelContainer/MarginContainer/VBoxContainer/Content/ControlPanel/VBoxContainer/PlaybackControls/StopButton
@onready var visualizer: Control = $PanelContainer/MarginContainer/VBoxContainer/Content/VisualizerPanel/Visualizer
@onready var log_console: TextEdit = $PanelContainer/MarginContainer/VBoxContainer/Footer/LogConsole

# Audio variables for synthetic test
var _audio_player: AudioStreamPlayer
var _generator_stream: AudioStreamGenerator
var _playback: AudioStreamGeneratorPlayback
var _is_playing_tone: bool = false
var _phase: float = 0.0
var _frequency: float = 440.0
var _volume: float = 0.5
var _selected_waveform: int = 0 # 0: Sine, 1: Square, 2: Sawtooth, 3: Noise

# Polling configuration for ExoPlayer bridge
var _selected_mode: int = 0 # 0: Synthetic Tone, 1: Poll ExoPlayer (Direct)
var _polled_player_id: int = 1
var _frames_pushed_count: int = 0

# History buffer for drawing the visualizer
var _sample_history: Array[float] = []
const MAX_HISTORY_SAMPLES = 200

# Segmented Control properties
var _style_active: StyleBoxFlat
var _style_inactive: StyleBoxFlat
var _mode_buttons: Array[Button] = []
var _waveform_buttons: Array[Button] = []
const WAVEFORM_NAMES = ["Sine Wave", "Square Wave", "Sawtooth Wave", "White Noise"]

func _ready() -> void:
	# Initialize custom styleboxes for segmented controls
	_style_active = StyleBoxFlat.new()
	_style_active.bg_color = Color(0.0, 0.5, 0.6, 0.9)
	_style_active.border_color = Color(0.0, 0.9, 1.0, 1.0)
	_style_active.set_border_width_all(2)
	_style_active.set_corner_radius_all(6)

	_style_inactive = StyleBoxFlat.new()
	_style_inactive.bg_color = Color(0.12, 0.15, 0.18, 0.85)
	_style_inactive.border_color = Color(0.2, 0.25, 0.3, 1.0)
	_style_inactive.set_border_width_all(1)
	_style_inactive.set_corner_radius_all(6)

	# Clear sample history
	for i in range(MAX_HISTORY_SAMPLES):
		_sample_history.append(0.0)
		
	# Populate Mode Segmented Control
	_mode_buttons = _create_segmented_control(
		mode_selector, 
		["Synth Tone", "Player 1", "Player 2"], 
		_selected_mode, 
		_on_mode_selected
	)
	
	# Populate Waveform Segmented Control
	_waveform_buttons = _create_segmented_control(
		waveform_selector, 
		["Sine", "Square", "Saw", "Noise"], 
		_selected_waveform, 
		_on_waveform_selected
	)
	
	# Connect slider signals
	freq_slider.value_changed.connect(_on_frequency_changed)
	volume_slider.value_changed.connect(_on_volume_changed)
	start_button.pressed.connect(_on_start_pressed)
	stop_button.pressed.connect(_on_stop_pressed)
	
	# Connect visualizer draw signal
	visualizer.draw.connect(draw_oscilloscope.bind(visualizer))
	
	# Initial UI updates
	_update_freq_label(freq_slider.value)
	_update_volume_label(volume_slider.value)
	_log("Diagnostic Console initialized. Ready to test audio sink.")
	
	# Setup audio player for synthetic mode
	_setup_internal_audio()

func _exit_tree() -> void:
	_stop_internal_audio()

func _log(message: String) -> void:
	var timestamp = Time.get_time_string_from_system()
	log_console.text += "[%s] %s\n" % [timestamp, message]
	log_console.scroll_vertical = log_console.get_line_count()

func _setup_internal_audio() -> void:
	if _audio_player == null:
		_audio_player = AudioStreamPlayer.new()
		add_child(_audio_player)
		_audio_player.bus = "Master"
		
	_generator_stream = AudioStreamGenerator.new()
	_generator_stream.mix_rate = 48000
	_generator_stream.buffer_length = 0.5
	
	_audio_player.stream = _generator_stream
	_log("Internal AudioStreamPlayer & AudioStreamGenerator initialized at 48000 Hz.")

func _start_internal_audio() -> void:
	if _audio_player == null:
		_setup_internal_audio()
	
	_phase = 0.0
	_audio_player.play()
	_playback = _audio_player.get_stream_playback()
	
	if _playback == null:
		_log("ERROR: get_stream_playback() returned NULL. Godot audio sink is unavailable!")
		status_indicator.color = Color(0.9, 0.2, 0.2)
		status_text.text = "SINK ERROR"
		_is_playing_tone = false
	else:
		_log("Internal AudioStreamPlayer started successfully.")
		status_indicator.color = Color(0.2, 0.8, 0.2)
		status_text.text = "SINK ACTIVE (SYNTHETIC)"
		_is_playing_tone = true

func _stop_internal_audio() -> void:
	_is_playing_tone = false
	if _audio_player != null and _audio_player.playing:
		_audio_player.stop()
		_log("Internal AudioStreamPlayer stopped.")
	
	status_indicator.color = Color(0.5, 0.5, 0.5)
	status_text.text = "STANDBY"

func _process(_delta: float) -> void:
	if _selected_mode == 0:
		# Mode: Synthetic test tone
		_process_synthetic_audio()
	else:
		# Mode: Poll from ExoPlayer
		_process_exoplayer_audio()
		
	# Request redraw of the oscilloscope visualizer
	visualizer.queue_redraw()

func _process_synthetic_audio() -> void:
	if not _is_playing_tone or _playback == null:
		mix_rate_label.text = "0 Hz"
		playback_active_label.text = "Inactive"
		return
		
	mix_rate_label.text = "48000 Hz"
	playback_active_label.text = "Active (Synthetic)"
	
	var frames_available = _playback.get_frames_available()
	if frames_available <= 0:
		return
		
	var increment = 2.0 * PI * _frequency / 48000.0
	var samples_to_push = mini(frames_available, 1024)
	
	for i in range(samples_to_push):
		var sample_val = 0.0
		
		# Generate based on chosen waveform
		match _selected_waveform:
			0: # Sine
				sample_val = sin(_phase)
			1: # Square
				sample_val = 1.0 if sin(_phase) >= 0.0 else -1.0
			2: # Sawtooth
				sample_val = (_phase / PI) - 1.0
			3: # White Noise
				sample_val = randf_range(-1.0, 1.0)
				
		sample_val *= _volume
		_playback.push_frame(Vector2(sample_val, sample_val))
		
		# Keep track of phase
		_phase += increment
		if _phase >= 2.0 * PI:
			_phase -= 2.0 * PI
			
		# Push to visualization buffer
		_sample_history.append(sample_val)
		if _sample_history.size() > MAX_HISTORY_SAMPLES:
			_sample_history.remove_at(0)
			
	_frames_pushed_count += samples_to_push
	frames_pushed_label.text = str(_frames_pushed_count)

func _process_exoplayer_audio() -> void:
	# Check if Android plugin singleton is available
	if not Engine.has_singleton("ExoPlayer") and not has_node("/root/ExoPlayer"):
		status_indicator.color = Color(0.9, 0.4, 0.1)
		status_text.text = "PLUGIN NOT LOADED"
		mix_rate_label.text = "N/A"
		playback_active_label.text = "N/A"
		return
		
	var ep = null
	if Engine.has_singleton("ExoPlayer"):
		ep = Engine.get_singleton("ExoPlayer")
	else:
		ep = get_node_or_null("/root/ExoPlayer")
		
	if ep == null or not ep.players.has(_polled_player_id):
		status_indicator.color = Color(0.5, 0.5, 0.5)
		status_text.text = "EXOPLAYER NOT CREATED"
		mix_rate_label.text = "N/A"
		playback_active_label.text = "Inactive"
		return
		
	var player_data = ep.players[_polled_player_id]
	var route_active = player_data.get("route_audio_to_godot", false)
	
	if not route_active:
		status_indicator.color = Color(0.9, 0.6, 0.2)
		status_text.text = "ROUTING DISABLED"
		playback_active_label.text = "Direct Android Output"
		mix_rate_label.text = "N/A"
		return
		
	status_indicator.color = Color(0.2, 0.8, 0.2)
	status_text.text = "ROUTING ACTIVE"
	
	var rate = player_data.get("godot_audio_sample_rate", 0)
	mix_rate_label.text = "%d Hz" % rate
	
	var audio_player = player_data.get("godot_audio_player", null)
	if audio_player != null and audio_player.playing:
		playback_active_label.text = "Active (Routing)"
	else:
		playback_active_label.text = "Active (Paused/Idle)"
		
	# Fetch details of internal buffer size from the Kotlin/Java side
	var format_info = {}
	if ep._android_plugin != null:
		format_info = ep._android_plugin.getAudioFormat(_polled_player_id)
		
	var queued = format_info.get("queuedFrames", 0)
	frames_pushed_label.text = "Queued: %d | Total Polled: %d" % [queued, _frames_pushed_count]
	
	# Tap into the polled samples directly to feed the visualizer.
	# We can inspect the playback object and sample count.
	var playback = player_data.get("godot_audio_playback", null)
	if playback != null:
		if player_data.has("last_samples"):
			var last_samples = player_data["last_samples"]
			if last_samples.size() > 0:
				var sample_count = last_samples.size() / 2
				for i in range(sample_count):
					var val = (last_samples[i * 2] + last_samples[i * 2 + 1]) / 2.0
					_sample_history.append(val)
					if _sample_history.size() > MAX_HISTORY_SAMPLES:
						_sample_history.remove_at(0)
				player_data.erase("last_samples")

# UI Signals Handler
func _on_mode_selected(index: int) -> void:
	_selected_mode = index
	_frames_pushed_count = 0
	
	if _selected_mode == 0:
		_polled_player_id = 1
		_log("Switched to Synthetic Tone mode.")
		_set_segmented_control_enabled(_waveform_buttons, true)
		freq_slider.editable = true
		_setup_internal_audio()
	else:
		_polled_player_id = index # index 1 corresponds to player ID 1, index 2 to player ID 2
		_log("Switched to Bridge Diagnostics mode for ExoPlayer ID: %d." % _polled_player_id)
		_set_segmented_control_enabled(_waveform_buttons, false)
		freq_slider.editable = false
		_stop_internal_audio()

func _on_waveform_selected(index: int) -> void:
	_selected_waveform = index
	var name = WAVEFORM_NAMES[index]
	_log("Waveform set to: %s" % name)

func _on_frequency_changed(value: float) -> void:
	_frequency = value
	_update_freq_label(value)

func _update_freq_label(value: float) -> void:
	freq_value.text = "%d Hz" % int(value)

func _on_volume_changed(value: float) -> void:
	_volume = value
	_update_volume_label(value)

func _update_volume_label(value: float) -> void:
	volume_value.text = "%d%%" % int(value * 100)

func _on_start_pressed() -> void:
	if _selected_mode == 0:
		_start_internal_audio()
	else:
		var ep = null
		if Engine.has_singleton("ExoPlayer"):
			ep = Engine.get_singleton("ExoPlayer")
		elif has_node("/root/ExoPlayer"):
			ep = get_node("/root/ExoPlayer")
		if ep != null and ep.players.has(_polled_player_id):
			ep.play(_polled_player_id)
			_log("Sent PLAY command to ExoPlayer ID: %d" % _polled_player_id)
		else:
			_log("Cannot play: ExoPlayer ID %d not active." % _polled_player_id)

func _on_stop_pressed() -> void:
	if _selected_mode == 0:
		_stop_internal_audio()
	else:
		var ep = null
		if Engine.has_singleton("ExoPlayer"):
			ep = Engine.get_singleton("ExoPlayer")
		elif has_node("/root/ExoPlayer"):
			ep = get_node("/root/ExoPlayer")
		if ep != null and ep.players.has(_polled_player_id):
			ep.pause(_polled_player_id)
			_log("Sent PAUSE command to ExoPlayer ID: %d" % _polled_player_id)
		else:
			_log("Cannot pause: ExoPlayer ID %d not active." % _polled_player_id)

# Oscilloscope Draw Function
func draw_oscilloscope(canvas: Control) -> void:
	var size = canvas.size
	
	# Draw background grid
	var grid_color = Color(0.15, 0.2, 0.25, 0.5)
	var mid_y = size.y / 2.0
	
	# Horizontal centerline
	canvas.draw_line(Vector2(0, mid_y), Vector2(size.x, mid_y), grid_color, 1.5)
	
	# Vertical gridlines
	var cols = 10
	var col_width = size.x / cols
	for col in range(1, cols):
		var x = col * col_width
		canvas.draw_line(Vector2(x, 0), Vector2(x, size.y), grid_color, 1.0)
		
	# Horizontal gridlines
	var rows = 6
	var row_height = size.y / rows
	for row in range(1, rows):
		var y = row * row_height
		canvas.draw_line(Vector2(0, y), Vector2(size.x, y), grid_color, 1.0)
		
	# Draw oscilloscope waveform
	if _sample_history.size() < 2:
		return
		
	var points = PackedVector2Array()
	var step_x = size.x / float(MAX_HISTORY_SAMPLES - 1)
	
	for i in range(_sample_history.size()):
		var x = i * step_x
		# Map float sample [-1.0, 1.0] to visual height [size.y - padding, padding]
		var y = mid_y - (_sample_history[i] * (size.y / 2.0 - 10.0))
		points.append(Vector2(x, y))
		
	# Draw with a beautiful glowing neon-cyan line
	var line_color = Color(0.0, 0.9, 1.0, 0.9)
	canvas.draw_polyline(points, line_color, 2.5, true)
	
	# Draw glowing dots at vertices for visual polish if amplitude is high
	if _sample_history.size() > 0 and absf(_sample_history[_sample_history.size() - 1]) > 0.005:
		canvas.draw_circle(points[points.size() - 1], 4.0, Color(0.0, 1.0, 0.8, 1.0))

# Segmented Control Helpers
func _create_segmented_control(container: HBoxContainer, items: Array[String], initial_index: int, callback: Callable) -> Array[Button]:
	var buttons: Array[Button] = []
	for i in range(items.size()):
		var btn = Button.new()
		btn.text = items[i]
		btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		btn.toggle_mode = true
		btn.focus_mode = Control.FOCUS_NONE
		
		# Set styles
		btn.add_theme_stylebox_override("normal", _style_inactive)
		btn.add_theme_stylebox_override("hover", _style_inactive)
		btn.add_theme_stylebox_override("pressed", _style_active)
		btn.add_theme_stylebox_override("disabled", _style_inactive)
		
		# Connect press signal
		btn.pressed.connect(func():
			_select_segment(buttons, i)
			callback.call(i)
		)
		
		container.add_child(btn)
		buttons.append(btn)
	
	# Select initial
	_select_segment(buttons, initial_index)
	return buttons

func _select_segment(buttons: Array[Button], selected_index: int) -> void:
	for i in range(buttons.size()):
		var btn = buttons[i]
		if i == selected_index:
			btn.button_pressed = true
			btn.add_theme_stylebox_override("normal", _style_active)
			btn.add_theme_stylebox_override("hover", _style_active)
			btn.add_theme_color_override("font_color", Color(1, 1, 1))
		else:
			btn.button_pressed = false
			btn.add_theme_stylebox_override("normal", _style_inactive)
			btn.add_theme_stylebox_override("hover", _style_inactive)
			btn.add_theme_color_override("font_color", Color(0.7, 0.75, 0.8))

func _set_segmented_control_enabled(buttons: Array[Button], enabled: bool) -> void:
	for btn in buttons:
		btn.disabled = not enabled
		if not enabled:
			btn.release_focus()
			btn.add_theme_color_override("font_disabled_color", Color(0.4, 0.45, 0.5))
