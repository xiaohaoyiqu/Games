extends "res://game/main/combat_room.gd"

const Practice = preload("res://game/training/guided_practice.gd")
const Arrow = preload("res://game/combat/arrow.gd")
const PRACTICE_SHOT_MOVE: String = "archer_shot"
const COVER_OBSTACLE: int = 0
# Extend the observation past the sides of the arrow's 36 px target width.
const DODGE_CLEARANCE: float = 24.0
const FIXED_TRIAL_OFFSET: Vector2 = Vector2(60,0)
const FIXED_TRIAL_MOVES: Dictionary = {"skill":"rising_slash","incoming":"bulwark_crush","evade":"bulwark_crush"}
const FIXED_WALK_START: Vector2 = Vector2(450,600)
const FIXED_WALK_FINISH: Vector2 = Vector2(450,408)
var guidance: RefCounted
var practice_context: Dictionary = {}
var from_battle_review: bool:
	get: return practice_context.get("kind") == "review"
var portal_return_note: String = ""
var equipment_practice: RefCounted
var equipment_observations: RefCounted
var equipment_notice: String=""
var fixed_trial_revision: int = 0
var fixed_trial_kind: String = ""
var _fixed_trial_attempt: Dictionary = {}
var _fixed_trial_results: Dictionary = {"skill":{},"incoming":{},"walk":{},"evade":{}}
var _fixed_trial_end: float = 0.0
var _fixed_trial_previous_ai: bool = false
var skill_start_count: int=0
var skill_count_incomplete: bool=false
var _last_skill_sequence: int=-1
var selected_lesson: int = Practice.Lesson.DEPTH
var _coach: Node2D
var _trial_sequence: int = -1
var _trial_active_end: float = 0.0
var _trial_window_completed: bool = false
var _trial_depth_escaped: bool = false
var _sequence_recovery_end: float = 0.0
var _practice_arrow: Node2D
var _projectile_qualified: bool = false


func _populate() -> void:
	equipment_practice=Session.take_equipment_practice(self)
	practice_context = Session.take_practice_context(self)
	player = _spawn(Vector2(450,550),true)
	player.skill_started.connect(_on_skill_started)
	_spawn(Vector2(720,540),false)
	_spawn(Vector2(866,415),false)
	_spawn(Vector2(918,596),false)
	for fighter in fighters:
		fighter.targets = _opponents_for(fighter)
	if equipment_practice!=null:
		equipment_observations=preload("res://game/training/equipment_trial_log.gd").new()
		player.received_hit.connect(_on_equipment_hurt)
		_apply_equipment_practice()
	elif has_portal_return():
		var lesson: int = practice_context.get("lesson",-1)
		if guidance_lesson_available(lesson):
			selected_lesson = lesson
			if start_selected_guidance(): return
		portal_return_note = "所选练习暂不可用，可返回出征选择。"


func _apply_equipment_practice() -> void:
	var stats: Dictionary=equipment_practice.stats()
	player.max_hp=int(stats["hp"]);player.damage_multiplier=float(stats["damage"])
	player.incoming_multiplier=float(stats["incoming"]);player.move_speed_multiplier=float(stats["speed"])
	player.skill_cooldown_multiplier=float(stats["cooldown"])
	player.blade_color=Color("89e6df") if equipment_practice.weapon_id()=="echo_blade" else Color("d6ecea")
	equipment_observations.set_ai(active_ai)
	skill_start_count=0;skill_count_incomplete=false
	super.reset_room();active_ai=false
	equipment_observations.restart(equipment_practice.selected_side(),false,"switch")
	for fighter in fighters:fighter.facing=1.0 if fighter==player else -1.0


func reset_room() -> void:
	if entry_blocked: return
	_release_practice_arrow()
	if fixed_trial_running(): _close_fixed_trial(false)
	skill_start_count=0;skill_count_incomplete=false
	if equipment_observations!=null: equipment_observations.set_ai(active_ai)
	super.reset_room()
	if equipment_observations!=null:
		equipment_observations.restart(equipment_practice.selected_side(),active_ai,"reset")


func _on_skill_started(source: Node2D, sequence: int, cooldown: float) -> void:
	if entry_blocked or paused or source!=player or not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene!=self:return
	if sequence<=_last_skill_sequence or sequence!=player.action_sequence or player.attack_name!="rising_slash" or player.hp<=0:return
	if not is_finite(cooldown) or cooldown<0.0 or not is_equal_approx(cooldown,float(player.skill_cooldown)):return
	_last_skill_sequence=sequence
	if fixed_trial_running():
		if fixed_trial_kind == "skill": _remember_fixed_attack(player)
		return
	if skill_start_count>=1_000_000_000:skill_count_incomplete=true
	else:skill_start_count+=1
	if equipment_observations!=null:
		equipment_observations.set_ai(active_ai);equipment_observations.started_skill()


func _on_equipment_hurt(victim: Node2D, damage: int) -> void:
	if paused or fixed_trial_running() or not _equipment_practice_ready() or victim!=player: return
	equipment_observations.set_ai(active_ai)
	equipment_observations.received(damage,player.hp==0)


func _equipment_practice_ready() -> bool:
	return not entry_blocked and equipment_practice!=null and is_inside_tree() and not is_queued_for_deletion() and get_tree().current_scene==self


