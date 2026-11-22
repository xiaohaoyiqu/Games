extends "res://game/main/dungeon.gd"

signal terminal_review_ready

const Frame = preload("res://game/network/battle_frame.gd")
const HitReview = preload("res://game/network/party_hit_review.gd")
var _replica_sounds=preload("res://game/audio/replica_cues.gd").new()
const Trace = preload("res://game/diagnostics/frame_trace.gd")
const SNAPSHOT_INTERVAL: float = 0.05
const MAX_CANCEL_RETRY_INTERVAL: float = 0.25
const WALK_TIMEOUT_SECONDS: float = 0.25
const RESULT_RETRY_INTERVAL: float = 0.25
const MAX_RESULT_RETRY_INTERVAL: float = 2.0
const SNAPSHOT_WARNING_SECONDS: float = 1.0
var service: Node
var heroes: Dictionary = {}
var hero_names: Dictionary = {}
var terminal_review: Dictionary = {}
var local_terminal_hits: Dictionary = {}
var _member_hit_logs: Dictionary = {}
var _member_damage_taken: Dictionary = {}
var _actors: Dictionary = {}
var _inputs: Dictionary = {}
var _input_sequences: Dictionary = {}
var _actions: Dictionary = {}
var _arrow_ids: Dictionary = {}
var _visual_arrows: Dictionary = {}
var _next_arrow: int = 0
var _next_loot: int = 0
var _tick: int = 0
var _clock: float = 0.0
var _snapshot_clock: float = 0.0
var _input_clock: float = 0.0
var _repeat_stop_until: float = 0.0
var _next_idle_input: float = 0.0
var _gap_cancel_queued: bool = false
var _pending_cancel_room: int = -1
var _next_cancel_retry: float = 0.0
var _cancel_retry_interval: float = SNAPSHOT_INTERVAL
var _latest_tick: int = 0
var _has_replica_frame: bool = false
var _since_snapshot: float = 0.0
var _last_snapshot_usec: int = Time.get_ticks_usec()
var _blend: Dictionary = {}
var _replica_exit: Dictionary = {}
var _plan: Dictionary = {}
var _manifest: Dictionary = {}
var _active: bool = false
var _result_retry_wait: float = 0.0
var _result_retry_interval: float = RESULT_RETRY_INTERVAL
var _retreated: bool = false
var _local_blocked: bool = false
var retreat_confirmation_open: bool = false
var _has_focus: bool = true
var read_local_input: bool = true


func _uses_motion_sound() -> bool:return false


func _entry_session() -> Node:
	return service._session if is_instance_valid(service) and is_instance_valid(service._session) else Session


func _ready() -> void:
	if not _guard_entry(): return
	var measured: int = Trace.begin()
	_prepare_scene()
	Trace.end("scene_ready",measured,run.room_index)


func _prepare_scene() -> void:
	if service == null: service = Session.party_service()
	_manifest = service.manifest(); _plan = service.run_plan()
	if _manifest.is_empty() or _plan.is_empty():
		_defer_entry_return("res://game/main/party_lobby.tscn",service)
		return
	run_id = _manifest["run"]
	service.started.connect(_start)
	service.frame_received.connect(_receive_frame)
	service.input_received.connect(_receive_input)
	service.action_received.connect(_receive_action)
	service.departed.connect(_departed)
	service.changed.connect(_refresh_retreat_confirmation)
	super._ready()
	if entry_blocked: return
	_active = service.running() and service.owns_run(run_id)
	acknowledge.call_deferred()


func acknowledge() -> void:
	if is_inside_tree() and is_instance_valid(player): service.acknowledge_loaded(run_id)


func _start() -> void:
	if not entry_blocked: _active = service.running() and service.owns_run(run_id)
func _move_definitions() -> Dictionary: return _plan.get("moves",{})
func _hud_script() -> String: return "res://game/ui/party_hud.gd"


