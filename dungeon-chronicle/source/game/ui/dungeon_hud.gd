extends Node2D

const Actions = preload("res://game/input/actions.gd")
const Progress = preload("res://game/levels/run_progress.gd")
var room: Node2D
var quest_tracker: Node2D
var battle_review_panel: Node2D
var settlement_label: Label
var navigation_notice: Label
var retry_button: Button
var defer_button: Button
var _town_hint: String
var _display_hint: String
var _restart_key: String
var _confirm_key: String
var _combat_hint: String
var _run_hint: String
var _pause_hint: String
var _exit_hint: String
var _exit_width: float


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		_refresh_bindings()
		queue_redraw()


func _refresh_bindings() -> void:
	var return_key := Actions.binding_text("return_menu")
	var pause_key := Actions.binding_text("pause_game")
	var interact_key := Actions.binding_text("interact")
	_town_hint = "回城 "+return_key
	_display_hint = "判定 %s   震动 %s" % [Actions.binding_text("toggle_debug"),Actions.binding_text("toggle_shake")]
	_restart_key = Actions.binding_text("reset_room")
	_confirm_key = Actions.binding_text("confirm_result")
	_combat_hint = Actions.combat_hint("破空斩")
	_run_hint = "重开本局 %s    暂停 %s" % [_restart_key,pause_key]
	var resume := "点击下方按钮继续" if pause_key == "未绑定" else "继续 "+pause_key
	_pause_hint = "%s    撤退回城 %s" % [resume,return_key]
	_exit_hint = "交互键未绑定" if interact_key == "未绑定" else interact_key+"  进入下个房间"
	_exit_width = clampf(ceilf(room.ui_font.get_string_size(_exit_hint,HORIZONTAL_ALIGNMENT_LEFT,-1,16).x)+16.0,156.0,1256.0)
	if retry_button != null and retry_button.visible: _refresh_settlement_buttons()


func _refresh_settlement_buttons() -> void:
	retry_button.text = "重试保存  "+Actions.binding_text("interact")
	retry_button.tooltip_text = retry_button.text
	if defer_button.visible:
		defer_button.text = "回主菜单核对  "+Actions.binding_text("return_menu")
		defer_button.tooltip_text = defer_button.text


func _ready() -> void:
	_refresh_bindings()
	quest_tracker = preload("res://game/ui/quest_tracker.gd").new()
	quest_tracker.host = room
	quest_tracker.in_run = true
	add_child(quest_tracker)
	battle_review_panel = preload("res://game/ui/battle_review_panel.gd").new()
	battle_review_panel.host = room
	add_child(battle_review_panel)
	settlement_label = Label.new()
	settlement_label.position = Vector2(415,467)
	settlement_label.size = Vector2(456,82)
	settlement_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	settlement_label.clip_text = true
	settlement_label.max_lines_visible = 4
	settlement_label.mouse_filter = Control.MOUSE_FILTER_PASS
	settlement_label.add_theme_font_override("font",room.ui_font)
	settlement_label.add_theme_font_size_override("font_size",15)
	settlement_label.hide()
	add_child(settlement_label)
	retry_button = _result_button(415,room.retry_settlement)
	defer_button = _result_button(646,room.defer_recorded_settlement)
	defer_button.focus_previous = defer_button.get_path_to(retry_button)
	defer_button.focus_next = defer_button.focus_previous


func _result_button(x: float,callback: Callable) -> Button:
	var button := Button.new()
	button.position = Vector2(x,552)
	button.size = Vector2(225,30)
	button.focus_mode = Control.FOCUS_ALL
	button.clip_text = true
	button.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	button.add_theme_font_override("font",room.ui_font)
	button.add_theme_font_size_override("font_size",15)
	button.hide()
	button.pressed.connect(callback)
	add_child(button)
	return button


func show_navigation_notice(message: String) -> void:
	if navigation_notice == null:
		if message.is_empty(): return
		navigation_notice = Label.new()
		navigation_notice.position = Vector2(379,590)
		navigation_notice.size = Vector2(522,68)
		navigation_notice.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		navigation_notice.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		navigation_notice.clip_text = true
		navigation_notice.mouse_filter = Control.MOUSE_FILTER_PASS
		navigation_notice.add_theme_font_override("font",room.ui_font)
		navigation_notice.add_theme_font_size_override("font_size",16)
		navigation_notice.add_theme_color_override("font_color",Color("e9c59e"))
		add_child(navigation_notice)
	navigation_notice.text = message
	navigation_notice.tooltip_text = message
	navigation_notice.visible = not message.is_empty()