func select_equipment_side(side: String) -> bool:
	if paused or not _equipment_practice_ready():return false
	if not equipment_practice.select_side(side):return false
	if fixed_trial_running(): _close_fixed_trial(false)
	equipment_notice=""
	_apply_equipment_practice()
	return true


func fixed_trial_running() -> bool:
	return not fixed_trial_kind.is_empty()


func fixed_trial_results(kind: String) -> Dictionary:
	var results: Dictionary = _fixed_trial_results.get(kind,{})
	return results.duplicate(true)


func completed_equipment_trials() -> Dictionary:
	if not _equipment_practice_ready(): return {}
	var completed: Dictionary = {}
	for kind: String in _fixed_trial_results:
		if not _fixed_trial_results[kind].is_empty():
			completed[kind] = fixed_trial_results(kind)
	if completed.is_empty(): return {}
	var loadout: Dictionary = equipment_practice.describe()
	return {
		"candidate_id": loadout["candidate_id"],
		"candidate_name": loadout["candidate_name"],
		"candidate_rank": loadout["candidate_rank"],
		"current_name": loadout["current_name"],
		"current_rank": loadout["current_rank"],
		"level": loadout["level"],
		"separation": FIXED_TRIAL_OFFSET.length(),
		"results": completed,
	}


func start_fixed_trial(kind: String) -> bool:
	if paused or fixed_trial_running() or not _equipment_practice_ready():
		return false
	if kind == "walk":
		_prepare_fixed_trial(kind,"fixed_walk")
		_fixed_trial_attempt["seconds"] = 0.0
		player.position = FIXED_WALK_START
		for fighter in fighters:
			fighter.visible = fighter == player
			fighter.targets.clear()
		return true
	if not FIXED_TRIAL_MOVES.has(kind): return false
	var guard: Node2D = fighters[1]
	var attacker: Node2D = _fixed_attacker(kind)
	var target: Node2D = player if attacker == guard else guard
	var move_id: String = FIXED_TRIAL_MOVES[kind]
	var move: Variant = attacker.moves.get(move_id)
	if not MoveRules.valid(move) or bool(move.get("projectile",false)):
		equipment_notice = "当前重锤配置无法进行试用。" if attacker == guard else "当前破空斩配置无法进行近身试斩。"
		return false

	_prepare_fixed_trial(kind,"fixed_" + kind)

	player.position = guard.position - FIXED_TRIAL_OFFSET
	player.facing = 1.0
	guard.facing = -1.0
	player.targets.clear()
	guard.targets.clear()
	attacker.targets.append(target)
	for fighter in fighters:
		fighter.visible = fighter == player or fighter == guard

	_fixed_trial_attempt.merge({
		"sequence": -1, "target": target, "checked_target": false, "damage": 0,
		"target_hp": target.hp, "remaining_hp": target.hp, "cooldown": 0.0,
	})
	attacker.attack_checked.connect(_on_fixed_attack_checked)
	if kind == "evade":
		if not CombatRules.can_hit(guard,player,move):
			_close_fixed_trial(false)
			super.reset_room()
			equipment_notice = "当前重锤在起始站位无法命中。"
			return false
		_fixed_trial_attempt["attack_depth"] = float(move["depth"])
		_fixed_trial_attempt["min_depth_gap"] = INF
		_fixed_trial_attempt["all_checks_blocked_by_depth"] = true
	if attacker == guard:
		if guard._start_attack(move_id):
			guard.skill_cooldown = float(guard.current_move["cooldown"])
			_remember_fixed_attack(guard)
	else:
		super.request_player_action("skill")
	if int(_fixed_trial_attempt["sequence"]) >= 0: return true
	_close_fixed_trial(false)
	super.reset_room()
	return false


func _fixed_attacker(kind: String) -> Node2D:
	return player if kind == "skill" else fighters[1]


func _prepare_fixed_trial(kind: String, reason: String) -> void:
	var side: String = equipment_practice.selected_side()
	equipment_notice = ""
	equipment_observations.set_ai(active_ai)
	equipment_observations.restart(side,active_ai,reason)
	_fixed_trial_previous_ai = active_ai
	active_ai = false
	skill_start_count = 0
	skill_count_incomplete = false
	super.reset_room()
	fixed_trial_kind = kind
	_fixed_trial_attempt = {"side": side}
	fixed_trial_revision += 1


func _advance_fixed_walk(before: Vector2, delta: float) -> void:
	if delta <= 0.0: return
	var after: Vector2 = player.position
	var step: float = before.y - after.y
	if (player.hp != player.max_hp or player.height != 0.0 or not before.is_finite() or not after.is_finite()
			or not is_equal_approx(after.x,FIXED_WALK_START.x) or before.y <= FIXED_WALK_FINISH.y or step <= 0.0
			or player.ground_motion.last_recovered or not player.ground_motion.last_normals.is_empty()):
		_close_fixed_trial(false)
		equipment_notice = "定距走位受阻或中断，保留上次完成的读数。"
		super.reset_room()
		return
	if after.y > FIXED_WALK_FINISH.y:
		_fixed_trial_attempt["seconds"] += delta
		return
	# Use the part of the final physics tick before the foot crossed the line.
	_fixed_trial_attempt["seconds"] += delta * (before.y - FIXED_WALK_FINISH.y) / step
	_close_fixed_trial(true)
	super.reset_room()


