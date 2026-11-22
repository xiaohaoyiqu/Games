extends Node2D

const Actions = preload("res://game/input/actions.gd")
const Exit = preload("res://game/levels/dungeon_exit.gd")
const EXIT_LABELS := {
	Exit.Status.DOWN:"已倒下",
	Exit.Status.APPROACH:"未到出口",
	Exit.Status.LAND:"等待落地",
	Exit.Status.EDGE:"出口边缘",
	Exit.Status.READY:"已到出口",
}

var room: Node2D
var quest_tracker: Node2D
var arrivals: Dictionary = {}
var retreat_panel: PanelContainer
var continue_button: Button
var retreat_button: Button
var retry_button: Button
var personal_pause_button: Button
var _return_key: String
var _pause_key: String
var _interact_key: String
var _combat_hint: String
var _local_peer: int
var _review_heading: String
var _member_lines: Array[String] = []
var _damage_heading: String
var _hit_lines: Array[String] = []


func _ready() -> void:
	_local_peer = room.service.local_id()
	room.terminal_review_ready.connect(_prepare_terminal_text)
	quest_tracker = preload("res://game/ui/quest_tracker.gd").new()
	quest_tracker.host = room
	quest_tracker.in_run = true
	quest_tracker.party = true
	add_child(quest_tracker)
	retry_button = Button.new()
	retry_button.position = Vector2(700,546)
	retry_button.size = Vector2(278,38)
	retry_button.focus_mode = Control.FOCUS_ALL
	retry_button.clip_text = true
	retry_button.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	retry_button.add_theme_font_override("font",room.ui_font)
	retry_button.add_theme_font_size_override("font_size",16)
	retry_button.hide()
	retry_button.pressed.connect(room.retry_result_save)
	add_child(retry_button)
	personal_pause_button = Button.new()
	personal_pause_button.toggle_mode = true
	personal_pause_button.position = Vector2(850,673)
	personal_pause_button.size = Vector2(205,40)
	personal_pause_button.clip_text = true
	personal_pause_button.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	personal_pause_button.add_theme_font_override("font",room.ui_font)
	personal_pause_button.add_theme_font_size_override("font_size",16)
	personal_pause_button.toggled.connect(room.set_personal_pause)
	add_child(personal_pause_button)
	room.service.changed.connect(_refresh_service_state)
	_refresh_service_state()
	if room.service.hosting(): _build_retreat_panel()
	_refresh_bindings()


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		_refresh_bindings()
		_refresh_service_state()


func _refresh_bindings() -> void:
	_return_key = Actions.binding_text("return_menu")
	_pause_key = Actions.binding_text("pause_game")
	_interact_key = Actions.binding_text("interact")
	_combat_hint = Actions.combat_hint()
	refresh_personal_pause()
	if retreat_panel != null:
		continue_button.text = "返回本局（%s）" % _pause_key
		retreat_button.text = "结束全队本局（%s）" % _interact_key


func _build_retreat_panel() -> void:
	retreat_panel = PanelContainer.new()
	retreat_panel.position = Vector2(350,255)
	retreat_panel.custom_minimum_size.x = 580
	var panel_theme := Theme.new()
	panel_theme.default_font = room.ui_font
	panel_theme.default_font_size = 19
	retreat_panel.theme = panel_theme
	var panel_style := StyleBoxFlat.new()
	panel_style.bg_color = Color("172332")
	panel_style.border_color = Color("987952")
	panel_style.set_border_width_all(2)
	panel_style.content_margin_left = 24
	panel_style.content_margin_right = 24
	panel_style.content_margin_top = 20
	panel_style.content_margin_bottom = 20
	retreat_panel.add_theme_stylebox_override("panel",panel_style)
	retreat_panel.hide()
	add_child(retreat_panel)

	var rows := VBoxContainer.new()
	rows.add_theme_constant_override("separation",12)
	retreat_panel.add_child(rows)
	var title := Label.new()
	title.text = "结束全队本局？"
	title.add_theme_font_size_override("font_size",25)
	rows.add_child(title)
	var detail := Label.new()
	detail.text = "确认后全队撤退，结算已拾取的金币和击败进度。\n选择期间，队友与敌人仍会行动。"
	rows.add_child(detail)

	var actions := HBoxContainer.new()
	actions.add_theme_constant_override("separation",12)
	rows.add_child(actions)
	continue_button = Button.new()
	continue_button.pressed.connect(room.cancel_retreat_confirmation)
	actions.add_child(continue_button)
	retreat_button = Button.new()
	retreat_button.pressed.connect(room.confirm_retreat)
	actions.add_child(retreat_button)
	continue_button.focus_next = continue_button.get_path_to(retreat_button)
	continue_button.focus_previous = continue_button.focus_next
	retreat_button.focus_next = retreat_button.get_path_to(continue_button)
	retreat_button.focus_previous = retreat_button.focus_next


