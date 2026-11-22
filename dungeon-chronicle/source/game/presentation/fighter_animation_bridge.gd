extends Node

## Optional adapter for user-supplied sprites. It never drives attacks or damage.
## Attach below a fighter and point to its AnimatedSprite2D child, or call bind().
## Map semantic keys, e.g. {"slash_1.windup":"sword_prepare","walk":"run"}.
## Full-move clips use the move ID ("slash_1"); phase clips use "move.phase".
## Call refresh_animations() after changing animation_map at runtime.
const Timeline = preload("res://game/presentation/sprite_timeline.gd")
const OWNER_META: StringName = &"_dc_animation_bridge"
@export var fighter_path: NodePath = NodePath("..")
@export var sprite_path: NodePath
@export var animation_map: Dictionary = {}
@export var hide_placeholder: bool = true
var _fighter: Node2D
var _sprite: AnimatedSprite2D
var _original_sprite: Dictionary = {}
var _original_placeholder: bool = true
var _timeline = Timeline.new()
var _selection_dirty: bool = true
var _animation: StringName = &""
var _phase_clip: bool = false
var _state: StringName = &""
var _sequence: int = -1
var _started_at: float = 0.0


func _ready() -> void:
	process_physics_priority = 100
	if not _timeline.invalidated.is_connected(refresh_animations):
		_timeline.invalidated.connect(refresh_animations)
	if _fighter == null and not sprite_path.is_empty():
		bind(get_node_or_null(fighter_path) as Node2D,get_node_or_null(sprite_path) as AnimatedSprite2D)
	set_physics_process(is_instance_valid(_fighter))


func bind(fighter: Node2D,sprite: AnimatedSprite2D) -> bool:
	if not is_instance_valid(fighter) or not is_instance_valid(sprite): return false
	if not fighter.has_method("presentation_snapshot") or not fighter.has_signal("presentation_changed"): return false
	if not fighter.is_ancestor_of(sprite): return false
	if fighter.has_meta(OWNER_META):
		var owner: Variant = fighter.get_meta(OWNER_META)
		if not owner is WeakRef or (owner.get_ref() != null and owner.get_ref() != self): return false
	unbind()
	_fighter = fighter
	_sprite = sprite
	_fighter.set_meta(OWNER_META,weakref(self))
	_original_placeholder = bool(fighter.procedural_visible)
	_original_sprite = {"position":sprite.position,"flip_h":sprite.flip_h,"visible":sprite.visible,
		"animation":sprite.animation,"frame":sprite.frame,"progress":sprite.frame_progress,"playing":sprite.is_playing()}
	_fighter.presentation_changed.connect(_on_presentation_changed)
	if not _timeline.invalidated.is_connected(refresh_animations): _timeline.invalidated.connect(refresh_animations)
	_timeline.set_frames(sprite.sprite_frames)
	refresh_animations()
	synchronize()
	set_physics_process(true)
	return true


func unbind() -> void:
	if is_instance_valid(_fighter):
		if _fighter.presentation_changed.is_connected(_on_presentation_changed):
			_fighter.presentation_changed.disconnect(_on_presentation_changed)
		_fighter.procedural_visible = _original_placeholder
		_fighter.queue_redraw()
		var owner: Variant = _fighter.get_meta(OWNER_META) if _fighter.has_meta(OWNER_META) else null
		if owner is WeakRef and owner.get_ref() == self: _fighter.remove_meta(OWNER_META)
	if is_instance_valid(_sprite) and not _original_sprite.is_empty():
		_sprite.pause()
		_sprite.position = _original_sprite["position"]
		_sprite.flip_h = _original_sprite["flip_h"]
		_sprite.visible = _original_sprite["visible"]
		var original: StringName = _original_sprite["animation"]
		var frames := _sprite.sprite_frames
		if frames != null and frames.has_animation(original) and frames.get_frame_count(original) > 0:
			_sprite.animation = original
			if _original_sprite["playing"]: _sprite.play(original)
			_sprite.set_frame_and_progress(mini(int(_original_sprite["frame"]),frames.get_frame_count(original)-1),float(_original_sprite["progress"]))
	_fighter = null
	_sprite = null
	_original_sprite.clear()
	_animation = &""
	_state = &""
	_sequence = -1
	_timeline.set_frames(null)
	set_physics_process(false)