func _remember_fixed_attack(attacker: Node2D) -> void:
	_fixed_trial_attempt["sequence"] = attacker.action_sequence
	_fixed_trial_attempt["cooldown"] = attacker.skill_cooldown
	var move: Dictionary = attacker.current_move
	_fixed_trial_end = float(move["windup"]) + float(move["active"]) + float(move["recovery"])


func _fixed_target_bound(attacker: Node2D) -> bool:
	return attacker.targets.size() == 1 and attacker.targets[0] == _fixed_trial_attempt["target"]


func _on_fixed_attack_checked(source: Node2D, victim: Node2D, sequence: int, move_id: String, blocker: int) -> void:
	if paused or source != _fixed_attacker(fixed_trial_kind) or victim != _fixed_trial_attempt["target"]: return
	if sequence != int(_fixed_trial_attempt["sequence"]) or move_id != FIXED_TRIAL_MOVES[fixed_trial_kind]: return
	if blocker != CombatRules.HitBlocker.TARGET:
		_fixed_trial_attempt["checked_target"] = true
	if fixed_trial_kind != "evade": return
	var gap: float = absf(victim.position.y - source.position.y)
	_fixed_trial_attempt["min_depth_gap"] = minf(float(_fixed_trial_attempt["min_depth_gap"]),gap)
	if blocker != CombatRules.HitBlocker.DEPTH:
		_fixed_trial_attempt["all_checks_blocked_by_depth"] = false


func _close_fixed_trial(completed: bool) -> void:
	if fixed_trial_kind != "walk":
		_fixed_attacker(fixed_trial_kind).attack_checked.disconnect(_on_fixed_attack_checked)
	if completed:
		var side: String = _fixed_trial_attempt["side"]
		var result: Dictionary
		if fixed_trial_kind == "walk":
			result = {
				"seconds": _fixed_trial_attempt["seconds"],
				"distance": FIXED_WALK_START.y - FIXED_WALK_FINISH.y,
			}
		else:
			result = {
				"damage": _fixed_trial_attempt["damage"],
				"target_hp": _fixed_trial_attempt["target_hp"],
			}
			if fixed_trial_kind == "skill":
				result["cooldown"] = _fixed_trial_attempt["cooldown"]
			else:
				result["remaining_hp"] = _fixed_trial_attempt["remaining_hp"]
			if fixed_trial_kind == "evade":
				result["attack_depth"] = _fixed_trial_attempt["attack_depth"]
				result["min_depth_gap"] = _fixed_trial_attempt["min_depth_gap"]
		_fixed_trial_results[fixed_trial_kind][side] = result
	else:
		equipment_notice = "这次固定练习已中断，保留上次完成的读数。"
	fixed_trial_kind = ""
	_fixed_trial_attempt.clear()
	active_ai = _fixed_trial_previous_ai
	for fighter in fighters:
		fighter.visible = true
		fighter.targets = _opponents_for(fighter)
	fixed_trial_revision += 1


func request_player_action(action: String) -> void:
	if not fixed_trial_running(): super.request_player_action(action)


func _player_walk_input() -> Vector2:
	if fixed_trial_kind == "walk": return Vector2.UP
	if fixed_trial_kind == "evade": return Vector2(0,Input.get_axis("move_up","move_down"))
	if fixed_trial_running(): return Vector2.ZERO
	return super._player_walk_input()


func return_equipment_town() -> bool:
	if not _equipment_practice_ready():return false
	return _request_practice_return()


func is_portal_practice() -> bool:
	return practice_context.get("kind") == "portal"


func has_portal_return() -> bool:
	return is_portal_practice() or from_battle_review


func return_to_portal() -> bool:
	if entry_blocked or not has_portal_return(): return false
	return _request_practice_return()


func _request_practice_return() -> bool:
	if Session.local_scene_pending(): return false
	var status: Error = Session.return_from_practice(self,_on_queued_return_failed)
	if status in [ERR_CANT_OPEN,ERR_CANT_CREATE]:
		_on_queued_return_failed(Session.last_message)
	elif status != OK:
		_set_return_note(Session.last_message)
	elif Session.local_scene_pending():
		_set_return_note("正在返回城镇……")
	return status == OK


func _on_queued_return_failed(_message: String) -> bool:
	_set_return_note("回城场景加载失败，可再次返回。")
	return true


func _set_return_note(message: String) -> void:
	if equipment_practice != null:
		equipment_notice = message
	else:
		portal_return_note = message
		ui.queue_redraw()


func toggle_guidance() -> void:
	if entry_blocked or equipment_practice!=null: return
	if paused: return
	if guidance == null:
		guidance = Practice.new()
		_configure_lesson()
	else:
		guidance = null
		_bind_jump_observers(false)
		_bind_sequence_observer(false)
		_bind_projectile_observer(false)
		fighters[1].enemy_kind = "sentinel"
		fighters[1].enraged = false
		if player.attack_checked.is_connected(_on_attack_checked):
			player.attack_checked.disconnect(_on_attack_checked)
		reset_room()
		active_ai = false
		for fighter in fighters:
			fighter.visible = true
			fighter.targets = _opponents_for(fighter)


