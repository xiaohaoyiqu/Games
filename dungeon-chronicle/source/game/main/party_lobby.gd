extends Control

const Actions = preload("res://game/input/actions.gd")
const Entry = preload("res://game/navigation/scene_entry.gd")
const QuestGuide = preload("res://game/quests/quest_guide.gd")
var entry_blocked: bool = false

var service: Node
var nickname: LineEdit
var address: LineEdit
var port: SpinBox
var host_button: Button
var join_button: Button
var ready_button: Button
var cancel_button: Button
var begin_button: Button
var leave_button: Button
var retry_button: Button
var normal_button: Button
var veteran_button: Button
var members: Label
var status: Label
var mode_label: Label
var departure_quest_hint: Label
var _quest_guide: RefCounted
var departure_status: Label
var bind_address: String = "*"


func _ready() -> void:
	var session: Node = service._session if is_instance_valid(service) and is_instance_valid(service._session) else Session
	entry_blocked = not Entry.allow(self,session)
	if entry_blocked: return
	if service == null: service = Session.party_service()
	_quest_guide = QuestGuide.new(session.rules)
	var ui_theme := Theme.new()
	var font: Font = preload("res://game/ui/ui_font.gd").acquire()
	ui_theme.default_font = font; ui_theme.default_font_size = 20; theme = ui_theme
	var background := ColorRect.new(); background.color = Color("101722"); background.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	background.mouse_filter = Control.MOUSE_FILTER_IGNORE; add_child(background)
	var margin := MarginContainer.new(); margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left","right","top","bottom"]: margin.add_theme_constant_override("margin_"+side,50 if side in ["left","right"] else 25)
	add_child(margin)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation",13); margin.add_child(box)
	_label(box,"回响遗迹 · 冒险队伍",30)
	_label(box,"2–4 人共同探索。房主创建队伍，队友输入房主地址与相同端口加入。",18)
	var fields := HBoxContainer.new(); fields.add_theme_constant_override("separation",15); box.add_child(fields)
	nickname = _field(fields,"角色名称","旅人",220,12)
	address = _field(fields,"房主地址","127.0.0.1",400,253)
	var port_column := VBoxContainer.new(); fields.add_child(port_column); _label(port_column,"端口",17)
	port = SpinBox.new(); port.min_value = 1024; port.max_value = 65535; port.value = 24567; port.step = 1
	port_column.add_child(port)
	var connections := HBoxContainer.new(); box.add_child(connections)
	host_button = _button(connections,"创建队伍",func(): service.host(int(port.value),nickname.text.strip_edges(),bind_address))
	join_button = _button(connections,"加入队伍",func(): service.join(address.text.strip_edges(),int(port.value),nickname.text.strip_edges()))
	leave_button = _button(connections,"离开队伍",func(): service.leave())
	var modes := HBoxContainer.new(); box.add_child(modes)
	normal_button = _button(modes,"普通",func(): service.transport.choose_mode("normal"))
	veteran_button = _button(modes,"精英",func(): service.transport.choose_mode("veteran"))
	mode_label = _label(box,"",18)
	departure_quest_hint = _label(box,"",17)
	departure_quest_hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	departure_quest_hint.add_theme_color_override("font_color",Color("e2ceaa"))
	members = _label(box,"尚未加入队伍。",21); members.custom_minimum_size.y = 160
	var commands := HBoxContainer.new(); box.add_child(commands)
	ready_button = _button(commands,"准备",_toggle_ready)
	begin_button = _button(commands,"出征",func(): service.begin())
	cancel_button = _button(commands,"取消入场",func(): service.cancel_loading())
	retry_button = _button(commands,"重试保存结算",func(): service.retry_save())
	_button(commands,"返回主菜单",func(): service.leave(); service._route("res://game/main/main_menu.tscn"))
	status = _label(box,"",18); status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	departure_status = _label(box,"",16)
	departure_status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_refresh_cancel_binding()
	service.changed.connect(_refresh)
	_refresh()


func _refresh_cancel_binding() -> void:
	cancel_button.text = "取消入场 · "+Actions.binding_text("return_menu")


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_instance_valid(cancel_button):
		_refresh_cancel_binding()


func _label(parent: Node,caption: String,size: int) -> Label:
	var label := Label.new(); label.text = caption; label.add_theme_font_size_override("font_size",size)
	parent.add_child(label); return label


func _field(parent: Node,caption: String,value: String,width: int,maximum: int) -> LineEdit:
	var column := VBoxContainer.new(); parent.add_child(column); _label(column,caption,17)
	var edit := LineEdit.new(); edit.text = value; edit.max_length = maximum; edit.custom_minimum_size.x = width
	column.add_child(edit); return edit


func _button(parent: Node,caption: String,callback: Callable) -> Button:
	var button := Button.new(); button.text = caption; button.custom_minimum_size = Vector2(180,45)
	button.pressed.connect(callback); parent.add_child(button); return button


func _unhandled_input(event: InputEvent) -> void:
	if entry_blocked: return
	if not event.is_echo() and event.is_action_pressed("return_menu"):
		if service.cancel_loading(): get_viewport().set_input_as_handled()


