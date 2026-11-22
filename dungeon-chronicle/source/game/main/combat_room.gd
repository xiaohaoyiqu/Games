extends Node2D

const Entry = preload("res://game/navigation/scene_entry.gd")
var entry_blocked: bool = false

const Fighter = preload("res://game/actors/fighter.gd")
const Pillar = preload("res://game/levels/stone_pillar.gd")
const Skills = preload("res://game/combat/skill_catalog.gd")
const MoveRules = preload("res://game/combat/move_rules.gd")
const Telegraph=preload("res://game/presentation/attack_telegraph.gd")
const TelegraphDraw=preload("res://game/ui/attack_telegraph_draw.gd")
const StartupTrace = preload("res://game/diagnostics/frame_trace.gd")
var _move_error_message: String = ""
var player: Node2D
var fighters: Array[Node2D] = []
var world: Node2D
var ui: Node2D
var ui_font: Font
var move_data: Dictionary
var obstacles: Array[Rect2] = [Rect2(245,491,50,27),Rect2(996,472,50,27)]
var elapsed: float = 0.0
var hit_stop: float = 0.0
var combo: int = 0
var combo_timer: float = 0.0
var best_combo: int = 0
var total_damage: int = 0
var effects: Array[Dictionary] = []
var numbers: Array[Dictionary] = []
var _resume_button: Button
var paused: bool = false:
	set(value):
		paused = value
		_sync_resume_button()
var show_debug: bool = false
var active_ai: bool = false
var shake_enabled: bool = true
var display_unsaved: bool=false
var _display_preferences: RefCounted
var _sound_cues: Node
var shake: float = 0.0
var verify_mode: bool = false
var verify_frame: int = 0
var verify_results: Dictionary = {}


func _requires_progression() -> bool: return false
func _entry_session() -> Node: return Session
func _guard_entry() -> bool:
	entry_blocked = not Entry.allow(self,_entry_session(),_requires_progression())
	return not entry_blocked


func _ready() -> void:
	if not _guard_entry(): return
	move_data = _move_definitions()
	if move_data.is_empty():
		entry_blocked = true
		Entry.block(self,_entry_session(),_move_failure_message(),false)
		return
	var measured: int = StartupTrace.begin()
	ui_font = preload("res://game/ui/ui_font.gd").acquire()
	StartupTrace.end("scene_font",measured,-1)
	measured = StartupTrace.begin()
	_setup_input()
	_bind_display_preferences()
	_bind_sound_cues()
	var backdrop := _make_backdrop()
	backdrop.name = "CachedBackdrop"
	backdrop.show_behind_parent = true
	add_child(backdrop)
	world = Node2D.new()
	world.name = "GroundSortedWorld"
	world.y_sort_enabled = true
	add_child(world)
	for point in [Vector2(270,505),Vector2(1021,486)]:
		var pillar := _make_obstacle_view()
		pillar.position = point
		world.add_child(pillar)
	StartupTrace.end("scene_world",measured,-1)
	measured = StartupTrace.begin()
	_populate()
	StartupTrace.end("scene_populate",measured,-1)
	if entry_blocked: return
	measured = StartupTrace.begin()
	ui = Node2D.new()
	ui.set_script(load(_hud_script()))
	ui.room = self
	var canvas := CanvasLayer.new()
	canvas.layer = 10
	add_child(canvas)
	canvas.add_child(ui)
	StartupTrace.end("scene_hud",measured,-1)
	verify_mode = "--verify-runtime" in OS.get_cmdline_user_args() or "--verify-dungeon" in OS.get_cmdline_user_args()
	if not verify_mode and _pauses_on_focus_loss():
		_resume_button = Button.new()
		_resume_button.name = "ResumeCombat"
		_resume_button.text = "继续战斗"
		_resume_button.position = Vector2(535,397)
		_resume_button.size = Vector2(210,44)
		_resume_button.add_theme_font_override("font",ui_font)
		_resume_button.add_theme_font_size_override("font_size",18)
		_resume_button.pressed.connect(_resume_from_button)
		_sync_resume_button()
		ui.add_child(_resume_button)
	queue_redraw()


