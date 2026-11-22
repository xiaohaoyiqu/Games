extends RefCounted

enum State { ACTIVE, CLEARED, VICTORY, DEFEAT }
var room_index: int = 0
var room_count: int = 4
var state: State = State.ACTIVE
var coins: int = 0
var kills: int = 0
var damage_taken: int = 0
var elapsed: float = 0.0
var kill_ids: Dictionary = {}
var loot_ids: Dictionary = {}


func record_kill(identifier: int) -> bool:
	if state != State.ACTIVE or kill_ids.has(identifier):
		return false
	kill_ids[identifier] = true
	kills += 1
	return true


func collect_loot(identifier: int,value: int) -> bool:
	if state == State.DEFEAT or value <= 0 or loot_ids.has(identifier):
		return false
	loot_ids[identifier] = true
	coins += value
	return true


func sync_enemies(living: int) -> void:
	if state == State.ACTIVE and living == 0:
		state = State.VICTORY if room_index == room_count-1 else State.CLEARED


func try_advance(near_exit: bool) -> bool:
	if state != State.CLEARED or not near_exit:
		return false
	room_index += 1
	state = State.ACTIVE
	kill_ids.clear()
	return true


func fail() -> void:
	if state in [State.ACTIVE,State.CLEARED]:
		state = State.DEFEAT
