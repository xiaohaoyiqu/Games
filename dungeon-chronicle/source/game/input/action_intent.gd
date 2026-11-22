extends RefCounted

## One pending intention: later supported input replaces earlier input.
## No queue grows while a character is blocked, paused or holding a key.
const MAX_WINDOW: float = 0.24
const ACTIONS: Array[String] = ["attack","skill","jump","dash"]
var action: StringName = &""
var remaining: float = 0.0


func offer(value: String,window: float) -> bool:
	if value not in ACTIONS or not is_finite(window) or window <= 0.0: return false
	action = StringName(value)
	remaining = minf(window,MAX_WINDOW)
	return true


func pending(elapsed: float=0.0) -> StringName:
	if not is_finite(elapsed) or elapsed < 0.0 or elapsed >= remaining: return &""
	return action


func consume() -> StringName:
	var result := pending()
	clear()
	return result


func advance(delta: float) -> void:
	if not is_finite(delta) or delta < 0.0: return
	remaining = maxf(0.0,remaining-delta)
	if remaining <= 0.0: clear()


func clear() -> void:
	action = &""
	remaining = 0.0