func _make_backdrop() -> Node2D:
	return preload("res://game/levels/cached_backdrop.gd").new()


func _make_obstacle_view() -> Node2D:
	return Pillar.new()


func _populate() -> void:
	pass


func _move_definitions() -> Dictionary:
	var catalog := Skills.new()
	_move_error_message = catalog.error_message()
	return catalog.moves_snapshot()

func _move_failure_message() -> String:
	var detail: String = _move_error_message if not _move_error_message.is_empty() else "技能配置不可用，当前无法开始战斗。"
	return detail+"请修复游戏文件后重新启动，或返回主菜单。"


func _hud_script() -> String:
	return "res://game/ui/training_hud.gd"


func _spawn(point: Vector2, controlled: bool) -> Node2D:
	var fighter := Node2D.new()
	fighter.set_script(Fighter)
	fighter.position = point
	fighter.spawn_point = point
	fighter.is_player = controlled
	fighter.max_hp = 100 if controlled else 160
	fighter.hp = fighter.max_hp
	fighter.facing = 1 if controlled else -1
	fighter.moves = move_data
	fighter.obstacles = obstacles
	fighter.landed_hit.connect(_on_hit)
	fighter.jump_started.connect(_sound_jump)
	fighter.ground_landed.connect(_sound_land)
	fighter.dash_started.connect(_sound_dash)
	world.add_child(fighter)
	fighters.append(fighter)
	return fighter


func _opponents_for(fighter: Node2D) -> Array[Node2D]:
	var result: Array[Node2D] = []
	for other in fighters:
		if other.is_player != fighter.is_player:
			result.append(other)
	return result


func _setup_input() -> void:
	preload("res://game/input/actions.gd").setup()


func _bind_display_preferences() -> void:
	var context: Node=_entry_session()
	var preferences: Variant=context.get("display_preferences")
	if preferences is RefCounted:
		_display_preferences=preferences
		_display_preferences.changed.connect(_display_changed)
		_display_changed()

func _display_changed() -> void:
	if not is_inside_tree() or is_queued_for_deletion():return
	var enabled: bool=_display_preferences.screen_shake()
	var toggled: bool=enabled!=shake_enabled
	shake_enabled=enabled;display_unsaved=_display_preferences.dirty()
	if toggled or not enabled:
		shake=0.0
		if is_instance_valid(world):world.position=Vector2.ZERO

func _toggle_shake() -> void:
	if not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene!=self:return
	if _display_preferences!=null:_display_preferences.set_shake(not shake_enabled)
	else:
		shake_enabled=not shake_enabled
		shake=0.0;world.position=Vector2.ZERO


func _bind_sound_cues() -> void:
	var candidate: Variant=_entry_session().get("sound_cues")
	if candidate is Node:_sound_cues=candidate

func _play_sound(id: String) -> bool:
	if paused or entry_blocked or not is_instance_valid(_sound_cues):return false
	return _sound_cues.play_scene(id,self)

func _stop_sound() -> void:
	if is_instance_valid(_sound_cues):_sound_cues.stop_scene(self)

func _uses_motion_sound() -> bool:return true
func _sound_jump(source: Node2D,_sequence: int) -> void:
	if _uses_motion_sound() and source==player and source.hp>0:_play_sound("jump")
func _sound_dash(source: Node2D) -> void:
	if _uses_motion_sound() and source==player and source.hp>0:_play_sound("dash")
func _sound_land(source: Node2D) -> void:
	if _uses_motion_sound() and source==player and source.hp>0:_play_sound("land")
func _exit_tree() -> void:_stop_sound()


