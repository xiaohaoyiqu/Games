extends RefCounted

## Local observations only: one live attempt and the last nonempty A/B attempts.
## No actors, clocks, files, Session, profiles, averages or strength estimates.
const LIMIT: int = 1_000_000_000
var revision: int = 0
var history_revision: int = 0
var _live: Dictionary = {}
var _last: Dictionary = {}

func restart(side: String, ai: bool, reason: String) -> bool:
	if side not in ["current","candidate"] or reason not in ["start","reset","switch","fixed_skill","fixed_incoming","fixed_walk","fixed_evade"]: return false
	if not _live.is_empty() and (int(_live["outgoing_hits"]) > 0 or int(_live["incoming_hits"]) > 0 or int(_live["skill_starts"]) > 0):
		var finished: Dictionary = _live.duplicate(true)
		finished["ended_by"] = reason
		_last[str(finished["side"])] = finished
		history_revision += 1
	_live = {"skill_starts":0,"side":side,"ai":ai,"ai_changed":false,"outgoing_hits":0,"outgoing_damage":0,
		"incoming_hits":0,"incoming_damage":0,"player_defeats":0,"target_defeats":0,"incomplete":false}
	revision += 1
	return true

func set_ai(enabled: bool) -> void:
	if _live.is_empty() or bool(_live["ai"]) == enabled: return
	_live["ai"] = enabled
	_live["ai_changed"] = true
	revision += 1

func _add(field: String, amount: int) -> void:
	var remaining: int = LIMIT - int(_live[field])
	if amount > remaining: _live["incomplete"] = true
	_live[field] = int(_live[field]) + mini(amount, remaining)

func started_skill() -> void:
	if _live.is_empty():return
	_add("skill_starts",1);revision+=1

func dealt(damage: int, defeated: bool) -> void:
	if _live.is_empty() or damage <= 0: return
	_add("outgoing_hits",1); _add("outgoing_damage",damage)
	if defeated: _add("target_defeats",1)
	revision += 1

func received(damage: int, defeated: bool) -> void:
	if _live.is_empty() or damage <= 0: return
	_add("incoming_hits",1); _add("incoming_damage",damage)
	if defeated: _add("player_defeats",1)
	revision += 1

func live_snapshot() -> Dictionary:
	return _live.duplicate(true)

func history_snapshot() -> Dictionary:
	return _last.duplicate(true)

func describe() -> Dictionary:
	return {"live":live_snapshot(),"last":history_snapshot()}