func show_retreat_confirmation(value: bool) -> void:
	retreat_panel.visible = value
	refresh_personal_pause()
	if value: continue_button.grab_focus()


func refresh_personal_pause(resumed: bool = false) -> void:
	personal_pause_button.visible = room.can_change_personal_pause()
	personal_pause_button.set_pressed_no_signal(room._local_blocked)
	var caption := "恢复个人操作" if room._local_blocked else "暂停个人操作"
	personal_pause_button.text = caption if _pause_key.is_empty() or _pause_key == "未绑定" else caption+"  "+_pause_key
	personal_pause_button.tooltip_text = personal_pause_button.text+" · 队伍战斗继续"
	if resumed and personal_pause_button.has_focus():
		personal_pause_button.release_focus()
	queue_redraw()


func _refresh_service_state() -> void:
	refresh_personal_pause()
	arrivals = room.service.arrival_states(room.run_id)
	var was_visible := retry_button.visible
	retry_button.visible = room.can_retry_result_save()
	if retry_button.visible:
		var binding := Actions.binding_text("interact")
		retry_button.text = "重试保存  "+binding
		retry_button.tooltip_text = room.service.pending_save_hint()
		if not was_visible and binding == "未绑定" and get_viewport().gui_get_focus_owner() == null:
			retry_button.grab_focus()
	queue_redraw()


func text(point: Vector2,value: String,size: int=17,color: Color=Color("b8bfca")) -> void:
	draw_string(room.ui_font,point,value,HORIZONTAL_ALIGNMENT_LEFT,-1,size,color)