func _unhandled_input(event: InputEvent) -> void:
	if entry_blocked or not is_instance_valid(player): return
	if event.is_echo(): return
	if event.is_action_pressed("return_menu") and not event.is_echo():
		Session.change_local_scene("res://game/main/main_menu.tscn")
		return
	if event.is_action_pressed("pause_game"):
		_set_local_pause(not paused)
		return
	if event.is_action_pressed("toggle_shake"):
		_toggle_shake();return
	if paused:
		return
	if event.is_action_pressed("reset_room"):
		reset_room()
		return
	if event.is_action_pressed("toggle_ai"):
		active_ai = not active_ai
	if event.is_action_pressed("toggle_debug"):
		show_debug = not show_debug
	for action in ["attack","jump","dash","skill"]:
		if event.is_action_pressed(action) and not event.is_echo():
			request_player_action(action)


func request_player_action(action: String) -> void:
	if entry_blocked or paused or not is_inside_tree() or is_queued_for_deletion() or not is_instance_valid(player):
		return
	player.request_action(action)


func _set_local_pause(value: bool) -> void:
	paused = value
	if paused: _stop_sound()
	player.clear_buffered_actions()
	player.walk_input = Vector2.ZERO


func _can_resume_combat() -> bool:
	return not entry_blocked and is_instance_valid(player)


func _sync_resume_button() -> void:
	if is_instance_valid(_resume_button):
		_resume_button.visible = paused and not verify_mode and _can_resume_combat()


func _resume_from_button() -> void:
	if paused and not is_queued_for_deletion() and _resume_button.is_visible_in_tree() and _can_resume_combat():
		_set_local_pause(false)


func _pauses_on_focus_loss() -> bool: return true


func _notification(what: int) -> void:
	if what != NOTIFICATION_APPLICATION_FOCUS_OUT or not is_instance_valid(player): return
	player.clear_buffered_actions()
	player.walk_input = Vector2.ZERO
	for action in ["move_left","move_right","move_up","move_down"]:
		Input.action_release(action)
	_stop_sound()
	if _pauses_on_focus_loss(): paused = true


func _player_walk_input() -> Vector2:
	return Input.get_vector("move_left","move_right","move_up","move_down")


func _physics_process(delta: float) -> void:
	if entry_blocked: return
	if verify_mode:
		_verify_step()
	ui.queue_redraw()
	queue_redraw()
	if paused:
		return
	elapsed += delta
	for index in range(effects.size()-1,-1,-1):
		effects[index]["life"] -= delta
		if effects[index]["life"] <= 0:
			effects.remove_at(index)
	for index in range(numbers.size()-1,-1,-1):
		numbers[index]["life"] -= delta
		if numbers[index]["life"] <= 0:
			numbers.remove_at(index)
	combo_timer = maxf(0,combo_timer-delta)
	if combo_timer <= 0:
		combo = 0
	shake = maxf(0,shake-delta*25)
	world.position = Vector2(sin(elapsed*110),cos(elapsed*132))*shake if shake_enabled else Vector2.ZERO
	if hit_stop > 0:
		hit_stop = maxf(0,hit_stop-delta)
		return
	player.walk_input = _player_walk_input()
	for fighter in fighters:
		fighter.debug_boxes = show_debug
		if not fighter.is_player:
			_update_enemy(fighter)
		fighter.advance(delta)


func _update_enemy(enemy: Node2D) -> void:
	enemy.walk_input = Vector2.ZERO
	if not active_ai or enemy.hp <= 0 or player.hp <= 0:
		return
	if not MoveRules.valid(move_data.get("dummy_strike")): return
	var offset: Vector2 = player.position-enemy.position
	if enemy.attack_name.is_empty() and enemy.stun_time <= 0:
		if absf(offset.x) > 1:
			enemy.facing = signf(offset.x)
		if absf(offset.x) > 67 or absf(offset.y) > 21:
			enemy.walk_input = offset.normalized()*0.42
		elif enemy.skill_cooldown <= 0:
			if enemy._start_attack("dummy_strike"):
				enemy.skill_cooldown = float(move_data["dummy_strike"]["cooldown"])


