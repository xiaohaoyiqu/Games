extends Node2D

const Entry = preload("res://game/navigation/scene_entry.gd")
var entry_blocked: bool = false

const Fighter = preload("res://game/actors/fighter.gd")
const Actions = preload("res://game/input/actions.gd")
const DEPARTURE_TEXTURE: Texture2D = preload("res://素材/运行/场景/石桥镇/town-gate-oblique.png")
const DEPARTURE_SOURCE := Rect2(126, 58, 894, 1273)
const DEPARTURE_BOUNDS := Rect2(995, 271, 190, 273)
var ui_font: Font
var player: Node2D
var panel: Control
var quest_tracker: Node2D
var _interact_key: String
var _travel_hint: String
var _equipment_trials: Dictionary = {}
var elapsed: float = 0.0
var toast: String = ""
var toast_time: float = 0.0
var world: Node2D
var town_obstacles: Array[Rect2] = [Rect2(221,477,74,40),Rect2(624,492,76,43)]
var places := {"quests":Vector2(270,515),"forge":Vector2(660,535),"portal":Vector2(1090,535)}


func _ready() -> void:
	entry_blocked = not Entry.allow(self,Session)
	if entry_blocked: return
	Actions.setup()
	_refresh_bindings()
	ui_font = preload("res://game/ui/ui_font.gd").acquire()
	_add_departure_gate()
	world = Node2D.new()
	world.y_sort_enabled = true
	add_child(world)
	for key in ["quests","forge"]:
		var vendor := Node2D.new()
		vendor.set_script(Fighter)
		vendor.position = places[key]
		vendor.is_player = true
		vendor.facing = 1 if key == "quests" else -1
		vendor.blade_color = Color("caab72")
		world.add_child(vendor)
	player = Node2D.new()
	player.set_script(Fighter)
	player.is_player = true
	player.position = Vector2(430,585)
	player.arena = Rect2(65,435,1150,205)
	player.obstacles = town_obstacles
	player.move_speed_multiplier = float(Session.rules.stats(Session.profile)["speed"])
	if Session.profile["equipped"]["weapon"] == "echo_blade": player.blade_color = Color("89e6df")
	world.add_child(player)
	quest_tracker = preload("res://game/ui/quest_tracker.gd").new()
	quest_tracker.host = self
	add_child(quest_tracker)
	var practice_context: Dictionary = Session.take_practice_context(self)
	if not practice_context.is_empty():
		player.position = practice_context.get("position",player.position)
		if practice_context["kind"] == "portal":
			open_panel("portal","",practice_context["difficulty"])
		elif practice_context["kind"] in ["inventory","forge"]:
			var mode: String = practice_context["kind"]
			var item: String = practice_context["item"]
			if equipment_choice_available(mode,item):
				_equipment_trials = practice_context.get("equipment_trials",{})
			else:
				item = ""
			open_panel(mode,item,"normal",practice_context["filter"],practice_context)
	if not Session.last_message.is_empty(): announce(Session.last_message)
	if "--verify-dungeon" in OS.get_cmdline_user_args(): _start_dungeon.call_deferred()
	if "--verify-town" in OS.get_cmdline_user_args() and not get_tree().has_meta("town_verifier_started"):
		get_tree().set_meta("town_verifier_started",true)
		var runner := Node.new()
		runner.set_script(load("res://tests/town_runtime.gd"))
		get_tree().root.add_child.call_deferred(runner)


func _notification(what: int) -> void:
	if not is_node_ready() or entry_blocked: return
	if what == NOTIFICATION_APPLICATION_FOCUS_OUT:
		player.walk_input = Vector2.ZERO
		for action in ["move_left","move_right","move_up","move_down"]:
			Input.action_release(action)
	elif what == NOTIFICATION_APPLICATION_FOCUS_IN:
		_refresh_bindings()


func _refresh_bindings() -> void:
	_interact_key = Actions.binding_text("interact")
	_travel_hint = "%s    交谈 / 出征 %s    背包 %s    委托日志 %s    菜单 %s" % [
		Actions.movement_hint(),_interact_key,Actions.binding_text("inventory"),
		Actions.binding_text("quests"),Actions.binding_text("return_menu")]


func _physics_process(delta: float) -> void:
	if entry_blocked: return
	elapsed += delta
	toast_time = maxf(0,toast_time-delta)
	player.walk_input = Input.get_vector("move_left","move_right","move_up","move_down") if panel == null else Vector2.ZERO
	player.advance(delta)
	queue_redraw()


func _unhandled_input(event: InputEvent) -> void:
	if entry_blocked: return
	if event.is_echo(): return
	if event.is_action_pressed("return_menu"):
		Session.change_local_scene("res://game/main/main_menu.tscn")
		return
	if event.is_action_pressed("pause_game"):
		close_panel()
		return
	if event.is_action_pressed("inventory"):
		if panel != null: close_panel()
		else: open_panel("inventory")
		return
	if event.is_action_pressed("quests"):
		open_panel("quests")
		return
	if event.is_action_pressed("interact") and panel == null:
		var key := nearest_place()
		if not key.is_empty(): open_panel(key)


