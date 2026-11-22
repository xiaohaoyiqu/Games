extends Node2D

const Actions = preload("res://game/input/actions.gd")
const ControlGuide = preload("res://game/ui/control_guide.gd")
var control_guide: AcceptDialog
var help_button: Button
var _guide_focus: WeakRef
var settings_button: Button
var display_settings: AcceptDialog
var solo_recovery_dialog: ConfirmationDialog
var _recovery_save_id: String = ""
var _settings_focus: WeakRef

var ui_font: Font
var elapsed: float = 0
var _gate_lights: Node2D
var start_button: Button
var training_button: Button
var party_button: Button
var _route_failure: String = ""
var build_caption: String
var menu_buttons: Array[Button] = []
var footer_labels: Array[Label] = []
var _navigation_hint: String


func _ready() -> void:
	Session.solo_save_finished.connect(_solo_recovery_finished)
	Actions.setup()
	_gate_lights = Node2D.new()
	_gate_lights.name = "GateLights"
	_gate_lights.draw.connect(_draw_gate_lights)
	add_child(_gate_lights)
	ui_font = preload("res://game/ui/ui_font.gd").acquire()
	build_caption = str(ProjectSettings.get_setting("application/config/version"))+" · 单人冒险 / 局域网组队"
	var start_caption: String = "查看上次单人结算" if Session.has_recovered_solo_settlement() else "进入石桥镇"
	start_button = _button(start_caption,Vector2(105,325),_start_run)
	training_button = _button("训练房",Vector2(105,395),func(): _enter_from_menu(Session.TRAINING_SCENE,training_button))
	party_button = _button("组队冒险",Vector2(105,465),func(): _enter_from_menu("res://game/main/party_lobby.tscn",party_button))
	_button("退出游戏",Vector2(105,535),func(): Session.request_quit())
	help_button = _button("操作说明",Vector2(285,535),_open_control_guide)
	settings_button=_button("视听设置",Vector2(465,325),_open_display_settings)
	control_guide = ControlGuide.new()
	control_guide.ui_font = ui_font
	add_child(control_guide)
	control_guide.training_requested.connect(_training_from_guide)
	control_guide.visibility_changed.connect(_guide_visibility_changed)
	display_settings=preload("res://game/ui/display_settings.gd").new()
	display_settings.ui_font=ui_font;display_settings.preferences=Session.display_preferences;display_settings.audio=Session.sound_cues
	display_settings.audio_preferences=Session.audio_preferences
	add_child(display_settings)
	display_settings.visibility_changed.connect(_settings_visibility_changed)
	_footer("NavigationHint","",16,Color("939eac"))
	_footer("ControlsHint","",15,Color("748394"))
	_footer("BuildCaption",build_caption,13,Color("657588"))
	_refresh_bindings()
	get_viewport().size_changed.connect(_layout_menu)
	_layout_menu(); _layout_menu.call_deferred()
	var configured: bool = Session.configuration_ready()
	start_button.disabled = not configured
	menu_buttons[2].disabled = not configured or Session.has_recovered_solo_settlement()
	_layout_menu()
	(start_button if configured else menu_buttons[1]).grab_focus()
	if "--verify-dungeon" in OS.get_cmdline_user_args() or "--verify-town" in OS.get_cmdline_user_args():
		call_deferred("_verify_start")


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready(): _refresh_bindings()


func _refresh_bindings() -> void:
	_navigation_hint = "%s 选择 · %s 确定 · 鼠标点击" % [Actions.binding_text("ui_focus_next"),Actions.binding_text("ui_accept")]
	footer_labels[0].text = Session.menu_status_hint(_navigation_hint)
	footer_labels[1].text = Actions.combat_hint() if _route_failure.is_empty() else _route_failure
	help_button.tooltip_text = "查看当前按键与练习说明 · "+Actions.binding_text("controls_help")
	settings_button.tooltip_text = "屏幕震动保存、音效音量／保存与试听 · "+Actions.binding_text("display_settings")


func _exit_tree() -> void:
	if is_instance_valid(Session.sound_cues):Session.sound_cues.stop_scene(self)


func _unhandled_input(event: InputEvent) -> void:
	if event.is_echo(): return
	if event.is_action_pressed("display_settings"):
		_open_display_settings();get_viewport().set_input_as_handled();return
	if event.is_action_pressed("controls_help"):
		_open_control_guide()
		get_viewport().set_input_as_handled()


func _dialog_open() -> bool:
	return control_guide.visible or display_settings.visible or (is_instance_valid(solo_recovery_dialog) and solo_recovery_dialog.visible)