func guidance_lesson_available(index: int) -> bool:
	if not Practice.valid_lesson(index): return false
	if Practice.is_projectile_lesson(index):
		var shot: Variant = move_data.get(PRACTICE_SHOT_MOVE)
		return MoveRules.valid(shot) and bool(shot.get("projectile",false))
	if not Practice.is_warden_lesson(index): return true
	for id: String in Practice.WARDEN_MOVES:
		var move: Variant = move_data.get(id)
		if not MoveRules.valid(move) or bool(move.get("projectile",false)): return false
	return true


# The picker is a draft: browsing it must not reset actors or a live attempt.
func select_guidance_lesson(index: int) -> bool:
	if entry_blocked or paused or equipment_practice!=null or not guidance_lesson_available(index): return false
	selected_lesson = index
	return true


func cycle_guidance_lesson(direction: int) -> bool:
	if direction not in [-1,1]: return false
	for offset in range(1,Practice.TITLES.size()):
		var index: int = posmod(selected_lesson+offset*direction,Practice.TITLES.size())
		if guidance_lesson_available(index): return select_guidance_lesson(index)
	return false


func start_selected_guidance() -> bool:
	if entry_blocked or paused or equipment_practice!=null or not guidance_lesson_available(selected_lesson): return false
	var next_practice := Practice.new()
	if not next_practice.select_lesson(selected_lesson): return false
	guidance = next_practice
	_configure_lesson()
	return true


func retry_guidance() -> void:
	if entry_blocked: return
	if guidance == null or paused: return
	guidance.retry()
	_configure_lesson()


func next_guidance() -> void:
	if entry_blocked: return
	if guidance == null or paused or not guidance.completed: return
	if guidance.advance(): _configure_lesson()
	else: toggle_guidance()


func _configure_lesson() -> void:
	reset_room()
	_bind_jump_observers(guidance.lesson == Practice.Lesson.JUMP)
	if not player.attack_checked.is_connected(_on_attack_checked):
		player.attack_checked.connect(_on_attack_checked)
	active_ai = false
	_coach = fighters[1]
	_bind_sequence_observer(guidance.uses_attack_sequence())
	_bind_projectile_observer(Practice.is_projectile_lesson(guidance.lesson))
	match guidance.lesson:
		Practice.Lesson.COVER,Practice.Lesson.ARROW_DODGE: _coach.enemy_kind = "archer"
		Practice.Lesson.WARDEN,Practice.Lesson.WARDEN_RAGE: _coach.enemy_kind = "warden"
		_: _coach.enemy_kind = "sentinel"
	_coach.enraged = guidance.lesson == Practice.Lesson.WARDEN_RAGE
	_sequence_recovery_end = 0.0
	_trial_sequence = -1; _trial_active_end = 0.0; _trial_window_completed = false; _trial_depth_escaped = false
	for fighter in fighters:
		fighter.targets.clear()
		fighter.visible = fighter == player
	_coach.position = Vector2(650,550)
	_coach.facing = -1
	player.facing = 1
	match guidance.lesson:
		Practice.Lesson.DEPTH: player.position = Vector2(450,550)
		Practice.Lesson.ATTACK: player.position = Vector2(560,470)
		Practice.Lesson.LAUNCH: player.position = Vector2(560,550)
		Practice.Lesson.DASH: player.position = Vector2(550,550)
		Practice.Lesson.JUMP: player.position = Vector2(580,550)
		Practice.Lesson.EVADE:
			player.position = Vector2(590,550)
			_coach.skill_cooldown = 0.9
		Practice.Lesson.RETREAT:
			player.position = Vector2(190,500)
			_coach.position = Vector2(430,550)
			_coach.visible = true
			_coach.targets.append(player)
		Practice.Lesson.COVER:
			player.position = Vector2(180,558)
			_coach.position = Vector2(440,535)
			_coach.visible = true
		Practice.Lesson.ARROW_DODGE:
			player.position = Practice.ARROW_DODGE_LANE.get_center()
			_coach.position = Vector2(790,player.position.y)
			_coach.visible = true
		Practice.Lesson.WARDEN,Practice.Lesson.WARDEN_RAGE:
			player.position = Vector2(590,550)
			_coach.visible = true
			_coach.targets.append(player)
	if guidance.lesson in [Practice.Lesson.ATTACK,Practice.Lesson.LAUNCH,Practice.Lesson.EVADE]:
		_coach.visible = true
		player.targets.append(_coach)
		_coach.targets.append(player)