func _add_departure_gate() -> void:
	var gate := Sprite2D.new()
	gate.name = "DepartureGate"
	gate.texture = DEPARTURE_TEXTURE
	gate.region_enabled = true
	gate.region_rect = DEPARTURE_SOURCE
	gate.region_filter_clip_enabled = true
	gate.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
	gate.centered = false
	gate.position = DEPARTURE_BOUNDS.position
	gate.scale = DEPARTURE_BOUNDS.size / DEPARTURE_SOURCE.size
	add_child(gate)


func nearest_place() -> String:
	var result := ""
	var distance := 120.0
	for key: String in places:
		var current: float = player.position.distance_to(places[key])
		if current < distance:
			distance = current
			result = key
	return result


func has_equipment_trials(item: String) -> bool:
	return _equipment_trials.get("candidate_id") == item and not _equipment_trials.get("results",{}).is_empty()


func equipment_trials_for(item: String) -> Dictionary:
	if not has_equipment_trials(item): return {}
	return _equipment_trials.duplicate(true)


func equipment_choice_available(mode: String,item: String) -> bool:
	return mode in ["inventory","forge"] and Session.rules.catalog.has(item) and (
		Session.profile["owned"].has(item) or (mode == "forge" and item in Session.rules.shop_ids())
	)


func open_panel(mode: String,selected_item: String="",difficulty: String="normal",inventory_filter: String="all",return_view: Dictionary={}) -> void:
	var returning_to_equipment: bool = mode in ["inventory","forge"] and return_view.get("kind") == mode
	# Explicit item jumps outside a trial return are inventory links.
	if not selected_item.is_empty():
		if (mode != "inventory" and not returning_to_equipment) or not equipment_choice_available(mode,selected_item):
			return
	close_panel()
	panel = Control.new()
	panel.set_script(load("res://game/ui/town_panel.gd"))
	panel.town = self
	panel.mode = mode
	if mode == "portal": panel.selected_difficulty = difficulty
	if returning_to_equipment:
		panel.inventory_filter = inventory_filter
		panel.inventory_page = return_view.get("inventory_page",0)
		panel.shop_page = return_view.get("shop_page",0)
	if not selected_item.is_empty():
		panel.selected = selected_item
		if not returning_to_equipment:
			panel.inventory_filter = inventory_filter
			panel._reveal_owned_equipment(selected_item)
		panel._focus_key = "item:"+selected_item if Session.profile["owned"].has(selected_item) else "action:preview_"+selected_item
	var canvas := CanvasLayer.new()
	canvas.layer = 20
	add_child(canvas)
	canvas.add_child(panel)


func close_panel() -> void:
	if panel != null:
		var canvas := panel.get_parent()
		panel = null
		canvas.queue_free()
		# Release the old controls and any popup before the replacement enters.
		remove_child(canvas)
	player.move_speed_multiplier = float(Session.rules.stats(Session.profile)["speed"])
	player.blade_color = Color("89e6df") if Session.profile["equipped"]["weapon"] == "echo_blade" else Color("d6ecea")


func _start_dungeon(difficulty: String="normal") -> bool:
	var prepared: Dictionary = Session.prepare_run(difficulty)
	if not prepared["ok"]:
		announce(prepared["message"])
		return false
	if Session.change_local_scene(Session.DUNGEON_SCENE,_recover_queued_departure) != OK:
		announce("副本场景暂不可用，请稍后重试")
		return false
	return true


func _recover_queued_departure(message: String) -> bool:
	if entry_blocked or is_queued_for_deletion(): return false
	announce(message)
	if is_instance_valid(panel): panel.show_departure_failure(message)
	return true


func practice_entry_load_failed(_message: String,source_panel_id: int) -> bool:
	var note := "练习场加载失败，可再次进入练习。"
	announce(note)
	if is_instance_valid(panel) and panel.get_instance_id() == source_panel_id:
		panel.result_note = note
		panel.queue_redraw()
	return true


func announce(message: String) -> void:
	toast = message
	toast_time = 5.0


func label(point: Vector2,value: String,size: int=17,color: Color=Color("c8cccb")) -> void:
	draw_string(ui_font,point,value,HORIZONTAL_ALIGNMENT_LEFT,-1,size,color)