func _populate() -> void:
	roster = _plan["roster"]; dungeon_data = _plan["dungeon"]; difficulty = _plan["preview"]
	move_data = _plan["moves"]; run.room_count = dungeon_data["rooms"].size()
	var host_records: bool = service.hosting()
	for index in range(_manifest["members"].size()):
		var member: Dictionary = _manifest["members"][index]
		var id: int = int(member["peer"])
		var hero: Node2D = _spawn(Vector2(160,450+index*48),true)
		var stats: Dictionary = service._contract.stats[id]
		hero.max_hp = int(stats["hp"]); hero.hp = hero.max_hp
		hero.damage_multiplier = stats["damage"]; hero.incoming_multiplier = stats["incoming"]
		hero.move_speed_multiplier = stats["speed"]; hero.skill_cooldown_multiplier = stats["cooldown"]
		hero.auto_respawn = false; hero.hurt_invulnerability = 0.65
		hero.modulate = [Color.WHITE,Color("b8e4ff"),Color("e0c4ff"),Color("c5e7b2")][index]
		if member["character"]["weapon"] == "echo_blade": hero.blade_color = Color("89e6df")
		hero.received_hit.connect(_player_hurt)
		heroes[id] = hero; hero_names[id] = member["name"]; _actors[id] = hero
		if host_records:
			_member_hit_logs[id] = Review.new(roster,move_data)
			_member_damage_taken[id] = 0
	player = heroes[service.local_id()]
	battle_review = _member_hit_logs.get(service.local_id())
	_load_room()


func _load_room() -> void:
	var measured: int = Trace.begin()
	_load_party_room()
	Trace.room = run.room_index
	Trace.end("room_load",measured,run.room_index)


func _load_party_room() -> void:
	for id in _actors.keys():
		if int(id) < 0: _actors.erase(id)
	_build_room(service.hosting())
	_arrow_ids.clear(); _visual_arrows.clear(); _blend.clear()
	for i in range(enemies.size()): _actors[-1-run.room_index*8-i] = enemies[i]
	_inputs.clear()


func _place_room_heroes() -> void:
	for index in range(_manifest["members"].size()):
		var id: int = int(_manifest["members"][index]["peer"])
		if heroes.has(id):
			heroes[id].enter_room(Vector2(160,450+index*48))


func _refresh_targets() -> void:
	for fighter in fighters: fighter.targets = _opponents_for(fighter)


func _unhandled_input(event: InputEvent) -> void:
	if entry_blocked or event.is_echo(): return
	if event.is_action_pressed("toggle_shake") and not retreat_confirmation_open:
		_toggle_shake()
		return
	if service == null or not service.owns_run(run_id): return
	if retreat_confirmation_open:
		if event.is_action_pressed("pause_game"): cancel_retreat_confirmation()
		elif event.is_action_pressed("interact"): confirm_retreat()
		return
	if _active and event.is_action_pressed("quests") and ui.quest_tracker.visible:
		ui.quest_tracker.toggle_collapsed()
		return
	if event.is_action_pressed("return_menu"):
		if service.hosting():
			if run.state in [Progress.State.VICTORY,Progress.State.DEFEAT]: service.return_to_lobby()
			elif _can_confirm_retreat():
				retreat_confirmation_open = true
				_clear_local_commands()
				ui.show_retreat_confirmation(true)
			elif not _active:
				service.cancel_loading()
		else: service.leave()
		return
	if event.is_action_pressed("interact") and service.pending_save():
		retry_result_save()
		return
	if event.is_action_pressed("pause_game"):
		set_personal_pause(not _local_blocked)
		return
	if event.is_action_pressed("toggle_debug"): show_debug = not show_debug
	if not _local_controls_available(): return
	for action in ["attack","jump","dash","skill","interact"]:
		if event.is_action_pressed(action): request_player_action(action)


func can_change_personal_pause() -> bool:
	return (is_inside_tree() and not is_queued_for_deletion() and not entry_blocked and _has_focus
		and not retreat_confirmation_open and run.state in [Progress.State.ACTIVE,Progress.State.CLEARED]
		and is_instance_valid(service) and service.owns_run(run_id))


func set_personal_pause(value: bool) -> void:
	var changed := value != _local_blocked and can_change_personal_pause()
	if changed:
		_local_blocked = value
		_clear_local_commands()
	if is_instance_valid(ui) and ui.is_node_ready():
		ui.refresh_personal_pause(changed and not value)


func can_retry_result_save() -> bool:
	return _run_is_active() and run.state in [Progress.State.VICTORY,Progress.State.DEFEAT] and service.accepted_result_tick(run_id) >= 0 and service.pending_save() and not service.saving()


func retry_result_save() -> void:
	if can_retry_result_save(): service.retry_save()