func _draw() -> void:
	if room.player == null: return
	var service = room.service
	var ink := Color("eedfc2")
	draw_rect(Rect2(0,0,1280,130),Color("0c121e"))
	text(Vector2(30,30),"回响遗迹 · 组队%s · 第 %d/4 间" % [room.difficulty["name"],room.run.room_index+1],22,ink)
	text(Vector2(790,30),"共享金币 %d  /  击败 %d" % [room.run.coins,room.run.kills],18,ink)
	var column: int = 0
	var at_exit: int = 0
	var living: int = 0
	var local_status: int = Exit.Status.HIDDEN
	var loaded_members: int = 0
	for id in room.heroes:
		var hero: Node2D = room.heroes[id]
		var x: int = 30+column*310
		text(Vector2(x,62),str(room.hero_names[id])+(" · 你" if id == _local_peer else ""),17,ink)
		draw_rect(Rect2(x,75,230,10),Color("283040"))
		draw_rect(Rect2(x,75,230.0*hero.hp/hero.max_hp,10),Color("ac5e65"))
		text(Vector2(x,108),"%d / %d" % [hero.hp,hero.max_hp],14)
		var status: int = room.exit_status(id)
		if id == _local_peer: local_status = status
		if EXIT_LABELS.has(status):
			if status != Exit.Status.DOWN: living += 1
			if status == Exit.Status.READY: at_exit += 1
			var color := Color("9edbc1") if status == Exit.Status.READY else Color("e9c59e")
			text(Vector2(x+110,108),EXIT_LABELS[status],14,color)
		if not room._active and arrivals.has(id):
			var confirmed: bool = arrivals[id]
			if confirmed: loaded_members += 1
			text(Vector2(x+110,108),"已确认入场" if confirmed else "等待入场",14,Color("9edbc1") if confirmed else Color("e9c59e"))
		column += 1
	draw_rect(Rect2(0,666,1280,54),Color("0c121e"))
	if not room.retreat_confirmation_open and room.run.state not in [2,3]:
		draw_string(room.ui_font,Vector2(30,697),_combat_hint,HORIZONTAL_ALIGNMENT_LEFT,810,16,ink)
		var return_hint: String = "离队"
		if service.hosting():
			return_hint = "取消入场" if not room._active else "撤退选项"
		draw_string(room.ui_font,Vector2(1068,697),"%s %s" % [return_hint,_return_key],HORIZONTAL_ALIGNMENT_LEFT,190,16,Color("b8bfca"))
	if not room._active:
		var message: String = service.note
		if service.loading_confirmation_pending(room.run_id):
			message = "本机已加载，正在发送入场确认……"
		elif not arrivals.is_empty():
			message = "已确认入场 %d/%d" % [loaded_members,arrivals.size()]
			if loaded_members == arrivals.size(): message += " · 等待开始"
		text(Vector2(395,310),message,24,ink)
	elif room.exit_open():
		text(Vector2(810,641),exit_hint(at_exit,living,local_status),18,Color("9edbc1"))
	if room.notification_time > 0: text(Vector2(375,610),room.notice_text,18,ink)
	if not room.run_finished() and not room.retreat_confirmation_open and (room._local_blocked or not room._has_focus):
		text(Vector2(360,178),_resume_hint(),19,Color("e9c59e"))
	if room.player.hp <= 0 and room.run.state in [0,1]:
		var wait: String = "你已倒下，等待队友进入下一间。" if room.exit_open() else "你已倒下，等待队友完成本局。"
		text(Vector2(390,305),wait,24,ink)
	var sync_hint := _sync_hint()
	if not sync_hint.is_empty():
		text(Vector2(450,220),sync_hint,20,Color("e9c59e"))
	if room.run.state in [2,3]:
		_draw_result(service,ink)


func _sync_hint() -> String:
	if room.run.state != 0 or not room._run_is_active():
		return ""
	if room.waiting_for_snapshot():
		return "等待房主同步，移动与出招暂缓" if room._has_replica_frame else "等待房主同步初始战况"
	if room.input_cancel_pending(): return "正在重试清除旧操作，移动与出招暂缓"
	return ""


func _resume_hint() -> String:
	if not room._has_focus: return "队伍战斗继续 · 请先切回游戏窗口"
	if _pause_key == "未绑定": return "个人操作已暂停 · 点击下方「恢复个人操作」继续"
	return "个人操作已暂停，队伍战斗继续 · 按 %s 恢复操作" % _pause_key


func exit_hint(at_exit: int,living: int,local_status: int) -> String:
	if local_status == Exit.Status.HIDDEN: return "等待房主同步出口状态，暂缓操作"
	if living == 0: return "等待本局结束"
	var tally: String = "出口集合 %d/%d" % [at_exit,living]
	if local_status == Exit.Status.DOWN: return tally + " · 等待队友前进"
	if room.input_cancel_pending(): return tally + " · 正在重试清除旧操作"
	if local_status == Exit.Status.APPROACH: return tally + " · 前往右侧集合区"
	if local_status == Exit.Status.LAND: return tally + " · 落地后集合"
	if local_status == Exit.Status.EDGE: return tally + " · 请站进集合区"
	if at_exit < living: return tally + " · 等待存活队员到齐"
	if not room._local_controls_available(): return tally + " · 恢复操作后前进"
	if _interact_key == "未绑定": return tally + " · 前进交互键未绑定"
	return tally + " · 按 %s 请求前进" % _interact_key