func _open_control_guide() -> bool:
	if not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene!=self:return false
	if _dialog_open() or Session.local_scene_pending(): return false
	var focused: Control = get_viewport().gui_get_focus_owner()
	_guide_focus = weakref(focused) if focused != null else null
	control_guide.refresh_bindings()
	control_guide.popup_centered_clamped(Vector2i(820,560),0.9)
	control_guide.focus_contents.call_deferred()
	return true


func _open_display_settings() -> bool:
	if not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene!=self:return false
	if _dialog_open() or Session.local_scene_pending(): return false
	var focused: Control=get_viewport().gui_get_focus_owner()
	_settings_focus=weakref(focused) if focused!=null else null
	display_settings.refresh();display_settings.popup_centered_clamped(Vector2i(680,520),0.9)
	display_settings.focus_contents.call_deferred()
	return true


func _settings_visibility_changed() -> void:
	if display_settings.visible or not is_inside_tree() or is_queued_for_deletion():return
	var previous: WeakRef=_settings_focus;_settings_focus=null
	_restore_dialog_focus.call_deferred(previous,settings_button)

func _guide_visibility_changed() -> void:
	if control_guide.visible or not is_inside_tree() or is_queued_for_deletion():return
	var previous: WeakRef=_guide_focus;_guide_focus=null
	_restore_dialog_focus.call_deferred(previous,help_button)

func _restore_dialog_focus(previous: WeakRef,fallback: Button) -> void:
	if not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene!=self:return
	if _dialog_open():return
	var control: Control=previous.get_ref() as Control if previous!=null else null
	if is_instance_valid(control) and control.is_inside_tree() and not control.is_queued_for_deletion() and not (control is BaseButton and control.disabled):control.grab_focus()
	elif is_instance_valid(fallback) and not fallback.disabled:fallback.grab_focus()


func _training_from_guide() -> void:
	if not control_guide.visible or not is_inside_tree() or get_tree().current_scene != self: return
	_guide_focus = weakref(training_button)
	control_guide.hide()
	_enter_from_menu(Session.TRAINING_SCENE,training_button)


func _enter_from_menu(path: String,button: Button) -> void:
	if not is_inside_tree() or is_queued_for_deletion() or get_tree().current_scene != self: return
	if button.disabled or _dialog_open(): return
	if not _route_failure.is_empty():
		_route_failure = ""
		footer_labels[1].text = Actions.combat_hint()
	var result: Error = Session.change_local_scene(path,_show_route_failure.bind(button))
	if result != OK:
		_show_route_failure(Session.last_message,button)


func _show_route_failure(message: String,button: Button) -> bool:
	_route_failure = "%s · %s" % [button.text,message]
	footer_labels[1].text = _route_failure
	if not _dialog_open() and not button.disabled: button.grab_focus()
	return true


func _verify_start() -> void:
	if DisplayServer.get_name() != "headless":
		await get_tree().process_frame
		await RenderingServer.frame_post_draw
		var output := OS.get_environment("DUNGEON_VERIFY_DIR")
		if not output.is_empty():
			DirAccess.make_dir_recursive_absolute(output)
			get_viewport().get_texture().get_image().save_png(output.path_join("main-menu.png"))
	# Use the same button signal as a mouse click or keyboard activation.
	start_button.pressed.emit()


func _start_run() -> void:
	if not is_inside_tree() or get_tree().current_scene != self or _dialog_open() or Session.local_scene_pending() or Session.solo_save_pending(): return
	if Session.has_recovered_solo_settlement():
		_open_solo_recovery()
	else:
		_enter_from_menu(Session.TOWN_SCENE,start_button)


func _open_solo_recovery() -> void:
	var view: Dictionary = Session.recovered_solo_preview()
	if view.is_empty(): return
	if not is_instance_valid(solo_recovery_dialog):
		solo_recovery_dialog = load("res://game/ui/solo_recovery_dialog.gd").new()
		solo_recovery_dialog.ui_font = ui_font
		add_child(solo_recovery_dialog)
		solo_recovery_dialog.confirmed.connect(_confirm_solo_recovery)
		solo_recovery_dialog.visibility_changed.connect(_solo_recovery_visibility_changed)
	solo_recovery_dialog.show_review(view)