func _can_confirm_retreat() -> bool:
	return _has_focus and _battle_commands_open() and service.hosting()


func cancel_retreat_confirmation() -> void:
	if not retreat_confirmation_open: return
	retreat_confirmation_open = false
	ui.show_retreat_confirmation(false)


func confirm_retreat() -> bool:
	if not retreat_confirmation_open: return false
	cancel_retreat_confirmation()
	if not _can_confirm_retreat(): return false
	_retreated = true
	run.fail()
	_finish()
	return true


func _refresh_retreat_confirmation() -> void:
	if retreat_confirmation_open and not _can_confirm_retreat():
		cancel_retreat_confirmation()


func _clear_local_commands() -> void:
	if not _battle_commands_open() or input_cancel_pending(): return
	service.send_input(Vector2.ZERO,run.room_index)
	_queue_local_cancel()


func input_cancel_pending() -> bool:
	return _pending_cancel_room == run.room_index


func _queue_local_cancel() -> void:
	var retrying := input_cancel_pending()
	if retrying and _clock < _next_cancel_retry: return
	_next_idle_input = 0.0
	var room_index: int = run.room_index
	if not service.send_action("cancel",_tick if service.hosting() else _latest_tick,room_index):
		_cancel_retry_interval = minf(_cancel_retry_interval*2.0,MAX_CANCEL_RETRY_INTERVAL) if retrying else SNAPSHOT_INTERVAL
		_next_cancel_retry = _clock+_cancel_retry_interval
		_pending_cancel_room = room_index
		return
	_pending_cancel_room = -1
	if waiting_for_snapshot():
		_gap_cancel_queued = true


func _run_is_active() -> bool:
	return _active and service != null and service.running() and service.owns_run(run_id)


func _battle_commands_open() -> bool:
	return (_run_is_active() and run.state in [Progress.State.ACTIVE,Progress.State.CLEARED]
		and (service.hosting() or _has_replica_frame))


func _local_controls_available() -> bool:
	return _battle_commands_open() and _has_focus and not _local_blocked and not retreat_confirmation_open and not input_cancel_pending()


func request_player_action(action: String) -> void:
	if _local_controls_available() and not waiting_for_snapshot():
		service.send_action(action,_tick if service.hosting() else _latest_tick,run.room_index)


func _pauses_on_focus_loss() -> bool: return false


func _notification(what: int) -> void:
	if entry_blocked: return
	if what == NOTIFICATION_APPLICATION_FOCUS_OUT:
		_has_focus = false
		cancel_retreat_confirmation()
		_stop_sound()
		if service != null: _clear_local_commands()
		for action in ["move_left","move_right","move_up","move_down"]: Input.action_release(action)
	elif what == NOTIFICATION_APPLICATION_FOCUS_IN: _has_focus = true
	else: return
	if is_instance_valid(ui) and ui.is_node_ready(): ui.refresh_personal_pause()


func _receive_input(peer: int,message: Dictionary) -> void:
	if entry_blocked: return
	if not service.hosting() or not _battle_commands_open() or not heroes.has(peer) or int(message["room"]) != run.room_index: return
	var sequence: int = int(message["seq"])
	if sequence <= int(_input_sequences.get(peer,0)): return
	_input_sequences[peer] = sequence
	var walk := Vector2(message["walk"][0],message["walk"][1]).limit_length()
	if walk == Vector2.ZERO:
		_inputs.erase(peer)
		heroes[peer].walk_input = Vector2.ZERO
		return
	_inputs[peer] = {"walk":walk,"expires":_clock+WALK_TIMEOUT_SECONDS,
		"expires_usec":Time.get_ticks_usec()+int(WALK_TIMEOUT_SECONDS*1000000.0)}


func _receive_action(peer: int,message: Dictionary) -> void:
	if entry_blocked: return
	if not service.hosting() or not _battle_commands_open() or not heroes.has(peer) or int(message["room"]) != run.room_index: return
	var sequence: int = int(message["seq"])
	if sequence <= int(_actions.get(peer,0)): return
	_actions[peer] = sequence
	if message["action"] == "cancel":
		heroes[peer].clear_buffered_actions()
		# Invalidate movement sent before cancel without discarding movement after it.
		if sequence > int(_input_sequences.get(peer,0)):
			_input_sequences[peer] = sequence
			_inputs.erase(peer)
			heroes[peer].walk_input = Vector2.ZERO
		return
	# Old reliable actions must not execute seconds after a connection stall.
	if int(message["tick"]) < _tick-24 or int(message["tick"]) > _tick+3: return
	if message["action"] == "interact":
		_try_party_exit(peer)
	else: heroes[peer].request_action(message["action"])