func _unhandled_input(event: InputEvent) -> void:
	if entry_blocked: return
	if event.is_echo(): return
	if event.is_action_pressed("return_menu") and has_portal_return():
		return_to_portal()
		return
	if equipment_practice!=null:
		if event.is_action_pressed("equipment_fixed_skill"):
			start_fixed_trial("skill")
			return
		if event.is_action_pressed("equipment_fixed_incoming"):
			start_fixed_trial("incoming")
			return
		if event.is_action_pressed("equipment_fixed_walk"):
			start_fixed_trial("walk")
			return
		if event.is_action_pressed("equipment_fixed_evade"):
			start_fixed_trial("evade")
			return
		if event.is_action_pressed("toggle_ai"):
			if not paused and not fixed_trial_running() and _equipment_practice_ready():
				active_ai=not active_ai
				equipment_observations.set_ai(active_ai)
			return
		if event.is_action_pressed("equipment_current"):select_equipment_side("current");return
		if event.is_action_pressed("equipment_candidate"):select_equipment_side("candidate");return
		if event.is_action_pressed("equipment_town"):return_equipment_town();return
		for action in ["guidance_toggle","guidance_previous","guidance_next","guidance_start"]:
			if event.is_action_pressed(action):return
	if event.is_action_pressed("guidance_toggle"): toggle_guidance(); return
	if event.is_action_pressed("guidance_previous"): cycle_guidance_lesson(-1); return
	if event.is_action_pressed("guidance_next"): cycle_guidance_lesson(1); return
	if event.is_action_pressed("guidance_start"): start_selected_guidance(); return
	if guidance != null and not paused:
		if event.is_action_pressed("reset_room"): retry_guidance(); return
		if event.is_action_pressed("interact"): next_guidance(); return
		if event.is_action_pressed("toggle_ai"): return
	super._unhandled_input(event)


func _bind_jump_observers(enabled: bool) -> void:
	for pair: Array in [[player.jump_started,_on_jump_started],[player.ground_landed,_on_ground_landed],[player.received_hit,_on_jump_hurt]]:
		var event: Signal = pair[0]
		var callback: Callable = pair[1]
		if enabled and not event.is_connected(callback): event.connect(callback)
		elif not enabled and event.is_connected(callback): event.disconnect(callback)

func _bind_projectile_observer(enabled: bool) -> void:
	var shots: Signal = fighters[1].projectile_fired
	if enabled and not shots.is_connected(_on_practice_projectile): shots.connect(_on_practice_projectile)
	elif not enabled and shots.is_connected(_on_practice_projectile): shots.disconnect(_on_practice_projectile)


func _bind_sequence_observer(enabled: bool) -> void:
	var checks: Signal = fighters[1].attack_checked
	if enabled and not checks.is_connected(_on_sequence_check): checks.connect(_on_sequence_check)
	elif not enabled and checks.is_connected(_on_sequence_check): checks.disconnect(_on_sequence_check)


func _on_sequence_check(source: Node2D, victim: Node2D, sequence: int, move_id: String, blocker: int) -> void:
	if entry_blocked or paused or guidance == null or source != _coach or victim != player: return
	if source.action_sequence != sequence or source.attack_name != move_id: return
	# A real invulnerable dash can also deny contact, but a dead target cannot.
	var clear: bool = blocker != CombatRules.HitBlocker.NONE and victim.hp > 0
	if blocker == CombatRules.HitBlocker.TARGET:
		clear = clear and victim.invulnerable > 0.0
	if Practice.is_warden_lesson(guidance.lesson):
		var expected: bool = blocker == CombatRules.HitBlocker.HEIGHT if move_id == Practice.WARDEN_MOVES[0] else (blocker == CombatRules.HitBlocker.DEPTH and victim.height <= 1.0)
		clear = victim.hp > 0 and expected
	guidance.observe_sequence_check(sequence,move_id,clear)


func _jump_observer_ready(source: Node2D) -> bool:
	return not entry_blocked and not paused and guidance != null and guidance.lesson == Practice.Lesson.JUMP and source == player and is_inside_tree() and not is_queued_for_deletion() and get_tree().current_scene == self

func _on_jump_started(source: Node2D, sequence: int) -> void:
	if not _jump_observer_ready(source): return
	if sequence != source.action_sequence or source.hp <= 0 or source.stun_time > 0 or source.height <= 0 or source.vertical_speed <= 0: return
	guidance.begin_jump(sequence,source.position)

func _on_ground_landed(source: Node2D) -> void:
	if not _jump_observer_ready(source): return
	if source.hp > 0 and source.height == 0.0 and source.vertical_speed == 0.0:
		guidance.finish_jump(source.position)

func _on_jump_hurt(source: Node2D, damage: int) -> void:
	if _jump_observer_ready(source) and damage > 0: guidance.hurt()

func _on_attack_checked(source: Node2D, victim: Node2D, sequence: int, move_id: String, blocker: int) -> void:
	if entry_blocked or paused or guidance == null or source != player or victim != _coach: return
	if source.action_sequence != sequence or source.attack_name != move_id: return
	var reason: String = ""
	match blocker:
		CombatRules.HitBlocker.NONE: reason = "in-range"
		CombatRules.HitBlocker.TARGET: reason = "target"
		CombatRules.HitBlocker.FACING: reason = "facing"
		CombatRules.HitBlocker.REACH: reason = "reach"
		CombatRules.HitBlocker.DEPTH: reason = "depth"
		CombatRules.HitBlocker.HEIGHT: reason = "height"
		_: return
	guidance.observe_attack(sequence,move_id,reason)


