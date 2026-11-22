extends "res://game/main/combat_room.gd"

const Progress = preload("res://game/levels/run_progress.gd")
const Exit = preload("res://game/levels/dungeon_exit.gd")
const Arrow = preload("res://game/combat/arrow.gd")
const Review = preload("res://game/combat/battle_review.gd")
const ForestArt = preload("res://game/levels/forest_room_art.gd")
const CampActorArt = preload("res://game/presentation/camp_actor_art.gd")
const ForestDetails = preload("res://game/presentation/forest_battle_details.gd")
@export var camp_actor_art: CampActorArt
var _forest_details := ForestDetails.new()
var _forest_art: ForestArt
var _waystones: Array[Sprite2D] = []
var run = Progress.new()
var battle_review: RefCounted
var roster: Dictionary = {}
var dungeon_data: Dictionary = {}
var enemies: Array[Node2D] = []
var arrows: Array[Node2D] = []
var loot: Array[Dictionary] = []
var enemy_profiles: Dictionary = {}
var intro_time: float = 2.3
var boss: Node2D
var boss_attack_count: int = 0
var notice_text: String = ""
var notification_time: float = 0.0
var verification_started: bool = false
var run_id: String = ""
var withdrew: bool = false
var rewards_saved: bool = false
var settlement_saving: bool = false
var settlement_recorded: bool = false
enum SettlementExit { NONE, TOWN, RESTART }
var _settlement_exit: SettlementExit = SettlementExit.NONE
var settlement_note: String = ""
var settlement_receipt: Dictionary = {}
var difficulty: Dictionary = {}


func _requires_progression() -> bool: return true


func _populate() -> void:
	run_id = Session.begin_run()
	if run_id.is_empty():
		_defer_entry_return(Session.TOWN_SCENE)
		return
	Session.solo_save_finished.connect(_solo_save_finished)
	var plan: Dictionary = Session.active_plan()
	roster = plan["roster"]
	dungeon_data = plan["dungeon"]
	difficulty = plan["preview"]
	move_data = plan["moves"]
	battle_review = Review.new(roster,move_data)
	run.room_count = dungeon_data["rooms"].size()
	player = _spawn(Vector2(160,550),true)
	var stats: Dictionary = Session.run_stats
	player.max_hp = int(stats["hp"])
	player.hp = player.max_hp
	player.damage_multiplier = float(stats["damage"])
	player.move_speed_multiplier = float(stats["speed"])
	player.incoming_multiplier = float(stats["incoming"])
	player.skill_cooldown_multiplier = float(stats["cooldown"])
	if Session.profile["equipped"]["weapon"] == "echo_blade": player.blade_color = Color("89e6df")
	player.auto_respawn = false
	player.hurt_invulnerability = 0.65
	player.received_hit.connect(_player_hurt)
	active_ai = true
	_load_room()


# Wait until the failed scene has finished entering the tree. Its return request
# belongs to this instance and to the navigation intent that installed it.
func _defer_entry_return(path: String,party: Node=null) -> void:
	entry_blocked = true
	set_physics_process(false)
	var session := _entry_session()
	var navigation: Node = party if party != null else session._party_node
	var generation: int = navigation._route_generation if is_instance_valid(navigation) else 0
	_return_from_failed_entry.call_deferred(path,session.scene_epoch(),generation,party)


func _return_from_failed_entry(path: String,epoch: int,generation: int,party: Node) -> void:
	if not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene != self: return
	var session := _entry_session()
	if session.scene_epoch() != epoch or session.local_scene_pending(): return
	var navigation: Node = party if is_instance_valid(party) else session._party_node
	if is_instance_valid(navigation):
		if navigation._route_generation != generation or navigation.has_current_navigation() or navigation.navigation_failed(): return
	if path == session.TOWN_SCENE:
		if session.change_local_scene(path,_entry_return_failed) != OK:
			_entry_return_failed(str(session.last_message))
	elif is_instance_valid(party):
		party._route(path)