func _physics_process(delta: float) -> void:
	if entry_blocked: return
	var measured: int = Trace.begin()
	var host: bool = service != null and service.hosting()
	_step_party(delta)
	Trace.end("host_physics" if host else "client_physics",measured,run.room_index)


func _step_party(delta: float) -> void:
	if service == null or player == null: return
	elapsed += delta; _clock += delta; _since_snapshot += delta
	_forest_details.step(delta)
	ui.queue_redraw(); queue_redraw()
	if not _run_is_active():
		if not _blend.is_empty():
			if terminal_review.is_empty():
				_blend.clear()
			else:
				# The accepted result still owns the final visual pose after disconnect.
				_advance_replica_pose()
		return
	if (read_local_input or input_cancel_pending()) and _battle_commands_open():
		_input_clock += delta
		if _input_clock >= SNAPSHOT_INTERVAL:
			_input_clock = fmod(_input_clock,SNAPSHOT_INTERVAL)
			if input_cancel_pending():
				_queue_local_cancel()
			elif waiting_for_snapshot():
				if not _gap_cancel_queued:
					_queue_local_cancel()
			elif _local_controls_available():
				_send_local_walk()
	if service.hosting():
		_tick += 1
		if run.state in [Progress.State.VICTORY,Progress.State.DEFEAT]:
			if not service.result_queued(run_id):
				_result_retry_wait -= delta
				if _result_retry_wait <= 0.0: _finish()
			return
		_advance_host(delta)
		if run.state in [Progress.State.VICTORY,Progress.State.DEFEAT]: return
		_snapshot_clock += delta
		if _snapshot_clock >= SNAPSHOT_INTERVAL:
			_snapshot_clock = fmod(_snapshot_clock,SNAPSHOT_INTERVAL)
			service.publish(make_frame())
	elif not _blend.is_empty():
		_advance_replica_pose()


func _send_local_walk() -> void:
	var walk := Input.get_vector("move_left","move_right","move_up","move_down")
	if service.hosting():
		service.send_input(walk,run.room_index)
		return
	if walk != Vector2.ZERO:
		_repeat_stop_until = _clock + WALK_TIMEOUT_SECONDS
		_next_idle_input = 0.0
	elif _clock < _next_idle_input:
		return
	# Repeat the stop at the movement cadence while the host could still be walking.
	if service.send_input(walk,run.room_index) and walk == Vector2.ZERO and _clock >= _repeat_stop_until:
		_next_idle_input = _clock + WALK_TIMEOUT_SECONDS


func _advance_replica_pose() -> void:
	var arrived: bool = _since_snapshot >= SNAPSHOT_INTERVAL
	var fraction: float = _since_snapshot/SNAPSHOT_INTERVAL
	for id in _blend:
		var fighter: Node2D = _actors.get(id)
		if not is_instance_valid(fighter): continue
		var motion: Dictionary = _blend[id]
		var target: Vector2 = motion["to"]
		var height: float = motion["height_to"]
		if not arrived:
			target = motion["from"].lerp(target,fraction)
			height = lerpf(motion["height_from"],height,fraction)
		fighter.position = target
		# Ground movement uses the node transform; local drawing depends on height.
		if fighter.height != height:
			fighter.height = height
			fighter._publish_presentation()
			fighter.queue_redraw()
			if arrived and height == 0.0 and fighter.is_player and fighter.hp > 0:
				if run.state in [Progress.State.ACTIVE,Progress.State.CLEARED]:
					fighter.ground_landed.emit(fighter)
				else:
					# Terminal snapshots stop the animation clock.
					_on_forest_landing(fighter)
	if arrived: _blend.clear()


func _advance_host(delta: float) -> void:
	var measured: int = Trace.begin()
	_step_host_model(delta)
	Trace.end("host_model",measured,run.room_index)