func _toggle_ready() -> void:
	var view: Dictionary = service.snapshot()
	for member in view.get("members",[]):
		if int(member["peer"]) == int(view["self"]): service.set_ready(not member["ready"]); return


func _refresh() -> void:
	if entry_blocked: return
	var view: Dictionary = service.snapshot()
	var connected: bool = service.transport != null and service.transport._peer != null
	var phase: String = str(view.get("phase",""))
	var lobby: bool = phase == "lobby"
	var awaiting_start: bool = phase == "loading" or (phase == "running" and not service.running())
	var joining: bool = connected and view.is_empty()
	var local_issue: String = service.local_departure_issue(view) if lobby else ""
	var departure_issue: String = service.departure_issue(view) if lobby and local_issue.is_empty() else local_issue
	var connection_issue: String = "" if connected else service.connection_issue()
	host_button.disabled = connected or not connection_issue.is_empty()
	join_button.disabled = host_button.disabled
	host_button.tooltip_text = connection_issue
	join_button.tooltip_text = connection_issue
	leave_button.disabled = not connected
	leave_button.text = "取消连接" if joining else "离开队伍"
	nickname.editable = not connected; address.editable = not connected; port.editable = not connected
	cancel_button.visible = service.can_cancel_loading()
	cancel_button.disabled = not cancel_button.visible
	ready_button.disabled = not lobby or service.pending_save()
	begin_button.disabled = not service.hosting() or not lobby or not departure_issue.is_empty()
	retry_button.visible = service.pending_save()
	retry_button.disabled = service.saving()
	retry_button.text = "正在保存结算……" if service.saving() else "重试保存结算"
	normal_button.disabled = not service.hosting() or not lobby
	veteran_button.disabled = not service.hosting() or not lobby
	var veteran: bool = not view.is_empty()
	var own_ready: bool = false
	var lines := PackedStringArray()
	for member: Dictionary in view.get("members",[]):
		var member_id: int = int(member["peer"])
		var own: bool = member_id == int(view.get("self",0))
		var name_suffix: String = "（房主）" if member_id == 1 else ""
		if own: name_suffix += "（你）"
		var member_status: String = "已准备" if member["ready"] else "未准备"
		if awaiting_start: member_status = "已确认入场" if member["loaded"] else "等待入场"
		var unlocked: bool = member["character"]["veteran"]
		if lobby and not unlocked: member_status += " · 精英未解锁"
		lines.append("%s%s · Lv.%d · %s" % [member["name"],name_suffix,member["character"]["level"],member_status])
		veteran = veteran and unlocked
		if own: own_ready = member["ready"]
	ready_button.text = "取消准备" if own_ready else "准备"
	ready_button.disabled = ready_button.disabled or (not own_ready and not local_issue.is_empty())
	veteran_button.disabled = veteran_button.disabled or not veteran
	members.text = "\n".join(lines) if not lines.is_empty() else ("正在等待房主确认……" if connected else "尚未加入队伍。")
	var catalog: RefCounted = service._session.rules.dungeons
	var difficulty: String = view.get("mode","normal")
	var preview: Dictionary = catalog.preview(difficulty)
	mode_label.text = "%s · 全程奖励：金币 %d / 经验 %d / 碎片 %d；入场按人数固定敌人生命。" % [preview["name"],preview["max_gold"],preview["clear_xp"],preview["materials"]] if not preview.is_empty() else "副本配置暂不可用。"
	var formation_rooms: int = int(preview.get("formation_rooms",0))
	if formation_rooms > 0:
		mode_label.text += "\n精英布阵 · %d 个房间采用不同站位" % formation_rooms
	if lobby and not veteran:
		var elite: Dictionary = catalog.preview("veteran")
		if not elite.is_empty(): mode_label.text += "\n精英需全员 Lv.%d，且通关 %d 次。" % [elite["unlock_level"],elite["unlock_clears"]]
	var hint: String = ""
	if lobby and not preview.is_empty():
		hint = _quest_guide.departure_hint(service._session.profile,difficulty,service._session.tracked_quest_id)
	departure_quest_hint.visible = not hint.is_empty()
	departure_quest_hint.text = "本机委托 · "+hint if departure_quest_hint.visible else ""
	status.text = "全员已确认入场，正在等待开始通知。" if phase == "running" and awaiting_start else service.note
	if not service.committed_save_hint.is_empty(): status.text += "\n"+service.committed_save_hint
	departure_status.visible = (lobby or not connected) and not service.pending_save()
	if service.pending_save(): status.text += "\n"+service.pending_save_hint()
	elif lobby:
		departure_status.text = departure_issue
		if departure_issue.is_empty():
			departure_status.text = "全员已准备，可以出征。" if service.hosting() else "全员已准备，等待房主出征。"
	elif not connection_issue.is_empty():
		departure_status.text = connection_issue
	else:
		departure_status.text = "先连接同一局域网。端口不可用时可更换；当前未提供在线队伍搜索。"