func _entry_return_failed(message: String) -> bool:
	if not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene != self: return false
	var detail := message if not message.is_empty() else "暂时无法返回城镇，可返回主菜单。"
	Entry.block(self,_entry_session(),detail,false)
	return true


func _hud_script() -> String:
	return "res://game/ui/dungeon_hud.gd"


func _move_definitions() -> Dictionary:
	return Session.rules.dungeons.moves_snapshot()

func _move_failure_message() -> String:
	var detail: String = _entry_session().rules.dungeons.error_message()
	return detail+"请修复游戏文件后重新启动，或返回主菜单。" if not detail.is_empty() else super._move_failure_message()


func _make_backdrop() -> Node2D:
	texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
	_forest_art = ForestArt.new()
	return _forest_art


func _make_obstacle_view() -> Node2D:
	var stone := ForestArt.make_waystone()
	_waystones.append(stone)
	return stone


func _draw_masonry_details() -> void:
	pass


func exit_threshold() -> Vector2:
	return ForestArt.exit_threshold(run.room_index)


func _spawn(point: Vector2, controlled: bool) -> Node2D:
	var fighter := super._spawn(point, controlled)
	if controlled:
		fighter.ground_landed.connect(_on_forest_landing)
	else:
		fighter.melee_contact_started.connect(_on_forest_contact)
	return fighter


func _on_forest_landing(fighter: Node2D) -> void:
	if fighter.hp > 0:
		_forest_details.add_dust(fighter.position, false)


func _on_forest_contact(fighter: Node2D, move_name: String) -> void:
	if move_name == "bulwark_crush":
		_forest_hammer_contact(fighter, fighter.position)


func _forest_hammer_contact(fighter: Node2D, ground: Vector2) -> void:
	var reach: float = fighter.current_move["reach"]
	var field: Rect2 = fighter.arena
	var contact_x := clampf(ground.x + fighter.facing * reach * 0.70, field.position.x, field.end.x)
	_forest_details.add_dust(Vector2(contact_x, ground.y), true)
	_play_sound("ground_hammer")


func _update_room_art() -> void:
	if run.room_index > ForestArt.CAMP_ROOM:
		camp_actor_art = null
	_forest_details.clear()
	if is_instance_valid(_forest_art):
		_forest_art.show_room(run.room_index)
		for slot in range(_waystones.size()):
			ForestArt.style_waystone(_waystones[slot], run.room_index, slot)


func _style_camp_actor(fighter: Node2D) -> void:
	if run.room_index != ForestArt.CAMP_ROOM:
		CampActorArt.clear_style(fighter)
		return
	if camp_actor_art == null:
		camp_actor_art = CampActorArt.new()
	camp_actor_art.apply(fighter, ForestArt.contact_shadow_texture(), _landing_clip_interrupted)


func _landing_clip_interrupted() -> bool:
	return run_finished()


func _load_room() -> void:
	_build_room(true)


func _build_room(simulate_combat: bool) -> void:
	_update_room_art()
	_stop_sound()
	for enemy in enemies:
		fighters.erase(enemy)
		world.remove_child(enemy)
		enemy.queue_free()
	enemies.clear()
	for arrow in arrows:
		world.remove_child(arrow)
		arrow.queue_free()
	arrows.clear()
	loot.clear()
	enemy_profiles.clear()
	boss = null
	boss_attack_count = 0
	_place_room_heroes()
	intro_time = 2.3
	notice_text = ""
	notification_time = 0.0
	for entry: Dictionary in dungeon_data["rooms"][run.room_index]["enemies"]:
		var enemy: Node2D = _spawn(Vector2(entry["x"],entry["y"]),false)
		var kind: String = entry["kind"]
		var profile: Dictionary = roster[kind]
		enemy.enemy_kind = kind
		enemy.max_hp = int(profile["hp"])
		enemy.hp = enemy.max_hp
		enemy.damage_multiplier = float(profile["damage_multiplier"])
		enemy.auto_respawn = false
		enemy.incoming_multiplier = float(profile["incoming"])
		enemy.launch_resistance = float(profile["launch_resistance"])
		enemy.move_speed_multiplier = float(profile["speed"])
		if simulate_combat:
			enemy.projectile_fired.connect(_fire_arrow)
			enemy_profiles[enemy.get_instance_id()] = profile
		enemies.append(enemy)
		if kind == "warden":
			boss = enemy
	for fighter in fighters:
		if simulate_combat:
			fighter.targets = _opponents_for(fighter)
		_style_camp_actor(fighter)
	effects.clear()
	numbers.clear()
	hit_stop = 0
	shake = 0
	world.position = Vector2.ZERO