func _draw() -> void:
	if entry_blocked: return
	draw_rect(Rect2(0,0,1280,720),Color("152532"))
	draw_colored_polygon(PackedVector2Array([Vector2(0,270),Vector2(180,122),Vector2(340,260),Vector2(535,110),Vector2(730,260),Vector2(960,125),Vector2(1280,240),Vector2(1280,450),Vector2(0,450)]),Color("263c49"))
	for point in [Vector2(55,224),Vector2(415,198),Vector2(780,226)]:
		draw_rect(Rect2(point.x,point.y+80,283,183),Color("4c4b4d"))
		draw_colored_polygon(PackedVector2Array([point+Vector2(-22,83),point+Vector2(139,-13),point+Vector2(307,83)]),Color("654956"))
		for row in range(4):
			draw_line(point+Vector2(5+row*24,72-row*20),point+Vector2(277-row*24,72-row*20),Color("80606a"),3)
		for x in [35,183]:
			draw_rect(Rect2(point+Vector2(x,126),Vector2(50,67)),Color("26313e"))
			draw_rect(Rect2(point+Vector2(x+5,132),Vector2(40,53)),Color("c4a06b"))
			draw_line(point+Vector2(x+24,133),point+Vector2(x+24,186),Color("4d4750"),5)
			draw_line(point+Vector2(x+5,157),point+Vector2(x+44,157),Color("4d4750"),4)
	draw_rect(Rect2(0,435,1280,231),Color("53606a"))
	for row in range(6):
		for column in range(19):
			var shade := 0.3+float((row*3+column*7)%5)*0.014
			draw_rect(Rect2(column*76-(38 if row%2 else 0),438+row*39,73,35),Color(shade,shade+0.055,shade+0.07))
	# Quest notice board and the smith's working forge are in the town plane.
	draw_rect(Rect2(175,346,140,94),Color("493c3b"))
	draw_rect(Rect2(181,352,128,81),Color("866b50"))
	for x in [191,229,267]: draw_rect(Rect2(x,363,29,46),Color("dbc69b"))
	draw_rect(Rect2(195,435,9,42),Color("473d3b"))
	draw_rect(Rect2(287,435,9,42),Color("473d3b"))
	draw_rect(Rect2(727,371,72,119),Color("3b424a"))
	draw_rect(Rect2(735,401,56,72),Color("382c2b"))
	draw_circle(Vector2(763,449),25+sin(elapsed*7)*2,Color("d99358"))
	draw_circle(Vector2(763,454),14,Color("f0c17c"))
	draw_rect(Rect2(623,489,92,18),Color("303846"))
	draw_rect(Rect2(653,506,32,30),Color("424a54"))
	# The arc sprite is a separate canvas item with filtered scaling.
	draw_set_transform(Vector2(DEPARTURE_BOUNDS.get_center().x, DEPARTURE_BOUNDS.end.y - 1.0), 0.0, Vector2(1.0, 0.13))
	draw_circle(Vector2.ZERO, 85.0, Color(0.08, 0.14, 0.17, 0.26), true, -1.0, true)
	draw_set_transform(Vector2.ZERO)
	var board_view: Dictionary = quest_tracker.view if is_instance_valid(quest_tracker) else {}
	var claimable_count: int = board_view.get("claimable_count",0)
	var board_caption: String = "委托板 · %d 项待领奖" % claimable_count if claimable_count > 0 else "委托板"
	label(Vector2(182,333),board_caption,19,Color("edd6a3"))
	label(Vector2(592,394),"铁匠 阿岚",18,Color("edd6a3"))
	label(Vector2(1008,248),"回响遗迹入口",21,Color("bddfd3"))
	var quest_mark: String = "?" if claimable_count > 0 else ("!" if board_view.get("has_offers",false) else "")
	if not quest_mark.is_empty(): label(Vector2(206,401),quest_mark,34,Color("f0d28d"))
	var nearby := nearest_place() if player != null else ""
	if not nearby.is_empty() and panel == null:
		var place: Vector2 = places[nearby]
		label(place+Vector2(-55,47),_interact_key+"  "+{"quests":"委托","forge":"铁匠","portal":"出征"}[nearby],18,Color("f1dfb4"))
	draw_rect(Rect2(0,0,1280,102),Color("0e1b29"))
	label(Vector2(34,38),"石桥镇",27,Color("e9d8b7"))
	label(Vector2(35,76),"在这里准备，再向遗迹出发",16,Color("8fa5ae"))
	if Session.profile != null:
		var info: Dictionary = Session.rules.level_info(Session.profile)
		label(Vector2(405,37),"流浪剑士  Lv.%d" % info["level"],22,Color("e9d8b7"))
		label(Vector2(405,75),("本阶段满级   ·   通关 %d 次" % Session.profile["clears"]) if int(info["level"]) >= 10 else "经验 %d / %d   ·   通关 %d 次" % [info["progress"],info["next"],Session.profile["clears"]],16,Color("98aab3"))
		label(Vector2(926,37),"金币 %d" % Session.profile["gold"],22,Color("e4c38b"))
		label(Vector2(926,75),"遗迹碎片 %d" % Session.profile["materials"],16,Color("98aab3"))
	draw_rect(Rect2(0,666,1280,54),Color("0e1b29"))
	draw_string(ui_font,Vector2(34,699),_travel_hint,HORIZONTAL_ALIGNMENT_LEFT,1210,17,Color("c8c8be"))
	if toast_time > 0:
		draw_rect(Rect2(225,603,830,40),Color(0.04,0.08,0.11,0.94))
		label(Vector2(245,630),toast,17,Color("d6dbba"))