func _draw_result(service: Node,ink: Color) -> void:
	draw_rect(Rect2(250,205,780,389),Color(0.03,0.045,0.07,0.96))
	text(Vector2(300,252),"遗迹已净化" if room.run.state == 2 else ("本局已撤退" if room._retreated else "队伍已倒下"),30,ink)
	text(Vector2(300,289),"共享金币 %d    击败 %d" % [room.run.coins,room.run.kills],20,ink)
	if _review_heading.is_empty():
		text(Vector2(300,331),"等待终局确认……",16)
	else:
		text(Vector2(300,320),_review_heading,16,ink)
		for i in range(_member_lines.size()):
			var point := Vector2(300+float(i%2)*355,351+floorf(i/2.0)*27)
			text(point,_member_lines[i],15)
		text(Vector2(300,420),_damage_heading,15,Color("b8bfca") if room.local_terminal_hits.is_empty() else Color("dfc594"))
		for i in range(_hit_lines.size()):
			text(Vector2(300,443+i*20),_hit_lines[i],14)
	text(Vector2(300,520),service.note.left(42),16,Color("e8ba9c") if service.pending_save() else Color("b7dbc6"))
	if service.result_queue_pending(room.run_id):
		text(Vector2(300,542),"仍有队员的终局等待发送",14,Color("e8ba9c"))
	if not service.committed_save_hint.is_empty():
		text(Vector2(565,542),service.committed_save_hint,14,Color("e8ba9c"))
	draw_string(room.ui_font,Vector2(300,568),_result_action_hint(service),HORIZONTAL_ALIGNMENT_LEFT,390 if retry_button.visible else 680,20,ink)


func _result_action_hint(service: Node) -> String:
	if service.accepted_result_tick(room.run_id) < 0:
		if not service.owns_run(room.run_id): return "已离开本局"
		return "正在确认全队结算" if service.hosting() else "等待房主确认结算，此时离队（%s）无法领取本局收益" % _return_key
	if service.saving():
		return "正在保存结算，请稍候……" if service.hosting() else "正在保存本机结算，也可离队（%s）" % _return_key
	if service.pending_save():
		return "本机结算尚未入账" if service.hosting() else "本机结算尚未入账，可离队（%s）" % _return_key
	if not service.hosting(): return "等待房主返回队伍，或离队（%s）" % _return_key
	if service.result_queue_pending(room.run_id): return "重试发送并返回队伍（%s）" % _return_key
	return "返回队伍（%s），重新准备后出征" % _return_key


func _prepare_terminal_text() -> void:
	var review: Dictionary = room.terminal_review
	var seconds: int = floori(float(review["elapsed_ms"])/1000.0)
	_review_heading = "终局成员 · 第 %d 间 · 用时 %02d:%02d" % [review["room"],seconds/60,seconds%60]
	_member_lines.clear()
	for row: Dictionary in review["members"]:
		var member_name: String = row["name"] + ("（你）" if row["peer"] == _local_peer else "")
		var status := "未在终局"
		if row["present"]:
			status = "%d / %d" % [row["hp"],row["max_hp"]] if int(row["hp"]) > 0 else "已倒下"
		_member_lines.append(member_name + " · " + status)
	var hits: Dictionary = room.local_terminal_hits
	_hit_lines.clear()
	_damage_heading = "本局个人受击数据不可用"
	if not hits.is_empty():
		_damage_heading = "个人受伤 %d" % hits["actual_damage"]
		if not hits["complete"]: _damage_heading += " · 记录有缺项"
		for hit: Dictionary in hits["recent"]:
			var hit_seconds: int = int(hit["time"])
			_hit_lines.append("第%d间 · %02d:%02d · %s / %s · 扣血 %d" % [
				hit["room"],hit_seconds/60,hit_seconds%60,hit["source"],hit["label"],hit["damage"]])
	queue_redraw()