func _place_room_heroes() -> void:
	player.enter_room(Vector2(160,550))


func run_finished() -> bool:
	return withdrew or run.state in [Progress.State.DEFEAT,Progress.State.VICTORY]


func _can_resume_combat() -> bool:
	return super._can_resume_combat() and not run_finished()


func request_player_action(action: String) -> void:
	if not run_finished(): super.request_player_action(action)


func _unhandled_input(event: InputEvent) -> void:
	if entry_blocked: return
	if event.is_echo(): return
	if event.is_action_pressed("toggle_shake"):
		_toggle_shake();return
	if not is_instance_valid(player): return
	if event.is_action_pressed("quests"):
		ui.quest_tracker.toggle_collapsed()
		return
	if event.is_action_pressed("return_menu"):
		if settlement_recorded:
			defer_recorded_settlement()
		else:
			_request_settlement_exit(SettlementExit.TOWN)
		return
	if event.is_action_pressed("reset_room"):
		_restart_run()
		return
	if event.is_action_pressed("confirm_result"):
		if run.state == Progress.State.DEFEAT:
			_restart_run()
		elif run_finished():
			_request_settlement_exit(SettlementExit.TOWN)
		return
	if run_finished():
		if event.is_action_pressed("guidance_start"):
			open_review_practice()
		elif event.is_action_pressed("interact"):
			retry_settlement()
		return
	if event.is_action_pressed("interact"):
		if not paused:
			try_exit()
		return
	if event.is_action_pressed("toggle_ai"):
		return
	super._unhandled_input(event)


func _physics_process(delta: float) -> void:
	if entry_blocked: return
	if not paused:
		_forest_details.step(delta)
	if run_finished():
		if verify_mode and not withdrew:
			_verify_step()
		ui.queue_redraw()
		queue_redraw()
		return
	var frozen: bool = paused or hit_stop > 0
	var existing_shots := arrows.size()
	super._physics_process(delta)
	if frozen or paused:
		return
	run.elapsed += delta
	intro_time = maxf(0,intro_time-delta)
	notification_time = maxf(0,notification_time-delta)
	_advance_arrows(delta,existing_shots)
	for enemy in enemies:
		if enemy.hp <= 0 and run.record_kill(enemy.get_instance_id()):
			var profile: Dictionary = enemy_profiles[enemy.get_instance_id()]
			_place_drop(enemy.get_instance_id(),enemy.position,int(profile["gold"]),0)
	for index in range(loot.size()-1,-1,-1):
		var drop: Dictionary = loot[index]
		if _try_pickup(drop,player):
			loot.remove_at(index)
	# Defeat takes precedence over a simultaneous final enemy kill.
	if player.hp <= 0:
		run.fail()
		_settle_once()
		return
	var before: int = run.state
	run.sync_enemies(living_enemies())
	if before != run.state:
		if run.state == Progress.State.CLEARED:
			_place_drop(-1-run.room_index,Vector2(1050,560),0,int(dungeon_data["room_heal"]))
			notice_text = "房间已清空 · 右侧出口已开启"
			notification_time = 3.0
		elif run.state == Progress.State.VICTORY:
			_sweep_gold()
			_settle_once()


func _restart_run() -> void:
	_request_settlement_exit(SettlementExit.RESTART)


