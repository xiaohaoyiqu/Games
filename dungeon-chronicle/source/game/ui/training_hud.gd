extends Node2D

const Actions = preload("res://game/input/actions.gd")

var room: Node2D
var guidance_ui: Node2D
var equipment_ui: Control
var skill_ui: Control
var _bindings: Dictionary = {}
var _movement_hint: String
var _pause_hint: String


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		_refresh_bindings()
		queue_redraw()


func _refresh_bindings() -> void:
	for action: String in ["return_menu","toggle_ai","guidance_toggle","toggle_debug","toggle_shake","attack","jump","dash","skill","reset_room","pause_game"]:
		_bindings[action] = Actions.binding_text(action)
	_movement_hint = Actions.movement_hint()
	var resume: String = "点击下方按钮继续" if _bindings["pause_game"] == "未绑定" else "继续 %s" % _bindings["pause_game"]
	_pause_hint = "%s  ·  继续后可重置 %s" % [resume,_bindings["reset_room"]]
	var back := get_node_or_null("ReturnPortal") as Button
	if back != null:
		back.text = "返回出征选择  "+str(_bindings["return_menu"])
		back.tooltip_text = back.text


func _ready() -> void:
	skill_ui=preload("res://game/ui/skill_readiness_panel.gd").new()
	skill_ui.room=room;add_child(skill_ui)
	if room.equipment_practice!=null:
		equipment_ui=preload("res://game/ui/equipment_practice_panel.gd").new()
		equipment_ui.room=room;add_child(equipment_ui)
	else:
		guidance_ui = preload("res://game/ui/training_guide.gd").new()
		guidance_ui.room = room
		add_child(guidance_ui)
	if room.has_portal_return():
		var back := Button.new()
		back.name = "ReturnPortal"
		back.clip_text = true
		back.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
		back.position = Vector2(993,115)
		back.size = Vector2(255,37)
		back.focus_mode = Control.FOCUS_NONE
		back.add_theme_font_override("font",room.ui_font)
		back.add_theme_font_size_override("font_size",16)
		back.pressed.connect(room.return_to_portal)
		add_child(back)
	_refresh_bindings()


func label_at(point: Vector2,text: String,size: int=16,color: Color=Color("b8bfca"),width: float=-1.0) -> void:
	draw_string(room.ui_font,point,text,HORIZONTAL_ALIGNMENT_LEFT,width,size,color)


func _draw() -> void:
	if room.player == null:
		return
	var ink := Color("eedfc2")
	var muted := Color("929cab")
	draw_rect(Rect2(0,0,1280,103),Color(0.035,0.046,0.071,0.93))
	draw_line(Vector2(32,103),Vector2(1248,103),Color("5a5360"),1)
	label_at(Vector2(34,35),"DUNGEON CHRONICLE",22,ink)
	label_at(Vector2(35,60),"地下城纪行  /  回响遗迹 · 训练房",16,muted)
	var origin_hint: String = "受击回顾练习 · 返回出征选择 %s" if room.from_battle_review else "2.5D 动作原型 · 返回菜单 %s"
	if room.is_portal_practice(): origin_hint = "出征前练习 · 返回出征选择 %s"
	label_at(Vector2(35,84),origin_hint % _bindings["return_menu"],13,Color("747f93"),380)
	if not room.portal_return_note.is_empty():
		label_at(Vector2(835,178),room.portal_return_note,15,Color("e0a280"))
	label_at(Vector2(435,33),"临时装备试用" if room.equipment_practice!=null else "流浪剑士",17,ink)
	draw_rect(Rect2(435,44,297,12),Color("222b3a"))
	draw_rect(Rect2(437,46,293.0*room.player.hp/room.player.max_hp,8),Color("ac5e65"))
	label_at(Vector2(742,55),"%d / %d" % [room.player.hp,room.player.max_hp],14,muted)
	if room.fixed_trial_kind == "incoming":
		label_at(Vector2(435,81),"固定承伤 · 训练守卫重锤",14,muted)
	elif room.fixed_trial_kind == "evade":
		label_at(Vector2(435,81),"走位避锤 · 上下离开预警区",14,muted)
	elif room.fixed_trial_kind == "walk":
		label_at(Vector2(435,81),"定距走位 · 自动向上",14,muted)
	elif room.fixed_trial_kind == "skill":
		label_at(Vector2(435,81),"固定试斩 · 同线静止守卫",14,muted)
	else:
		label_at(Vector2(435,81),"引导练习 · 返回自由练习 %s" % _bindings["guidance_toggle"] if room.guidance != null else "训练守卫："+("主动进攻" if room.active_ai else "静止练习")+"   切换 %s" % _bindings["toggle_ai"],14,muted,460)
	label_at(Vector2(930,33),"ECHOES OF THE RUINS",16,ink)
	label_at(Vector2(930,58),"最高连击 %02d    总伤害 %d" % [room.best_combo,room.total_damage],15,muted)
	label_at(Vector2(930,82),"判定显示 %s   震动%s %s" % [_bindings["toggle_debug"],"开" if room.shake_enabled else "关",_bindings["toggle_shake"]]+(" · 待核对" if room.display_unsaved else ""),13,muted,320)
	if room.combo > 0:
		label_at(Vector2(1090,201),"%02d" % room.combo,51,ink)
		label_at(Vector2(1095,225),"COMBO",17,Color("b99874"))
	draw_rect(Rect2(0,666,1280,54),Color(0.035,0.046,0.071,0.98))
	draw_line(Vector2(32,666),Vector2(1248,666),Color("5a5360"),1)
	label_at(Vector2(35,699),_movement_hint,16,ink,219)
	label_at(Vector2(264,699),"三段普攻 %s" % _bindings["attack"],16,ink,147)
	label_at(Vector2(421,699),"跳跃 %s" % _bindings["jump"],16,ink,176)
	label_at(Vector2(607,699),"冲刺 %s" % _bindings["dash"],16,ink,183)
	label_at(Vector2(800,699),"破空斩 %s" % _bindings["skill"],16,ink,188)
	label_at(Vector2(1000,699),"重置 %s   暂停 %s" % [_bindings["reset_room"],_bindings["pause_game"]],16,muted,260)
	if room.show_debug:
		label_at(Vector2(35,648),"绿色：地面脚点  /  金色：攻击纵深范围  /  跳跃高度独立判定",14,Color("96c6b0"))
	if room.paused and not room.verify_mode:
		draw_rect(Rect2(0,103,1280,563),Color(0.015,0.02,0.035,0.73))
		label_at(Vector2(548,330),"已暂停",36,ink)
		label_at(Vector2(488,373),_pause_hint,17,muted,760)