func present_settlement(receipt: Dictionary,message: String,retry_recorded: bool,saving: bool) -> void:
	show_navigation_notice("")
	var failed := receipt.is_empty() and not saving
	retry_button.visible = failed
	defer_button.visible = failed and retry_recorded
	if failed:
		retry_button.focus_next = retry_button.get_path_to(defer_button) if retry_recorded else NodePath()
		retry_button.focus_previous = retry_button.focus_next
		_refresh_settlement_buttons()
		if Actions.binding_text("interact") == "未绑定" and get_viewport().gui_get_focus_owner() == null:
			retry_button.grab_focus()
	var lines := PackedStringArray()
	if saving:
		lines.append("正在保存本局结算 · 已取得的收益等待入账")
	elif receipt.is_empty():
		lines.append("奖励尚未入账")
		if retry_recorded: lines.append("本局待办已保存")
		if not message.is_empty(): lines.append(message)
		if not retry_recorded: lines.append("尚未确认保存待办，关闭游戏可能丢失本局收益。")
	else:
		lines.append("已入账 · 金币 +%d   经验 +%d   碎片 +%d" % [receipt["gold"],receipt["xp"],receipt["materials"]])
		if not message.is_empty(): lines.append("档案已转为只读，请重启游戏核对结算待办。")
		var growth := PackedStringArray()
		if receipt["level_after"] > receipt["level_before"]:
			growth.append("等级 Lv.%d → Lv.%d" % [receipt["level_before"],receipt["level_after"]])
		if receipt["elite_unlocked"]: growth.append("精英难度已解锁")
		if not growth.is_empty(): lines.append("    ".join(growth))
		if not receipt["items"].is_empty():
			var names := PackedStringArray()
			for id: String in receipt["items"]:
				names.append(str(Session.rules.catalog[id]["name"]))
			lines.append("新装备已放入背包 · "+"、".join(names))
	settlement_label.text = "\n".join(lines)
	settlement_label.tooltip_text = settlement_label.text
	if not receipt.is_empty() and not message.is_empty(): settlement_label.tooltip_text += "\n"+message
	settlement_label.add_theme_color_override("font_color",Color("e0a280") if receipt.is_empty() else Color("a8cfb0"))
	settlement_label.show()


func text(point: Vector2,value: String,size: int=17,color: Color=Color("b8bfca")) -> void:
	draw_string(room.ui_font,point,value,HORIZONTAL_ALIGNMENT_LEFT,-1,size,color)


func _draw_exit_hint() -> void:
	var foot: Vector2 = room.exit_threshold()
	var nearby: bool = room.near_exit()
	var width := _exit_width if nearby else 156.0
	var bounds := Rect2(clampf(foot.x-width*0.5,12.0,1268.0-width),foot.y+32.0,width,28.0)
	var caption := _exit_hint if nearby else "出口已开启"
	draw_rect(bounds, Color(0.04, 0.08, 0.07, 0.83))
	draw_string(room.ui_font, bounds.position + Vector2(0.0, 19.0), caption, HORIZONTAL_ALIGNMENT_CENTER, bounds.size.x, 16, Color("ccebd9"))