func _on_hit(source: Node2D, victim: Node2D, damage: int) -> void:
	if entry_blocked: return
	super._on_hit(source,victim,damage)
	if fixed_trial_running():
		if fixed_trial_kind == "walk": return
		var expected_source: Node2D = _fixed_attacker(fixed_trial_kind)
		if not paused and source == expected_source and victim == _fixed_trial_attempt["target"] and damage > 0:
			if source.action_sequence == int(_fixed_trial_attempt["sequence"]) and source.attack_name == FIXED_TRIAL_MOVES[fixed_trial_kind]:
				_fixed_trial_attempt["damage"] = damage
				_fixed_trial_attempt["remaining_hp"] = victim.hp
		return
	if not paused and _equipment_practice_ready() and source==player and victim in fighters and victim!=player:
		equipment_observations.set_ai(active_ai)
		equipment_observations.dealt(damage,victim.hp==0)
	if guidance == null: return
	if source == player and victim == _coach:
		guidance.landed(str(source.attack_name),damage,victim.vertical_speed > 0 and victim.hp > 0)
	elif source == _coach and victim == player:
		guidance.hurt()


func _update_enemy(enemy: Node2D) -> void:
	if guidance == null:
		super._update_enemy(enemy); return
	enemy.walk_input = Vector2.ZERO
	if enemy != _coach or guidance.completed or guidance.failed or guidance.trial_active: return
	if Practice.is_projectile_lesson(guidance.lesson):
		_start_practice_shot()
		return
	if guidance.uses_attack_sequence():
		_start_sequence_attack()
		return
	if guidance.lesson != Practice.Lesson.EVADE: return
	if enemy.hp <= 0 or enemy.stun_time > 0 or not enemy.attack_name.is_empty() or enemy.skill_cooldown > 0 or player.height > 1 or not MoveRules.valid(move_data.get("dummy_strike")): return
	if not preload("res://game/combat/combat_rules.gd").can_hit(enemy,player,move_data["dummy_strike"]): return
	if enemy._start_attack("dummy_strike"):
		enemy.skill_cooldown = float(move_data["dummy_strike"]["cooldown"])
		_trial_sequence = enemy.action_sequence
		_trial_active_end = float(move_data["dummy_strike"]["windup"])+float(move_data["dummy_strike"]["active"])
		_trial_window_completed = false; _trial_depth_escaped = false
		guidance.begin_trial(_trial_sequence)


func _start_practice_shot() -> void:
	if not _coach.attack_name.is_empty(): return
	var move: Variant = _coach.moves.get(PRACTICE_SHOT_MOVE)
	if not MoveRules.valid(move) or not bool(move.get("projectile",false)) or not _coach._start_attack(PRACTICE_SHOT_MOVE):
		guidance.cancel_projectile_trial()
		return
	_trial_sequence = _coach.action_sequence
	if not guidance.begin_projectile_trial(_trial_sequence):
		guidance.cancel_projectile_trial()


func _on_practice_projectile(source: Node2D,move: Dictionary) -> void:
	if paused or guidance == null or not Practice.is_projectile_lesson(guidance.lesson) or source != _coach or _practice_arrow != null: return
	if source.action_sequence != _trial_sequence or source.attack_name != PRACTICE_SHOT_MOVE: return
	var cover: bool = guidance.lesson == Practice.Lesson.COVER
	if not cover and (player.height > 1.0 or player.hp <= 0 or not Practice.ARROW_DODGE_LANE.has_point(player.position)):
		guidance.cancel_projectile_trial("先留在箭路上，等箭离弦再走。可重试本项。")
		return
	if not guidance.observe_projectile_launch(_trial_sequence): return
	var arrow := Arrow.new()
	var aim: Vector2 = player.position if cover else Vector2(player.position.x,source.position.y)
	arrow.aim(source,aim,move,obstacles)
	arrow.target = player
	arrow.impacted.connect(_on_hit)
	_practice_arrow = arrow
	_projectile_qualified = not cover or _player_in_cover()
	world.add_child(arrow)
	_play_sound("shot")


func _player_in_cover() -> bool:
	return player.hp > 0 and player.height <= 1.0 and Practice.COVER_MARKER.has_point(player.position)


func _release_practice_arrow() -> void:
	_projectile_qualified = false
	if not is_instance_valid(_practice_arrow): return
	var arrow: Node2D = _practice_arrow
	_practice_arrow = null
	world.remove_child(arrow)
	arrow.queue_free()


func _advance_practice_shot(delta: float,previous_arrow: Node2D) -> void:
	if not is_instance_valid(_practice_arrow):
		if guidance.trial_active and (_coach.action_sequence != _trial_sequence or _coach.attack_name != PRACTICE_SHOT_MOVE):
			guidance.finish_projectile_trial(_trial_sequence,false)
		return
	var cover: bool = guidance.lesson == Practice.Lesson.COVER
	if cover: _projectile_qualified = _projectile_qualified and _player_in_cover()
	_practice_arrow.advance(delta,_practice_arrow != previous_arrow)
	var passed_lane := false
	if not cover and _practice_arrow.position.x <= Practice.ARROW_DODGE_LANE.end.x+DODGE_CLEARANCE:
		var point: Vector2 = player.position
		var cleared: bool = Practice.ARROW_DODGE_ZONES[0].has_point(point) or Practice.ARROW_DODGE_ZONES[1].has_point(point)
		_projectile_qualified = _projectile_qualified and cleared and player.height <= 1.0 and player.hp > 0
		passed_lane = _practice_arrow.position.x <= Practice.ARROW_DODGE_LANE.position.x-DODGE_CLEARANCE
	# The swept shot can clear the lane and reach the arena edge in the same step.
	if passed_lane or _practice_arrow.expired:
		var reached_goal: bool = _practice_arrow.blocking_obstacle == COVER_OBSTACLE if cover else passed_lane
		guidance.finish_projectile_trial(_trial_sequence,_projectile_qualified and reached_goal)
		_release_practice_arrow()