func _request_settlement_exit(action: SettlementExit) -> void:
	if _settlement_exit != SettlementExit.NONE: return
	_settlement_exit = action
	# An already saved result can replace any queued training handoff now.
	if rewards_saved: _continue_after_settlement()
	else: _settle_once()


func _continue_after_settlement() -> void:
	if not is_inside_tree() or get_tree().current_scene != self or not rewards_saved: return
	var action: SettlementExit = _settlement_exit
	_settlement_exit = SettlementExit.NONE
	if action == SettlementExit.NONE: return
	ui.show_navigation_notice("")
	var to_town: bool = action == SettlementExit.TOWN
	var destination := "回城" if to_town else "重开"
	var path: String = Session.TOWN_SCENE if to_town else scene_file_path
	var status: Error = Session.change_local_scene(path,_settled_exit_load_failed.bind(destination))
	if status == ERR_CANT_OPEN:
		_settled_exit_load_failed(Session.last_message,destination)
	elif status != OK:
		var reason: String = Session.configuration_error() if status == ERR_UNAVAILABLE else ""
		ui.show_navigation_notice("%s未完成，请稍后重试。%s" % [destination,reason])


func _settled_exit_load_failed(_message: String,destination: String) -> bool:
	if not rewards_saved or not is_instance_valid(ui): return false
	ui.show_navigation_notice("%s场景加载失败。本局结算已保存，可在这里重试。" % destination)
	return true


func review_practice_lesson() -> int:
	if entry_blocked or run.state != Progress.State.DEFEAT or battle_review == null: return -1
	return battle_review.practice_lesson()


func open_review_practice() -> bool:
	if review_practice_lesson() < 0: return false
	ui.show_navigation_notice("")
	var status: Error = Session.start_review_practice(self,_settled_exit_load_failed.bind("练习"))
	if status in [ERR_CANT_OPEN,ERR_CANT_CREATE]:
		_settled_exit_load_failed(Session.last_message,"练习")
	elif status != OK:
		ui.show_navigation_notice(Session.last_message if not Session.last_message.is_empty() else "暂时无法进入练习，请稍后重试。")
	return status == OK


func retry_settlement() -> void:
	if not run_finished() or settlement_saving or rewards_saved or run_id.is_empty() or run_id != Session.active_run_id: return
	_settle_once()


func defer_recorded_settlement() -> void:
	if not settlement_recorded or settlement_saving or rewards_saved or run_id != Session.active_run_id: return
	var status: Error = Session.defer_solo_settlement(self)
	if status != OK:
		# A busy handoff did not recheck the journal. Only retain a claim already on screen.
		var keep_recorded_hint: bool = ui.defer_button.visible and status != ERR_INVALID_DATA and status != ERR_UNAVAILABLE
		ui.present_settlement({},Session.last_message,keep_recorded_hint,false)


func settle_retreat_for_quit() -> void:
	if entry_blocked or run_id.is_empty() or run_id != Session.active_run_id or settlement_saving or rewards_saved: return
	if run_finished() and not settlement_note.is_empty(): return
	_settle_once()


func _settle_once() -> bool:
	if rewards_saved: return true
	if settlement_saving: return false
	if not run_finished():
		# An early departure ends combat even when its first save fails.
		withdrew = true
		_stop_sound()
	player.clear_buffered_actions()
	player.walk_input = Vector2.ZERO
	var result: Dictionary = Session.bank_run(run_id,run.kills,run.coins,run.state == Progress.State.VICTORY,true)
	_show_settlement(result)
	return rewards_saved


func _solo_save_finished(identifier: String,result: Dictionary) -> void:
	if identifier == run_id and settlement_saving: _show_settlement(result)