func _confirm_solo_recovery() -> void:
	if not is_inside_tree() or get_tree().current_scene != self or not is_instance_valid(solo_recovery_dialog) or not solo_recovery_dialog.visible: return
	var reviewed_id: String = solo_recovery_dialog.reviewed_id
	if reviewed_id.is_empty() or not _recovery_save_id.is_empty(): return
	var result: Dictionary = Session.retry_recovered_solo_settlement(reviewed_id,true)
	if result.get("pending",false):
		_recovery_save_id = reviewed_id
		start_button.disabled = true
		_layout_menu()
		solo_recovery_dialog.show_saving()
	else:
		_apply_solo_recovery_result(result)


func _solo_recovery_finished(identifier: String,result: Dictionary) -> void:
	if identifier != _recovery_save_id: return
	_recovery_save_id = ""
	_apply_solo_recovery_result(result)


func _apply_solo_recovery_result(result: Dictionary) -> void:
	start_button.disabled = not Session.configuration_ready()
	if result["ok"]:
		start_button.text = "进入石桥镇"
		menu_buttons[2].disabled = not Session.configuration_ready()
		if solo_recovery_dialog.visible: solo_recovery_dialog.hide()
	elif solo_recovery_dialog.visible:
		solo_recovery_dialog.show_failure(str(result["message"]))
	_layout_menu()
	if not solo_recovery_dialog.visible: _restore_dialog_focus.call_deferred(null,start_button)


func _solo_recovery_visibility_changed() -> void:
	if not solo_recovery_dialog.visible:
		_restore_dialog_focus.call_deferred(null,start_button)


func _button(caption: String,point: Vector2,callback: Callable) -> Button:
	var button := Button.new()
	button.text = caption
	button.position = point
	button.size = Vector2(340,60)
	button.add_theme_font_override("font",ui_font)
	button.add_theme_font_size_override("font_size",22)
	for state in ["normal","hover","pressed","focus"]:
		var style := StyleBoxFlat.new()
		style.bg_color = Color("273342") if state != "normal" else Color("172231")
		style.border_color = Color("d2bb89") if state == "focus" else Color("6e6b73")
		style.set_border_width_all(2 if state == "focus" else 1)
		style.corner_radius_top_left = 3
		style.corner_radius_bottom_right = 3
		button.add_theme_stylebox_override(state,style)
	button.pressed.connect(callback)
	add_child(button)
	menu_buttons.append(button)
	button.minimum_size_changed.connect(_layout_menu)
	return button


func _process(delta: float) -> void:
	var hint: String = Session.menu_status_hint(_navigation_hint)
	if footer_labels.size()==3 and footer_labels[0].text!=hint: footer_labels[0].text=hint
	elapsed += delta
	_gate_lights.queue_redraw()


func _draw() -> void:
	draw_rect(Rect2(0,0,1280,720),Color("101722"))
	for row in range(10):
		for column in range(12):
			var x := 560+column*75-(35 if row%2 else 0)
			draw_rect(Rect2(x,row*55,72,52),Color("1b2533"))
	draw_circle(Vector2(938,268),157,Color("464553"))
	draw_rect(Rect2(781,268,314,317),Color("464553"))
	draw_circle(Vector2(938,272),131,Color("10232c"))
	draw_rect(Rect2(807,272,262,313),Color("10232c"))
	for i in range(9):
		var inset := i*11
		draw_rect(Rect2(815+inset,274+inset,246-inset*2,307-inset*2),Color(0.28,0.63,0.61,0.16),false,2)
	for x in [713,1160]:
		draw_rect(Rect2(x-22,203,44,375),Color("3c4354"))
		draw_rect(Rect2(x-33,194,66,18),Color("666472"))
		draw_rect(Rect2(x-33,573,66,20),Color("666472"))
	draw_colored_polygon(PackedVector2Array([Vector2(781,586),Vector2(1095,586),Vector2(1252,720),Vector2(625,720)]),Color("283640"))
	for step in range(4):
		draw_line(Vector2(779-step*30,589+step*29),Vector2(1097+step*30,589+step*29),Color("4d535e"),3)
	draw_string(ui_font,Vector2(103,148),"DUNGEON CHRONICLE",HORIZONTAL_ALIGNMENT_LEFT,-1,22,Color("bcae93"))
	draw_string(ui_font,Vector2(99,216),"地下城纪行",HORIZONTAL_ALIGNMENT_LEFT,-1,52,Color("eddfc2"))
	draw_string(ui_font,Vector2(105,266),"石桥镇 · 回响遗迹",HORIZONTAL_ALIGNMENT_LEFT,-1,25,Color("aab8c2"))
	draw_string(ui_font,Vector2(106,298),"接受委托，探索遗迹，带着战利品回城。",HORIZONTAL_ALIGNMENT_LEFT,-1,17,Color("8794a5"))