func _draw() -> void:
	if room.player == null:
		return
	var ink := Color("eedfc2")
	var muted := Color("98a2b0")
	var progress = room.run
	var finished: bool = room.run_finished()
	draw_rect(Rect2(0,0,1280,103),Color(0.035,0.046,0.071,0.96))
	draw_line(Vector2(32,103),Vector2(1248,103),Color("62576a"),1)
	text(Vector2(34,35),"地下城纪行",24,ink)
	text(Vector2(35,62),"回响遗迹 · %s / 第%d/%d间" % [room.difficulty.get("name","普通"),progress.room_index+1,progress.room_count],16,muted)
	text(Vector2(35,85),room.dungeon_data["rooms"][progress.room_index]["name"],15,Color("c5a982"))
	text(Vector2(405,33),"流浪剑士",17,ink)
	draw_rect(Rect2(405,44,310,14),Color("283040"))
	draw_rect(Rect2(407,46,306.0*room.player.hp/room.player.max_hp,10),Color("ac5e65"))
	text(Vector2(725,57),"%d / %d" % [room.player.hp,room.player.max_hp],15,muted)
	text(Vector2(405,84),"金币 %d     击败 %d     用时 %s" % [progress.coins,progress.kills,format_time(progress.elapsed)],15,muted)
	text(Vector2(935,34),"剩余敌人  %d" % room.living_enemies(),19,ink)
	text(Vector2(935,60),"最高连击 %d    总伤害 %d" % [room.best_combo,room.total_damage],14,muted)
	var return_action: String = _town_hint
	if finished and not room.rewards_saved:
		return_action = "正在保存结算" if room.settlement_saving else "结算待处理"
	text(Vector2(935,84),return_action if finished else return_action+"   "+_display_hint,14,muted)
	if room.boss != null and is_instance_valid(room.boss) and room.boss.hp > 0:
		var boss: Node2D = room.boss
		draw_rect(Rect2(309,123,662,47),Color(0.025,0.03,0.05,0.9))
		text(Vector2(324,144),"回响守门人"+("  ·  狂怒" if boss.enraged else ""),16,Color("e1bd95"))
		draw_rect(Rect2(324,152,630,7),Color("382b38"))
		draw_rect(Rect2(324,152,630.0*boss.hp/boss.max_hp,7),Color("b86c71") if boss.enraged else Color("bd936f"))
	if room.combo > 1:
		text(Vector2(1100,221),"%02d" % room.combo,44,ink)
		text(Vector2(1100,244),"COMBO",16,Color("b99874"))
	if not finished and progress.state == Progress.State.CLEARED:
		_draw_exit_hint()
	if room.notification_time > 0:
		draw_rect(Rect2(369,603,542,38),Color(0.03,0.05,0.07,0.9))
		text(Vector2(389,629),room.notice_text,18,Color("c5e4cf"))
	if room.intro_time > 0 and not finished:
		draw_rect(Rect2(355,191,570,77),Color(0.03,0.04,0.06,0.91))
		text(Vector2(378,222),room.dungeon_data["rooms"][progress.room_index]["name"],26,ink)
		text(Vector2(378,250),room.dungeon_data["rooms"][progress.room_index]["subtitle"],15,muted)
	draw_rect(Rect2(0,666,1280,54),Color(0.035,0.046,0.071,0.98))
	draw_line(Vector2(32,666),Vector2(1248,666),Color("62576a"),1)
	var cooldown := " %.1fs" % room.player.skill_cooldown if room.player.skill_cooldown > 0 else ""
	draw_string(room.ui_font,Vector2(32,699),_combat_hint+cooldown,HORIZONTAL_ALIGNMENT_LEFT,910,16,ink)
	draw_string(room.ui_font,Vector2(964,699),_run_hint,HORIZONTAL_ALIGNMENT_LEFT,288,16,muted)
	if room.paused and not room.verify_mode and not finished:
		draw_rect(Rect2(0,103,1280,563),Color(0.015,0.02,0.035,0.76))
		text(Vector2(550,326),"已暂停",36,ink)
		draw_string(room.ui_font,Vector2(200,373),_pause_hint,HORIZONTAL_ALIGNMENT_CENTER,880,18,muted)
	if finished:
		draw_rect(Rect2(0,103,1280,563),Color(0.015,0.02,0.035,0.86))
		draw_rect(Rect2(379,177,522,408),Color("161f2e"))
		draw_rect(Rect2(379,177,522,408),Color("6e6370"),false,2)
		var won: bool = progress.state == Progress.State.VICTORY
		var heading: String = "遗迹已净化" if won else "冒险暂告一段落"
		var summary: String = "回响守门人已被击败" if won else "调整走位，留意敌人的出招预警"
		if room.withdrew:
			heading = "本次出征已结束"
			summary = "结算已取得的金币和击败记录"
		text(Vector2(466,244),heading,34,ink)
		text(Vector2(448,291),summary,19,muted)
		text(Vector2(448,342),"本局金币      %d" % progress.coins,22,Color("e0c38e"))
		text(Vector2(448,380),"击败敌人      %d" % progress.kills,20,muted)
		text(Vector2(448,416),"用时 %s   /   最高连击 %d" % [format_time(progress.elapsed),room.best_combo],20,muted)
		text(Vector2(448,452),"受到伤害      %d" % progress.damage_taken,18,muted)
		if room.rewards_saved:
			var next_action := "再来一局 %s   /   返回城镇 %s" % [_restart_key,_confirm_key] if won or room.withdrew else "再来一局 %s / %s   /   %s" % [_restart_key,_confirm_key,_town_hint]
			draw_string(room.ui_font,Vector2(415,571),next_action,HORIZONTAL_ALIGNMENT_LEFT,456,18,ink)


func format_time(seconds: float) -> String:
	var total := int(seconds)
	return "%02d:%02d" % [total/60,total%60]