func _step_host_model(delta: float) -> void:
	for collection in [effects,numbers]:
		for i in range(collection.size()-1,-1,-1):
			collection[i]["life"] -= delta
			if collection[i]["life"] <= 0: collection.remove_at(i)
	combo_timer = maxf(0,combo_timer-delta)
	if combo_timer <= 0: combo = 0
	shake = maxf(0,shake-delta*25)
	world.position = Vector2(sin(elapsed*110),cos(elapsed*132))*shake if shake_enabled else Vector2.ZERO
	if hit_stop > 0: hit_stop = maxf(0,hit_stop-delta); return
	run.elapsed += delta; intro_time = maxf(0,intro_time-delta); notification_time = maxf(0,notification_time-delta)
	var now_usec := Time.get_ticks_usec()
	for id in heroes:
		var input: Variant = _inputs.get(id)
		if input != null and (_clock > input["expires"] or now_usec > input["expires_usec"]):
			_inputs.erase(id)
			input = null
		heroes[id].walk_input = input["walk"] if input != null else Vector2.ZERO
	var existing_shots := arrows.size()
	for fighter in fighters:
		fighter.debug_boxes = show_debug
		if not fighter.is_player: _update_enemy(fighter)
		fighter.advance(delta)
	_advance_arrows(delta,existing_shots)
	for enemy in enemies:
		if enemy.hp <= 0 and run.record_kill(enemy.get_instance_id()):
			_next_loot += 1
			_place_drop(_next_loot,enemy.position,int(enemy_profiles[enemy.get_instance_id()]["gold"]),0)
	_collect_party_loot()
	if not _has_living_hero():
		run.fail(); _finish(); return
	var previous: int = run.state
	run.sync_enemies(living_enemies())
	if previous != run.state:
		if run.state == Progress.State.CLEARED:
			_next_loot += 1
			_place_drop(_next_loot,Vector2(1050,560),0,int(dungeon_data["room_heal"]))
		elif run.state == Progress.State.VICTORY: _sweep_gold(); _finish()


func _collect_party_loot() -> void:
	for index in range(loot.size()-1,-1,-1):
		for id: int in heroes:
			if _try_pickup(loot[index],heroes[id]):
				loot.remove_at(index)
				break


func _has_living_hero() -> bool:
	for id: int in heroes:
		if heroes[id].hp > 0: return true
	return false


func _enemy_target(point: Vector2) -> Node2D:
	var nearest: Node2D
	var nearest_distance := INF
	for id: int in heroes:
		var hero: Node2D = heroes[id]
		if hero.hp <= 0: continue
		var distance := hero.position.distance_squared_to(point)
		if distance < nearest_distance:
			nearest = hero
			nearest_distance = distance
	return nearest


func _player_hurt(victim: Node2D,damage: int) -> void:
	super._player_hurt(victim,damage)
	if _member_hit_logs.is_empty(): return
	var peer: Variant = heroes.find_key(victim)
	if _member_damage_taken.has(peer):
		_member_damage_taken[peer] += damage


func _record_received_hit(source: Node2D,victim: Node2D,damage: int,move_id: String) -> void:
	if _member_hit_logs.is_empty() or damage <= 0: return
	var log: RefCounted = _member_hit_logs.get(heroes.find_key(victim))
	if log != null:
		log.record(str(source.enemy_kind),move_id,damage,victim.hp,run.room_index,run.elapsed)


func _retire_arrow(arrow: Node2D) -> void:
	_arrow_ids.erase(arrow.get_instance_id())
	super._retire_arrow(arrow)


func _fire_arrow(source: Node2D,move: Dictionary) -> void:
	if arrows.size() >= 24: return
	var nearest := _enemy_target(source.position)
	if nearest == null: return
	var arrow := Arrow.new()
	arrow.aim(source,nearest.position,move,obstacles)
	for hero in heroes.values(): arrow.targets.append(hero)
	arrow.impacted.connect(_on_arrow_hit.bind(str(source.attack_name)))
	_next_arrow += 1; _arrow_ids[arrow.get_instance_id()] = _next_arrow
	world.add_child(arrow); arrows.append(arrow)
	_play_sound("shot")


func exit_open() -> bool:
	return _run_is_active() and run.state == Progress.State.CLEARED


func _landing_clip_interrupted() -> bool:
	return run_finished() or not _run_is_active() or waiting_for_snapshot()