func _draw_gate_lights() -> void:
	for i in range(16):
		var x := 830.0+fmod(i*27.0+sin(elapsed+i)*12.0,220)
		var y := 580.0-fmod(elapsed*18+i*41.0,330)
		_gate_lights.draw_circle(Vector2(x,y),1.5,Color(0.64,0.85,0.76,0.5))


func _footer(identifier: String,text: String,font_size: int,color: Color) -> void:
	var label := Label.new(); label.name = identifier; label.text = text
	label.clip_text = true
	label.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	label.add_theme_font_override("font",ui_font)
	label.add_theme_font_size_override("font_size",font_size)
	label.add_theme_color_override("font_color",color)
	add_child(label); footer_labels.append(label)
	label.minimum_size_changed.connect(_layout_menu)


func _layout_menu() -> void:
	if not is_inside_tree(): return
	if menu_buttons.size() != 6 or footer_labels.size() != 3 or settings_button==null: return
	var heights: Array[float] = []
	var total: float = 30+14+10+14
	for index in range(3):
		heights.append(maxf(60,menu_buttons[index].get_combined_minimum_size().y))
	heights[0]=maxf(heights[0],settings_button.get_combined_minimum_size().y)
	heights.append(maxf(60,maxf(menu_buttons[3].get_combined_minimum_size().y,help_button.get_combined_minimum_size().y)))
	for height in heights: total += height
	for label in footer_labels: total += label.get_combined_minimum_size().y
	var y: float = minf(325,get_viewport_rect().size.y-16-total)
	for index in range(3):
		menu_buttons[index].position = Vector2(105,y); menu_buttons[index].size = Vector2(340,heights[index])
		if index==0:settings_button.position=Vector2(465,y);settings_button.size=Vector2(160,heights[0])
		y += heights[index]+10
	menu_buttons[3].position = Vector2(105,y); menu_buttons[3].size = Vector2(160,heights[3])
	help_button.position = Vector2(285,y); help_button.size = Vector2(160,heights[3])
	y += heights[3]+14
	var focusable: Array[Button] = []
	for button: Button in [start_button,settings_button,menu_buttons[1],menu_buttons[2],menu_buttons[3],help_button]:
		button.focus_mode = Control.FOCUS_NONE if button.disabled else Control.FOCUS_ALL
		if not button.disabled: focusable.append(button)
	for index in range(focusable.size()):
		var button: Button = focusable[index]
		var previous: Button = focusable[(index+focusable.size()-1)%focusable.size()]
		var next: Button = focusable[(index+1)%focusable.size()]
		button.focus_previous = button.get_path_to(previous); button.focus_next = button.get_path_to(next)
		button.focus_neighbor_top = button.get_path_to(previous); button.focus_neighbor_bottom = button.get_path_to(next)
	var upper: Button = menu_buttons[2] if not menu_buttons[2].disabled else menu_buttons[1]
	var first: Button = start_button if not start_button.disabled else menu_buttons[1]
	menu_buttons[3].focus_neighbor_right = menu_buttons[3].get_path_to(help_button)
	help_button.focus_neighbor_left = help_button.get_path_to(menu_buttons[3])
	for button: Button in [menu_buttons[3],help_button]:
		button.focus_neighbor_top = button.get_path_to(upper)
		button.focus_neighbor_bottom = button.get_path_to(first)
	start_button.focus_neighbor_right=start_button.get_path_to(settings_button)
	start_button.focus_neighbor_bottom=start_button.get_path_to(menu_buttons[1])
	start_button.focus_neighbor_top=start_button.get_path_to(menu_buttons[3])
	settings_button.focus_neighbor_left=settings_button.get_path_to(first)
	settings_button.focus_neighbor_top=settings_button.get_path_to(help_button)
	settings_button.focus_neighbor_bottom=settings_button.get_path_to(menu_buttons[1])
	menu_buttons[1].focus_neighbor_top=menu_buttons[1].get_path_to(start_button if not start_button.disabled else settings_button)
	help_button.focus_neighbor_bottom=help_button.get_path_to(settings_button)
	for index in range(footer_labels.size()):
		var label: Label = footer_labels[index]
		label.position = Vector2(106,y)
		label.size = Vector2(maxf(1,get_viewport_rect().size.x-122),label.get_combined_minimum_size().y)
		y += label.size.y+(10 if index==0 else (14 if index==1 else 0))