func _start_sequence_attack() -> void:
	var move_id: String = guidance.sequence_move_id()
	if move_id.is_empty() or not _coach.attack_name.is_empty() or _coach.skill_cooldown > 0.0: return
	var move: Variant = _coach.moves.get(move_id)
	if not MoveRules.valid(move) or bool(move.get("projectile",false)):
		guidance.cancel_attack_sequence()
		return
	var started: bool = _coach.start_warden_attack(move_id) if Practice.is_warden_lesson(guidance.lesson) else _coach._start_attack(move_id)
	if not started:
		guidance.cancel_attack_sequence()
		return
	_trial_sequence = _coach.action_sequence
	var current: Dictionary = _coach.current_move
	_sequence_recovery_end = float(current["windup"]) + float(current["active"]) + float(current["recovery"])
	if not guidance.begin_sequence_attack(_trial_sequence,move_id):
		guidance.cancel_attack_sequence()


func _advance_sequence_attack() -> void:
	var target_bound: bool = _coach.targets.size() == 1 and _coach.targets[0] == player
	var same_attack: bool = _coach.action_sequence == _trial_sequence and _coach.hp > 0
	if target_bound and same_attack and _coach.attack_name == guidance.sequence_move_id(): return
	var recovered: bool = target_bound and same_attack and _coach.attack_name.is_empty() and _coach.attack_time >= _sequence_recovery_end
	guidance.finish_sequence_attack(_trial_sequence,recovered)


func _physics_process(delta: float) -> void:
	if entry_blocked: return
	var frozen: bool = paused or hit_stop > 0
	if not frozen and fixed_trial_running() and fixed_trial_kind != "walk":
		if not _fixed_target_bound(_fixed_attacker(fixed_trial_kind)):
			_close_fixed_trial(false)
			super.reset_room()
			return
	var previous_arrow: Node2D = _practice_arrow
	var before: Vector2 = player.position if fixed_trial_kind == "walk" else Vector2.ZERO
	super._physics_process(delta)
	if fixed_trial_running():
		if frozen or paused: return
		if fixed_trial_kind == "walk":
			_advance_fixed_walk(before,delta)
			return
		var attacker: Node2D = _fixed_attacker(fixed_trial_kind)
		var same_action: bool = attacker.action_sequence == int(_fixed_trial_attempt["sequence"])
		var target_kept: bool = _fixed_target_bound(attacker)
		if same_action and target_kept and attacker.attack_name == FIXED_TRIAL_MOVES[fixed_trial_kind]: return
		# Normal recovery clears the name but keeps the elapsed move time.
		var completed: bool = (same_action and target_kept and _fixed_trial_attempt["checked_target"]
			and attacker.attack_name.is_empty() and attacker.attack_time >= _fixed_trial_end and attacker.hp > 0)
		if completed and fixed_trial_kind == "evade":
			completed = int(_fixed_trial_attempt["damage"]) > 0 or bool(_fixed_trial_attempt["all_checks_blocked_by_depth"])
		_close_fixed_trial(completed)
		super.reset_room()
		return
	if guidance == null or frozen or paused: return
	guidance.observe_motion(player.position,player.height,player.dash_time > 0)
	if Practice.is_projectile_lesson(guidance.lesson):
		_advance_practice_shot(delta,previous_arrow)
	elif guidance.uses_attack_sequence() and guidance.trial_active:
		_advance_sequence_attack()
	elif guidance.lesson == Practice.Lesson.EVADE and guidance.trial_active:
		if not _trial_window_completed and _coach.attack_time >= _trial_active_end:
			_trial_window_completed = true
			_trial_depth_escaped = absf(player.position.y-_coach.position.y) > float(move_data["dummy_strike"]["depth"]) and player.hp > 0
		if _coach.attack_name.is_empty() or _coach.action_sequence != _trial_sequence:
			guidance.finish_trial(_trial_sequence,_trial_window_completed,_trial_depth_escaped)


