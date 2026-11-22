extends CanvasLayer

# Owned by the persistent service, independent of the scene that failed to load.
const Actions = preload("res://game/input/actions.gd")
const MENU: String = "res://game/main/main_menu.tscn"
const LOBBY: String = "res://game/main/party_lobby.tscn"
var service: Node
var message: Label
var hint: Label
var status: Label
var lobby_button: Button
var menu_button: Button
var save_button: Button

func _ready() -> void:
	layer = 100
	var surface := Control.new(); surface.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT); add_child(surface)
	surface.mouse_filter = Control.MOUSE_FILTER_STOP
	var shade := ColorRect.new(); shade.color = Color(0.025,0.04,0.065,0.98)
	shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT); shade.mouse_filter = Control.MOUSE_FILTER_IGNORE; surface.add_child(shade)
	var center := CenterContainer.new(); center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT); surface.add_child(center)
	var panel := PanelContainer.new(); panel.custom_minimum_size.x = 860; center.add_child(panel)
	var theme := Theme.new(); theme.default_font = preload("res://game/ui/ui_font.gd").acquire(); theme.default_font_size = 20; panel.theme = theme
	var margin := MarginContainer.new()
	for side in ["left","right","top","bottom"]: margin.add_theme_constant_override("margin_"+side,36)
	panel.add_child(margin)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation",20); margin.add_child(box)
	var heading := Label.new(); heading.text = "场景加载失败"; heading.add_theme_font_size_override("font_size",30); box.add_child(heading)
	message = Label.new(); message.custom_minimum_size = Vector2(760,85); message.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; box.add_child(message)
	status = Label.new(); status.custom_minimum_size.y = 28; box.add_child(status)
	var actions := HBoxContainer.new(); actions.add_theme_constant_override("separation",12); box.add_child(actions)
	lobby_button = _button(actions,"重试返回队伍",260,func(): service.recover_navigation(LOBBY))
	menu_button = _button(actions,"返回主菜单",220,func(): service.recover_navigation(MENU))
	save_button = _button(actions,"重试保存结算",250,func(): service.retry_save())
	hint = Label.new()
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	hint.add_theme_font_size_override("font_size",17)
	box.add_child(hint)
	_refresh_bindings()
	service.changed.connect(refresh); service.navigation_requested.connect(_navigation_requested)
	refresh(); lobby_button.grab_focus()

func _refresh_bindings() -> void:
	hint.text = "返回主菜单 %s / %s · %s" % [
		Actions.binding_text("return_menu"),Actions.binding_text("ui_cancel"),Actions.panel_navigation_hint()]

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		_refresh_bindings()

func _button(parent: Node,caption: String,width: int,callback: Callable) -> Button:
	var button := Button.new(); button.text = caption; button.custom_minimum_size = Vector2(width,46)
	button.pressed.connect(callback); parent.add_child(button); return button

func _navigation_requested(_path: String) -> void: refresh.call_deferred()

func refresh() -> void:
	if not is_inside_tree() or is_queued_for_deletion(): return
	var pending: bool = service.navigation_pending()
	message.text = "本次场景切换未完成。可以重试返回队伍入口，或返回主菜单后重新连接。"
	if service.pending_save(): message.text += "\n"+service.pending_save_hint()
	status.text = ("正在返回队伍……" if service._recovery_target == LOBBY else "正在返回主菜单……") if pending and not service._recovery_target.is_empty() else "请选择恢复操作。"
	lobby_button.disabled = pending and service._recovery_target == LOBBY
	menu_button.disabled = pending and service._recovery_target == MENU
	save_button.visible = service.pending_save(); save_button.disabled = service.saving()
	save_button.text = "正在保存结算……" if service.saving() else "重试保存结算"
	var buttons: Array[Button] = []
	for button in [lobby_button,menu_button,save_button]:
		if button.visible and not button.disabled: buttons.append(button)
	for index in range(buttons.size()):
		var next: NodePath = buttons[index].get_path_to(buttons[(index+1)%buttons.size()])
		var previous: NodePath = buttons[index].get_path_to(buttons[(index+buttons.size()-1)%buttons.size()])
		buttons[index].focus_next = next; buttons[index].focus_previous = previous
		buttons[index].focus_neighbor_right = next; buttons[index].focus_neighbor_left = previous
	var focused: Control = get_viewport().gui_get_focus_owner()
	if focused == null or focused not in buttons:
		if not buttons.is_empty(): buttons[0].grab_focus()

func _input(event: InputEvent) -> void:
	if not visible or is_queued_for_deletion(): return
	if event.is_action_pressed("return_menu") or event.is_action_pressed("ui_cancel"):
		if not event.is_echo(): service.recover_navigation(MENU)
		get_viewport().set_input_as_handled(); return
	# GUI navigation/activation continues to the focused button. Other key input
	# is consumed before the disconnected scene can handle gameplay commands.
	for action in ["ui_accept","ui_focus_next","ui_focus_prev","ui_left","ui_right","ui_up","ui_down"]:
		if event.is_action(action): return
	if event is InputEventKey or event is InputEventJoypadButton: get_viewport().set_input_as_handled()