func _show_settlement(result: Dictionary) -> void:
	_sync_resume_button()
	settlement_saving = bool(result.get("pending",false))
	settlement_recorded = bool(result.get("retry_recorded",false))
	rewards_saved = result["ok"]
	settlement_receipt = result.get("receipt",{})
	settlement_note = Session.last_receipt if rewards_saved else str(result["message"])
	if not rewards_saved and not settlement_saving:
		_settlement_exit = SettlementExit.NONE
	var message: String = str(result.get("save_warning","")) if rewards_saved else str(result["message"])
	ui.present_settlement(settlement_receipt,message,settlement_recorded,settlement_saving)
	ui.queue_redraw()
	if rewards_saved and _settlement_exit != SettlementExit.NONE: _continue_after_settlement.call_deferred()


func living_enemies() -> int:
	var living: int = 0
	for enemy in enemies:
		if enemy.hp > 0:
			living += 1
	return living


func near_exit() -> bool:
	return Exit.status(player.position,player.height,player.hp) == Exit.Status.READY


func try_exit() -> bool:
	if withdrew or not near_exit() or run.state != Progress.State.CLEARED:
		return false
	_sweep_gold()
	if not run.try_advance(true):
		return false
	_load_room()
	return true


func _try_pickup(drop: Dictionary,collector: Node2D) -> bool:
	if collector.height >= 12 or collector.position.distance_squared_to(drop["point"]) >= 37.0*37.0: return false
	return _collect_drop(drop,collector)


func _collect_drop(drop: Dictionary,collector: Node2D=null) -> bool:
	if collector == null: collector = player
	if collector.hp <= 0: return false
	var gold: int = int(drop["gold"])
	var heal: int = int(drop["heal"])
	var restored: int = mini(heal,collector.max_hp-collector.hp)
	if gold > 0:
		run.collect_loot(int(drop["id"]),gold)
		if heal > 0: drop["gold"] = 0
	# A full collector can take the gold while leaving the potion for another hero.
	if heal > 0 and restored <= 0: return false
	if restored > 0:
		collector.hp += restored
		if collector == player:
			notice_text = "恢复 %d 点生命" % restored
			notification_time = 1.5
	return true


func _place_drop(id: int,point: Vector2,gold: int,heal: int) -> void:
	if gold > 0 or heal > 0:
		loot.append(_drop_record(id,point,gold,heal))


func _drop_record(id: int,point: Vector2,gold: int,heal: int) -> Dictionary:
	return {"id":id,"point":point,"gold":gold,"heal":heal}


func _sweep_gold() -> void:
	if run.state == Progress.State.DEFEAT: return
	for index in range(loot.size()-1,-1,-1):
		var drop: Dictionary = loot[index]
		var gold: int = int(drop["gold"])
		if gold <= 0: continue
		run.collect_loot(int(drop["id"]),gold)
		if int(drop["heal"]) > 0: drop["gold"] = 0
		else: loot.remove_at(index)


func _player_hurt(_victim: Node2D,damage: int) -> void:
	run.damage_taken += damage


func _enemy_target(_point: Vector2) -> Node2D:
	return player