func _show_exit_guidance() -> bool:
	return exit_open() and not waiting_for_snapshot()


func _snapshot_late(now_usec: int) -> bool:
	return (_since_snapshot > SNAPSHOT_WARNING_SECONDS
		or now_usec-_last_snapshot_usec > int(SNAPSHOT_WARNING_SECONDS*1000000.0))


func waiting_for_snapshot() -> bool:
	return not service.hosting() and (not _has_replica_frame or _snapshot_late(Time.get_ticks_usec()))


func exit_status(peer: int) -> int:
	if not exit_open() or not heroes.has(peer): return Exit.Status.HIDDEN
	if service.hosting():
		var hero: Node2D = heroes[peer]
		return Exit.status(hero.position,hero.height,hero.hp)
	if waiting_for_snapshot(): return Exit.Status.HIDDEN
	return _replica_exit.get(peer,Exit.Status.HIDDEN)


func _try_party_exit(peer: int) -> bool:
	if not exit_open() or not heroes.has(peer) or heroes[peer].hp <= 0: return false
	for hero in heroes.values():
		if hero.hp <= 0: continue
		if not Exit.can_cross(hero.position,hero.height): return false
	_sweep_gold()
	if not run.try_advance(true): return false
	_load_room(); return true


func try_exit() -> bool:
	return _try_party_exit(service.local_id()) if service.hosting() else false


func _departed(peer: int) -> void:
	if entry_blocked or not service.owns_run(run_id): return
	if not service.hosting() or not heroes.has(peer): return
	var hero: Node2D = heroes[peer]
	heroes.erase(peer); _actors.erase(peer); _inputs.erase(peer); _actions.erase(peer); _input_sequences.erase(peer)
	_member_hit_logs.erase(peer); _member_damage_taken.erase(peer)
	for arrow in arrows:
		arrow.targets.erase(hero)
	fighters.erase(hero); world.remove_child(hero); hero.queue_free()
	_refresh_targets()


func make_frame() -> Dictionary:
	var measured: int = Trace.begin()
	var result: Dictionary = _snapshot()
	Trace.end("snapshot",measured,run.room_index)
	return result


func _snapshot() -> Dictionary:
	var packed_actors: Array = []
	for id in _actors:
		var fighter: Node2D = _actors[id]
		packed_actors.append(Frame.pack(id,0 if id > 0 else Frame.KINDS.find(fighter.enemy_kind),fighter))
	var packed_arrows: Array = []
	for arrow in arrows:
		packed_arrows.append([_arrow_ids[arrow.get_instance_id()],snappedf(arrow.position.x,0.01),snappedf(arrow.position.y,0.01),snappedf(arrow.travel.x,0.01),snappedf(arrow.travel.y,0.01),arrow.flight_height])
	var packed_loot: Array = []
	for drop in loot: packed_loot.append([drop["id"],snappedf(drop["point"].x,0.01),snappedf(drop["point"].y,0.01),drop["gold"],drop["heal"]])
	return {"tick":_tick,"room":run.room_index,"phase":run.state,"kills":run.kills,"gold":run.coins,"elapsed":snappedf(run.elapsed,0.001),"retreat":_retreated,"actors":packed_actors,"arrows":packed_arrows,"loot":packed_loot}


func _build_hit_review(frame: Dictionary) -> Array[Dictionary]:
	var rows: Array[Dictionary] = []
	for actor: Array in frame["actors"]:
		var peer: int = int(actor[0])
		var log: RefCounted = _member_hit_logs.get(peer)
		if log == null: continue
		var total: int = _member_damage_taken[peer]
		var recent: Array[Dictionary] = log.recent_records()
		for hit in recent: hit["time"] = snappedf(hit["time"],0.001)
		rows.append({"peer":peer,"damage":total,"observed":log.damage_total,
			"complete":not log.incomplete and total == log.damage_total,"recent":recent})
	return rows


func _finish() -> void:
	cancel_retreat_confirmation()
	ui.refresh_personal_pause()
	if service.accepted_result_tick(run_id) >= 0:
		if service.retry_result_delivery(run_id): return
	else:
		var frame: Dictionary = make_frame()
		if service.publish(frame,true,_build_hit_review(frame)): return
	_result_retry_wait = _result_retry_interval
	_result_retry_interval = minf(_result_retry_interval*2.0,MAX_RESULT_RETRY_INTERVAL)


