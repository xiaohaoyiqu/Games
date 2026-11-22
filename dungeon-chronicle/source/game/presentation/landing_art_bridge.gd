extends "res://game/presentation/fighter_animation_bridge.gd"

# Training and camp heroes share this short, presentation-only landing clip.
const LAND_DURATION: float = 3.0 / 16.0
var landing_interrupted: Callable
var _landing_until: float = -1.0


func on_ground_landed(_source: Node2D) -> void:
	if not is_instance_valid(_fighter): return
	_landing_until = float(_fighter.anim_time) + LAND_DURATION
	refresh_animations()


func clear_landing() -> void:
	if _landing_until < 0.0: return
	_landing_until = -1.0
	refresh_animations()


func synchronize() -> void:
	if _landing_until >= 0.0 and is_instance_valid(_fighter):
		if float(_fighter.anim_time) >= _landing_until or (landing_interrupted.is_valid() and landing_interrupted.call()):
			clear_landing()
	super.synchronize()


func _select(snapshot: Dictionary) -> void:
	if _landing_until >= 0.0:
		if snapshot["state"] in [&"idle", &"walk"]:
			snapshot["state"] = &"land"
		else:
			_landing_until = -1.0
	super._select(snapshot)