func _update_enemy(enemy: Node2D) -> void:
	enemy.walk_input = Vector2.ZERO
	if enemy.hp <= 0 or enemy.stun_time > 0 or enemy.height > 1 or not enemy.attack_name.is_empty():
		return
	var target := _enemy_target(enemy.position)
	if target == null or target.hp <= 0: return
	var offset: Vector2 = target.position-enemy.position
	if absf(offset.x) > 1:
		enemy.facing = signf(offset.x)
	var profile: Dictionary = enemy_profiles[enemy.get_instance_id()]
	var move_name: String = profile["move"]
	if not MoveRules.valid(move_data.get(move_name)): return
	var reach: float = move_data[move_name]["reach"]
	var depth: float = move_data[move_name]["depth"]
	if enemy.enemy_kind == "archer":
		if absf(offset.y) > 20:
			enemy.walk_input = Vector2(signf(offset.x)*0.15,signf(offset.y))
		elif absf(offset.x) < 180:
			var retreat := -signf(offset.x)
			if retreat == 0.0:
				# At the same x there is no away direction. Prefer the arena interior.
				retreat = 1.0 if enemy.position.x <= enemy.arena.get_center().x else -1.0
				enemy.ground_motion.configure(enemy.arena,enemy.obstacles)
				if not enemy.ground_motion.is_free(enemy.position+Vector2(retreat,0)):
					retreat = -retreat
			enemy.walk_input = Vector2(retreat,0)
		elif enemy.skill_cooldown <= 0:
			if enemy._start_attack(move_name):
				enemy.skill_cooldown = float(move_data[move_name]["cooldown"])
		return
	if enemy.enemy_kind == "warden":
		enemy.enraged = enemy.hp*2 <= enemy.max_hp
		move_name = Skills.WARDEN_ATTACKS[boss_attack_count % Skills.WARDEN_ATTACKS.size()]
		if not MoveRules.valid(move_data.get(move_name)): return
		reach = float(move_data[move_name]["reach"])-25
		depth = float(move_data[move_name]["depth"])-12
	if absf(offset.x) > reach-15 or absf(offset.y) > depth:
		# A small depth detour prevents chasing directly into a tall pillar.
		var direction := offset.normalized()
		for obstacle in obstacles:
			if obstacle.grow(35).has_point(enemy.position+direction*45):
				direction = Vector2(0,-1 if enemy.position.y < obstacle.get_center().y else 1)
		enemy.walk_input = direction
	elif enemy.skill_cooldown <= 0:
		if enemy.enemy_kind == "warden":
			if enemy.start_warden_attack(move_name): boss_attack_count += 1
		elif enemy._start_attack(move_name):
			enemy.skill_cooldown = float(move_data[move_name]["cooldown"])


func _on_hit(source: Node2D, victim: Node2D, damage: int) -> void:
	super._on_hit(source,victim,damage)
	_record_received_hit(source,victim,damage,str(source.attack_name))


func _record_received_hit(source: Node2D, victim: Node2D, damage: int, move_id: String) -> void:
	if victim == player and damage > 0 and battle_review != null:
		battle_review.record(str(source.enemy_kind),move_id,damage,player.hp,run.room_index,run.elapsed,source.enraged)


func _on_arrow_hit(source: Node2D, victim: Node2D, damage: int, move_id: String) -> void:
	# The shooter may have recovered or changed actions before impact.
	super._on_hit(source,victim,damage)
	_record_received_hit(source,victim,damage,move_id)


func _advance_arrows(delta: float,existing_shots: int = -1) -> void:
	var index: int = 0
	# A shot created by an impact callback starts moving on the next physics step.
	for shot_index in range(arrows.size()):
		var arrow: Node2D = arrows[index]
		arrow.advance(delta,existing_shots >= 0 and shot_index >= existing_shots)
		if arrow.expired:
			arrows.remove_at(index)
			_retire_arrow(arrow)
		else:
			index += 1


func _retire_arrow(arrow: Node2D) -> void:
	world.remove_child(arrow)
	arrow.queue_free()


func _fire_arrow(source: Node2D,move: Dictionary) -> void:
	var arrow := Arrow.new()
	arrow.aim(source,player.position,move,obstacles)
	arrow.target = player
	arrow.impacted.connect(_on_arrow_hit.bind(str(source.attack_name)))
	world.add_child(arrow)
	arrows.append(arrow)
	_play_sound("shot")


func _show_exit_guidance() -> bool:
	return not withdrew and run.state == Progress.State.CLEARED


func _draw() -> void:
	if entry_blocked: return
	super._draw()
	if dungeon_data.is_empty():
		return
	var open: bool = not withdrew and run.state == Progress.State.CLEARED
	ForestArt.draw_exit(self, run.room_index, open, open and _show_exit_guidance())
	_forest_details.draw_dust(self, world.position)
	_forest_details.draw_drops(self, loot, elapsed, world.position)
	_draw_attack_telegraphs()


func _verify_step() -> void:
	if not verification_started:
		verification_started = true
		var verifier := Node.new()
		verifier.set_script(load("res://tests/dungeon_runtime.gd"))
		verifier.room = self
		add_child(verifier)