func _draw() -> void:
	if entry_blocked: return
	super._draw()
	_draw_attack_telegraphs()
	if fixed_trial_kind == "walk":
		draw_set_transform(world.position)
		var color := Color("8ed9dc")
		draw_dashed_line(FIXED_WALK_START,FIXED_WALK_FINISH,color,2,8)
		draw_line(FIXED_WALK_START-Vector2(24,0),FIXED_WALK_START+Vector2(24,0),color,2)
		draw_line(FIXED_WALK_FINISH-Vector2(24,0),FIXED_WALK_FINISH+Vector2(24,0),color,2)
		draw_string(ui_font,FIXED_WALK_START+Vector2(30,5),"起点",HORIZONTAL_ALIGNMENT_LEFT,-1,15,color)
		draw_string(ui_font,FIXED_WALK_FINISH+Vector2(30,5),"终点",HORIZONTAL_ALIGNMENT_LEFT,-1,15,color)
		draw_set_transform(Vector2.ZERO)
	if guidance == null: return
	draw_set_transform(world.position)
	var marker: Rect2 = guidance.marker()
	if marker.has_area():
		draw_rect(marker,Color(0.8,0.66,0.35,0.14)); draw_rect(marker,Color("d6bc83"),false,2)
		var marker_text: String = "地面目标区域"
		if guidance.lesson == Practice.Lesson.JUMP: marker_text = "跳跃落地框"
		elif guidance.lesson == Practice.Lesson.COVER: marker_text = "柱后掩护"
		elif guidance.lesson == Practice.Lesson.RETREAT:
			marker_text = "撤离框" if guidance.attack_stage == Practice.AttackStage.EXIT else "引出挥击"
		draw_string(ui_font,marker.position-Vector2(0,9),marker_text,HORIZONTAL_ALIGNMENT_LEFT,-1,15,Color("e2cf9d"))
	elif guidance.lesson == Practice.Lesson.ARROW_DODGE and not guidance.completed and not guidance.failed:
		for zone: Rect2 in Practice.ARROW_DODGE_ZONES:
			draw_rect(zone,Color(0.8,0.66,0.35,0.14))
			draw_rect(zone,Color("d6bc83"),false,2)
		var lane: Rect2 = Practice.ARROW_DODGE_LANE
		draw_dashed_line(Vector2(lane.position.x,lane.get_center().y),_coach.position,Color("d7a281"),2,7)
	elif guidance.lesson in [Practice.Lesson.ATTACK,Practice.Lesson.LAUNCH]:
		draw_line(Vector2(480,550),Vector2(780,550),Color("d6bc83"),2)
		draw_string(ui_font,Vector2(480,520),"靠近守卫脚点这一线",HORIZONTAL_ALIGNMENT_LEFT,-1,15,Color("e2cf9d"))
	if guidance.lesson == Practice.Lesson.JUMP:
		var foot: Vector2 = player.position
		var air_foot: Vector2 = foot-Vector2(0,player.height)
		var guide_color := Color("8ed9dc")
		draw_arc(foot,16,0,TAU,24,guide_color,2,true)
		draw_line(foot-Vector2(6,0),foot+Vector2(6,0),guide_color,2)
		draw_line(foot-Vector2(0,6),foot+Vector2(0,6),guide_color,2)
		if player.height > 0.5: draw_dashed_line(foot,air_foot,guide_color,2,5)
		draw_string(ui_font,Vector2(clampf(foot.x+20,80,1120),clampf(foot.y+24,370,630)),"脚点／影子",HORIZONTAL_ALIGNMENT_LEFT,-1,14,guide_color)
	draw_set_transform(Vector2.ZERO)



func _verify_step() -> void:
	verify_frame += 1
	match verify_frame:
		2:
			reset_room()
			player.position = Vector2(620,540)
			player.facing = 1
			Input.action_press("attack")
			_unhandled_input(_action_event("attack"))
		5: Input.action_release("attack")
		30:
			verify_results["attack_hit"] = fighters[1].hp < fighters[1].max_hp
			player.request_action("skill")
		42:
			verify_results["launch_height"] = fighters[1].height > 0
		70:
			player.request_action("jump")
		80:
			verify_results["jump_height"] = player.height > 0
		100:
			reset_room()
			Input.action_press("move_up")
		120:
			Input.action_release("move_up")
			verify_results["depth_movement"] = player.position.y < player.spawn_point.y-25
			reset_room()
			player.position = Vector2(252,535)
			Input.action_press("move_up")
		145:
			Input.action_release("move_up")
			verify_results["solid_pillar"] = player.position.y >= 532.0
			reset_room()
			player.position = Vector2(680,540)
			active_ai = true
		190:
			verify_results["enemy_attack"] = player.hp < player.max_hp
			active_ai = false
			reset_room()
			player.position = Vector2(605,540)
			player.request_action("skill")
		203:
			show_debug = true
			for fighter in fighters:
				fighter.debug_boxes = true
				fighter.queue_redraw()
			paused = true
			_save_runtime_evidence()


func _save_runtime_evidence() -> void:
	var output_path := OS.get_environment("DUNGEON_VERIFY_DIR")
	if output_path.is_empty():
		output_path = ProjectSettings.globalize_path("res://docs/verification/runtime-local")
	DirAccess.make_dir_recursive_absolute(output_path)
	if DisplayServer.get_name() != "headless":
		await _capture(output_path.path_join("training-room.png"))
		show_debug = false
		reset_room()
		for fighter in fighters:
			fighter.debug_boxes = false
		player.position = Vector2(270,468)
		player.queue_redraw()
		ui.queue_redraw()
		await _capture(output_path.path_join("depth-behind.png"))
		player.position = Vector2(270,548)
		player.queue_redraw()
		await _capture(output_path.path_join("depth-front.png"))
	var all_passed := true
	for result in verify_results.values():
		all_passed = all_passed and bool(result)
	var report := {"version":ProjectSettings.get_setting("application/config/version"),"engine":Engine.get_version_info()["string"],"checks":verify_results,"passed":all_passed,"display_server":DisplayServer.get_name()}
	var file := FileAccess.open(output_path.path_join("runtime.json"),FileAccess.WRITE)
	file.store_string(JSON.stringify(report,"  "))
	file.close()
	print("RUNTIME_VERIFICATION ",JSON.stringify(report))
	Session.request_quit(0 if all_passed else 1)