func _settle_once() -> bool: return service.retry_save()


func _receive_frame(value: Dictionary) -> void:
	if entry_blocked or not _run_is_active(): return
	var measured: int = Trace.begin()
	_apply_replica(value)
	if run_finished() and ui.personal_pause_button.visible: ui.refresh_personal_pause()
	_capture_terminal_review(value)
	Trace.room = run.room_index
	Trace.end("replica",measured,run.room_index)


static func _terminal_members(members: Array,actors: Array) -> Array[Dictionary]:
	var health: Dictionary = {}
	for actor: Array in actors:
		var peer: int = int(actor[0])
		if peer > 0: health[peer] = Vector2i(int(actor[5]),int(actor[6]))
	var rows: Array[Dictionary] = []
	for member: Dictionary in members:
		var peer: int = int(member["peer"])
		var row := {"peer":peer,"name":str(member["name"]),"present":health.has(peer)}
		if health.has(peer):
			var values: Vector2i = health[peer]
			row["hp"] = values.x
			row["max_hp"] = values.y
		rows.append(row)
	return rows


func _capture_terminal_review(value: Dictionary) -> void:
	if not terminal_review.is_empty() or int(value["phase"]) not in [Progress.State.VICTORY,Progress.State.DEFEAT]:
		return
	if service.accepted_result_tick(run_id) != int(value["tick"]): return
	var members: Array[Dictionary] = _terminal_members(_manifest["members"],value["actors"])
	terminal_review = {"room":int(value["room"])+1,"elapsed_ms":roundi(float(value["elapsed"])*1000.0),"members":members}
	local_terminal_hits = HitReview.describe(service.local_hit_review(run_id),roster,move_data)
	if local_terminal_hits.is_empty() and battle_review != null:
		var local_id: int = service.local_id()
		for row in members:
			if row["peer"] == local_id and row["present"]:
				local_terminal_hits = battle_review.describe(_member_damage_taken[local_id],int(row["hp"]) == 0)
				break
	terminal_review_ready.emit()


func _apply_replica(value: Dictionary) -> void:
	if service.hosting() or not _active: return
	var changed_room: bool = int(value["room"]) != run.room_index
	var received_usec := Time.get_ticks_usec()
	var after_gap := _snapshot_late(received_usec)
	# Network polling can receive the recovery frame before physics observes the stall.
	var missed_cancel := after_gap and _has_replica_frame and not changed_room and not _gap_cancel_queued and not input_cancel_pending()
	var continuous_room := _has_replica_frame and not changed_room and not after_gap
	_has_replica_frame = true
	_last_snapshot_usec = received_usec
	_gap_cancel_queued = false
	if changed_room or after_gap: _next_idle_input = 0.0
	if changed_room: _stop_sound()
	elif after_gap:
		_forest_details.clear()
		# A fresh packet may arrive before the art bridge gets a physics tick.
		if run.room_index == ForestArt.CAMP_ROOM:
			for hero: Node2D in heroes.values():
				CampActorArt.clear_landing(hero)
	_latest_tick = int(value["tick"]); _since_snapshot = 0
	run.room_index = int(value["room"]); run.state = int(value["phase"])
	if missed_cancel and _battle_commands_open():
		_queue_local_cancel()
	if changed_room:
		_update_room_art()
	run.kills = int(value["kills"]); run.coins = int(value["gold"]); run.elapsed = value["elapsed"]
	_retreat_from_frame(value)
	_replica_exit.clear()
	var cleared: bool = run.state == Progress.State.CLEARED
	enemies.clear(); boss = null
	for actor in value["actors"]:
		var id: int = int(actor[0])
		var target_position := Vector2(actor[2],actor[3])
		var target_height: float = actor[4]
		if cleared and id > 0:
			_replica_exit[id] = Exit.status(target_position,target_height,int(actor[5]),true)
		var fresh: bool = not _actors.has(id)
		if fresh:
			var fighter: Node2D = _spawn(target_position,id > 0)
			fighter.auto_respawn = false; _actors[id] = fighter
		var fighter: Node2D = _actors[id]
		var previous: Vector2 = fighter.position
		var old_height: float = fighter.height
		var hammer_was_active: bool = fighter.attack_name == "bulwark_crush" and fighter.action_sequence == int(actor[17]) and fighter.attack_time >= float(actor[10])
		var moved: bool = previous != target_position or old_height != target_height
		var continuous_pose: bool = continuous_room and not fresh and previous.distance_squared_to(target_position) <= 180.0 * 180.0
		var interpolate: bool = moved and continuous_pose
		if interpolate:
			_blend[id] = {"from":previous,"to":target_position,"height_from":old_height,"height_to":target_height}
		else:
			_blend.erase(id)
		fighter.enemy_kind = Frame.KINDS[int(actor[1])]
		Frame.apply(actor,fighter,move_data,not interpolate,continuous_room and not fresh)
		if changed_room or fresh:
			_style_camp_actor(fighter)
		elif continuous_pose and fighter.hp > 0 and actor[8] == "bulwark_crush" and float(actor[9]) >= float(actor[10]) and not hammer_was_active:
			_forest_hammer_contact(fighter, target_position)
		if id < 0:
			enemies.append(fighter)
			if fighter.enemy_kind == "warden": boss = fighter
	# After insertion the registry contains every present ID, plus any departures.
	if _actors.size() > value["actors"].size():
		var present: Dictionary = {}
		for actor: Array in value["actors"]: present[int(actor[0])] = true
		for id in _actors.keys():
			if not present.has(id):
				var fighter: Node2D = _actors[id]
				_actors.erase(id); heroes.erase(id); _blend.erase(id); fighters.erase(fighter)
				world.remove_child(fighter); fighter.queue_free()
	_sync_arrows(value["arrows"])
	_sync_replica_loot(value["loot"])
	if changed_room: intro_time = 2.3
	for cue: String in _replica_sounds.observe_validated(value,service.local_id(),after_gap):_play_sound(cue)