func _on_hit(source: Node2D, victim: Node2D, damage: int) -> void:
	if damage>0:
		if victim==player:_play_sound("hurt")
		elif not victim.is_player:_play_sound("impact")
	if source.is_player:
		combo += 1
		best_combo = maxi(best_combo,combo)
		total_damage += damage
		combo_timer = 2.0
	hit_stop = 0.045
	shake = 3.0
	effects.append({"point":victim.position-Vector2(0,victim.height+53),"life":0.16})
	numbers.append({"point":victim.position-Vector2(0,victim.height+105),"life":0.75,"damage":damage,"player":source.is_player})


func reset_room() -> void:
	if entry_blocked: return
	_stop_sound()
	for fighter in fighters:
		fighter.reset_fighter()
	combo = 0
	best_combo = 0
	total_damage = 0
	combo_timer = 0
	effects.clear()
	numbers.clear()
	hit_stop = 0
	shake = 0
	world.position = Vector2.ZERO


func _draw_masonry_details() -> void:
	for x in [319,960]:
		draw_rect(Rect2(x-7,235,14,48),Color("161d2a"))
		draw_circle(Vector2(x,222),50,Color(0.85,0.4,0.12,0.045))
		draw_circle(Vector2(x,222),30,Color(0.95,0.5,0.15,0.06))
		var flicker := sin(elapsed*13+x)*3
		draw_colored_polygon(PackedVector2Array([Vector2(x-9,238),Vector2(x-12,220),Vector2(x-3,206+flicker),Vector2(x+1,218),Vector2(x+9,199-flicker),Vector2(x+13,225),Vector2(x+7,238)]),Color("dc8b52"))
		draw_colored_polygon(PackedVector2Array([Vector2(x-5,238),Vector2(x-5,223),Vector2(x+3,216+flicker),Vector2(x+6,233)]),Color("f2d697"))
	# Dark edges frame the arena without obscuring its central combat space.
	draw_rect(Rect2(0,350,29,320),Color(0.02,0.025,0.04,0.55))
	draw_rect(Rect2(1251,350,29,320),Color(0.02,0.025,0.04,0.55))


func _draw() -> void:
	if entry_blocked: return
	_draw_masonry_details()
	for effect in effects:
		var point: Vector2 = effect["point"]
		var size: float = 25.0*(float(effect["life"])/0.16)
		for i in range(8):
			var dir := Vector2.from_angle(i*TAU/8+0.2)
			draw_line(point+dir*5,point+dir*size,Color("f7ddb0"),3)
	for number in numbers:
		var point: Vector2 = number["point"]
		point.y -= (0.75-float(number["life"]))*48
		draw_string(ui_font,point,str(number["damage"]),HORIZONTAL_ALIGNMENT_CENTER,48,24,Color("f7d8a2") if number["player"] else Color("ef8b87"))


func _draw_attack_telegraphs() -> void:
	# At most the scene's existing fighters. No nodes, files, JSON or move copies.
	draw_set_transform(world.position)
	for fighter in fighters:
		if not is_instance_valid(fighter) or fighter.is_player or not fighter.is_visible_in_tree() or fighter.hp<=0:continue
		var view: Dictionary=Telegraph.describe(fighter.position,float(fighter.facing),float(fighter.height),str(fighter.attack_name),float(fighter.attack_time),fighter.current_move)
		TelegraphDraw.paint(self,view,ui_font)
	draw_set_transform(Vector2.ZERO)


func _verify_step() -> void:
	pass


func _action_event(action: String) -> InputEventAction:
	var event := InputEventAction.new()
	event.action = action
	event.pressed = true
	return event


func _capture(path: String) -> void:
	await get_tree().process_frame
	await RenderingServer.frame_post_draw
	var status := get_viewport().get_texture().get_image().save_png(path)
	if status != OK:
		push_error("Screenshot failed: " + path)
		Session.request_quit(2)
