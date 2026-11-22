extends CanvasLayer

# Local presentation only. No profile I/O, transport or deferred scene changes.
const MAIN: String = "res://game/main/main_menu.tscn"
const TRAINING: String = "res://game/main/training_room.tscn"
var _session: WeakRef
var _source: WeakRef
var _epoch: int
var _pending: bool = false
var _reason: String
var _allow_training: bool
var _panel: PanelContainer
var reason_label: Label
var status: Label
var menu_button: Button
var training_button: Button
var exit_button: Button
var _buttons: Array[Button] = []

func _init(session: Node,source: Node,reason: String="",allow_training: bool=true) -> void:
	_reason = reason; _allow_training = allow_training
	_session = weakref(session); _source = weakref(source); _epoch = session.scene_epoch()
	layer = 100; process_mode = Node.PROCESS_MODE_ALWAYS

func _ready() -> void:
	set_process(false); set_physics_process(false)
	var canvas := Control.new(); canvas.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var theme := Theme.new(); theme.default_font = preload("res://game/ui/ui_font.gd").acquire(); theme.default_font_size = 20
	canvas.theme = theme; add_child(canvas)
	var shade := ColorRect.new(); shade.color = Color("101722"); shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	canvas.add_child(shade)
	var margin := MarginContainer.new(); margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side: String in ["left","right","top","bottom"]: margin.add_theme_constant_override("margin_"+side,24)
	canvas.add_child(margin)
	var scroll := ScrollContainer.new(); scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED; scroll.follow_focus = true
	margin.add_child(scroll)
	var center := CenterContainer.new(); center.size_flags_horizontal = Control.SIZE_EXPAND_FILL; center.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.add_child(center)
	_panel = PanelContainer.new(); center.add_child(_panel)
	var style := StyleBoxFlat.new(); style.bg_color = Color("1b2734"); style.border_color = Color("d2bb89"); style.set_border_width_all(2)
	_panel.add_theme_stylebox_override("panel",style)
	var inset := MarginContainer.new()
	for side: String in ["left","right","top","bottom"]: inset.add_theme_constant_override("margin_"+side,24)
	_panel.add_child(inset)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation",18); inset.add_child(box)
	var title := _label(box,"当前场景暂时无法进入",30); title.add_theme_color_override("font_color",Color("efd9aa"))
	reason_label = _label(box,_reason if not _reason.is_empty() else "装备或委托配置不可用。请修复游戏文件后重新启动；也可返回主菜单或使用训练房。",20)
	status = _label(box,"角色档案保留；这里不会重置进度。",18)
	menu_button = _button(box,"返回主菜单",_navigate.bind(MAIN))
	if _allow_training: training_button = _button(box,"进入训练房",_navigate.bind(TRAINING))
	exit_button = _button(box,"退出游戏",_quit)
	for i in range(_buttons.size()):
		var button: Button = _buttons[i]
		button.focus_previous = button.get_path_to(_buttons[(i+_buttons.size()-1)%_buttons.size()])
		button.focus_next = button.get_path_to(_buttons[(i+1)%_buttons.size()])
		button.focus_neighbor_top = button.focus_previous; button.focus_neighbor_bottom = button.focus_next
	get_viewport().size_changed.connect(_layout)
	_layout(); menu_button.grab_focus()

func _label(parent: Node,text: String,size: int) -> Label:
	var label := Label.new(); label.text = text; label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	label.add_theme_font_size_override("font_size",size); parent.add_child(label); return label
func _button(parent: Node,text: String,action: Callable) -> Button:
	var button := Button.new(); button.text = text; button.custom_minimum_size.y = 48
	button.pressed.connect(action); parent.add_child(button); _buttons.append(button); return button
func _layout() -> void:
	_panel.custom_minimum_size.x = maxf(0,minf(620,get_viewport().get_visible_rect().size.x-48))
func _live(epoch: int) -> bool:
	var session: Node = _session.get_ref()
	var source: Node = _source.get_ref()
	return (is_instance_valid(session) and session.is_inside_tree() and is_inside_tree()
		and is_instance_valid(source) and get_parent() == source and get_tree().current_scene == source
		and not is_queued_for_deletion() and not source.is_queued_for_deletion() and session.scene_epoch() == epoch)
func _enable(active: bool) -> void:
	for button: Button in _buttons: button.disabled = not active
func _navigate(path: String) -> void:
	if path not in [MAIN,TRAINING] or (path==TRAINING and not _allow_training) or _pending: return
	if not _live(_epoch): status.text = "这个入口已失效，请使用当前页面。"; _enable(false); return
	var session: Node = _session.get_ref(); var before: int = _epoch
	_pending = true; _enable(false); status.text = "正在切换，请稍候……"
	var button: Button = menu_button if path == MAIN else training_button
	var result: Error = session.change_local_scene(path,_show_route_failure.bind(before+1,button))
	if result != OK and session.scene_epoch() in [before,before+1]:
		_show_route_failure(str(session.last_message),session.scene_epoch(),button)

func _show_route_failure(message: String,epoch: int,button: Button) -> bool:
	if not _pending or not _live(epoch): return false
	_epoch = epoch
	_pending = false
	_enable(true)
	status.text = message.left(256) if not message.is_empty() else "暂时无法切换，请重试或退出游戏。"
	button.grab_focus()
	return true
func _quit() -> void:
	if _pending or not _live(_epoch): return
	var session: Node=_session.get_ref()
	session.request_quit()