func _sync_replica_loot(values: Array) -> void:
	var changed := false
	var visible_count := 0
	for drop: Array in values:
		# Older hosts can include already credited coins in a victory snapshot.
		var gold: int = 0 if run.state == Progress.State.VICTORY else int(drop[3])
		var heal := int(drop[4])
		if gold == 0 and heal == 0: continue
		var id := int(drop[0])
		var point := Vector2(drop[1],drop[2])
		if visible_count == loot.size():
			_place_drop(id,point,gold,heal)
			changed = true
		else:
			var previous: Dictionary = loot[visible_count]
			if previous["id"] != id or previous["point"] != point or previous["gold"] != gold or previous["heal"] != heal:
				loot[visible_count] = _drop_record(id,point,gold,heal)
				changed = true
		visible_count += 1
	if visible_count < loot.size():
		loot.resize(visible_count)
		changed = true
	if changed: queue_redraw()


func _retreat_from_frame(value: Dictionary) -> void:
	_retreated = value["retreat"]


func _sync_arrows(values: Array) -> void:
	if values.is_empty() and arrows.is_empty() and _visual_arrows.is_empty(): return
	arrows.clear()
	for value in values:
		var id: int = int(value[0])
		var arrow: Node2D = _visual_arrows.get(id)
		if arrow == null:
			arrow = Arrow.new()
			world.add_child(arrow)
			_visual_arrows[id] = arrow
		arrow.position = Vector2(value[1],value[2])
		arrow.travel = Vector2(value[3],value[4])
		arrow.flight_height = value[5]
		arrows.append(arrow)
	if _visual_arrows.size() == values.size(): return
	var present: Dictionary = {}
	for value: Array in values: present[int(value[0])] = true
	for id in _visual_arrows.keys():
		if not present.has(id):
			var arrow: Node2D = _visual_arrows[id]; _visual_arrows.erase(id)
			world.remove_child(arrow); arrow.queue_free()


func _draw() -> void:
	if entry_blocked: return
	super._draw()
	if ui_font == null: return
	if _show_exit_guidance():
		draw_rect(Exit.FLOOR,Color(0.36,0.78,0.64,0.13))
		draw_rect(Exit.FLOOR,Color("65a995"),false,2)
	for id in heroes:
		var hero: Node2D = heroes[id]
		draw_string(ui_font,hero.position-Vector2(32,hero.height+120),str(hero_names[id])+(" · 你" if id == service.local_id() else ""),HORIZONTAL_ALIGNMENT_CENTER,100,14,Color("d7e7df"))