func _exit_tree() -> void:
	unbind()


func _on_presentation_changed(_state_name: StringName,_move: StringName,_id: int) -> void:
	refresh_animations()


func refresh_animations() -> void:
	_selection_dirty = true


func _select(snapshot: Dictionary) -> void:
	_selection_dirty = false
	var state: StringName = snapshot["state"]
	var move := String(snapshot["move"])
	var phase: String = snapshot["phase"]
	var candidates: Array[String] = [String(state)]
	if not phase.is_empty(): candidates = [move+"."+phase,String(state),move,"attack"]
	if state == &"walk": candidates.append("idle")
	var selected: StringName = &""
	var selected_phase: bool = false
	if animation_map.size() <= 128:
		for key in candidates:
			var mapped: Variant = animation_map.get(key,key)
			if typeof(mapped) not in [TYPE_STRING,TYPE_STRING_NAME] or String(mapped).length() > 128: continue
			if not _timeline.track(StringName(mapped)).is_empty():
				selected = StringName(mapped)
				selected_phase = not phase.is_empty() and key in [move+"."+phase,String(state)]
				break
	if selected != _animation or int(snapshot["sequence"]) != _sequence or state != _state:
		# State phase changes sharing one full-move clip retain attack_time below.
		_started_at = float(snapshot["clock"])
	_animation = selected
	_state = state
	_sequence = int(snapshot["sequence"])
	_phase_clip = selected_phase
	var show_sprite: bool = not selected.is_empty() and bool(_original_sprite["visible"])
	_sprite.visible = show_sprite
	var show_placeholder: bool = _original_placeholder and (not show_sprite or not hide_placeholder)
	if _fighter.procedural_visible != show_placeholder:
		_fighter.procedural_visible = show_placeholder
		_fighter.queue_redraw()
	if not selected.is_empty():
		_sprite.animation = selected
		_sprite.pause()


func _physics_process(_delta: float) -> void:
	synchronize()


func synchronize() -> void:
	if not is_instance_valid(_fighter) or not is_instance_valid(_sprite):
		unbind()
		return
	_timeline.set_frames(_sprite.sprite_frames)
	if _selection_dirty: _select(_fighter.presentation_snapshot())
	_sprite.position = Vector2(_original_sprite["position"])-Vector2(0,float(_fighter.height))
	_sprite.flip_h = bool(_original_sprite["flip_h"]) != (float(_fighter.facing) < 0)
	if _animation.is_empty(): return
	var value: Dictionary = _timeline.track(_animation)
	if value.is_empty():
		refresh_animations()
		return
	var seconds: float = maxf(0.0,float(_fighter.anim_time)-_started_at)
	var loop: bool = value["loop"]
	if String(_state).begins_with("attack_"):
		var move: Dictionary = _fighter.current_move
		var windup: float = move.get("windup",0.0)
		var active: float = move.get("active",0.0)
		var recovery: float = move.get("recovery",0.0)
		var duration := windup+active+recovery
		var clock: float = _fighter.attack_time
		if _phase_clip:
			if _state == &"attack_active": clock -= windup
			if _state == &"attack_recovery": clock -= windup+active
			duration = float(move.get(String(_state).trim_prefix("attack_"),0.0))
		seconds = clampf(clock/duration,0.0,1.0)*float(value["duration"]) if duration > 0 else float(value["duration"])
		loop = false
	var sample: Vector2 = _timeline.sample(_animation,seconds,loop)
	if sample.x >= 0: _sprite.set_frame_and_progress(int(sample.x),sample.y)
