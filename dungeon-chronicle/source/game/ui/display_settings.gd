extends AcceptDialog

const Actions = preload("res://game/input/actions.gd")

var ui_font: Font
var preferences: RefCounted
var audio: Node
var audio_preferences: RefCounted
var sound_status: Label
var sound_save: Button
var sound_reload: Button
var sound_toggle: CheckButton
var sound_level: HSlider
var sound_label: Label
var preview_button: Button
var scroll: ScrollContainer
var shake_toggle: CheckButton
var status_label: Label
var save_button: Button
var reload_button: Button
var refreshes: int=0

func _ready() -> void:
	title="视听设置";visible=false;exclusive=true;transient=true
	min_size=Vector2i(560,410);ok_button_text="关闭";get_label().hide()
	scroll=ScrollContainer.new();scroll.custom_minimum_size=Vector2(520,320)
	scroll.horizontal_scroll_mode=ScrollContainer.SCROLL_MODE_DISABLED;scroll.follow_focus=true;add_child(scroll)
	var content:=VBoxContainer.new();content.size_flags_horizontal=Control.SIZE_EXPAND_FILL
	content.add_theme_constant_override("separation",12);scroll.add_child(content)
	shake_toggle=CheckButton.new();shake_toggle.text="战斗屏幕震动"
	shake_toggle.add_theme_font_override("font",ui_font);shake_toggle.add_theme_font_size_override("font_size",20)
	content.add_child(shake_toggle)
	var description:=Label.new()
	description.text="训练、单人和组队本机共用。更改立即生效并尝试保存；保存失败时，本次选择仍然有效。"
	description.autowrap_mode=TextServer.AUTOWRAP_WORD_SMART;description.custom_minimum_size=Vector2(480,55)
	description.add_theme_font_override("font",ui_font);description.add_theme_font_size_override("font_size",17);content.add_child(description)
	status_label=Label.new();status_label.autowrap_mode=TextServer.AUTOWRAP_WORD_SMART;status_label.custom_minimum_size=Vector2(480,80)
	status_label.add_theme_font_override("font",ui_font);status_label.add_theme_font_size_override("font_size",17)
	content.add_child(status_label)
	sound_toggle=CheckButton.new();sound_toggle.text="音效"
	sound_toggle.add_theme_font_override("font",ui_font);sound_toggle.add_theme_font_size_override("font_size",20);content.add_child(sound_toggle)
	sound_label=Label.new();sound_label.autowrap_mode=TextServer.AUTOWRAP_WORD_SMART;sound_label.custom_minimum_size=Vector2(480,42);sound_label.add_theme_font_override("font",ui_font);sound_label.add_theme_font_size_override("font_size",17);content.add_child(sound_label)
	sound_level=HSlider.new();sound_level.min_value=0;sound_level.max_value=100;sound_level.step=1
	sound_level.custom_minimum_size=Vector2(480,30);sound_level.focus_mode=Control.FOCUS_ALL;content.add_child(sound_level)
	preview_button=Button.new();preview_button.text="试听命中提示"
	preview_button.add_theme_font_override("font",ui_font);preview_button.add_theme_font_size_override("font_size",17);content.add_child(preview_button)
	sound_toggle.toggled.connect(_sound_toggle);sound_level.value_changed.connect(_sound_volume);preview_button.pressed.connect(_preview)
	# Preferences refresh the dialog synchronously. Recheck playback after both fields have been applied.
	if is_instance_valid(audio): audio.changed.connect(_on_playback_changed,CONNECT_DEFERRED)
	var sound_description:=Label.new()
	sound_description.text="音效更改立即生效。要在下次启动沿用，请点击“保存音效”。"
	sound_description.autowrap_mode=TextServer.AUTOWRAP_WORD_SMART;sound_description.custom_minimum_size=Vector2(480,55)
	sound_description.add_theme_font_override("font",ui_font);sound_description.add_theme_font_size_override("font_size",17);content.add_child(sound_description)
	sound_status=Label.new();sound_status.autowrap_mode=TextServer.AUTOWRAP_WORD_SMART;sound_status.custom_minimum_size=Vector2(480,80)
	sound_status.add_theme_font_override("font",ui_font);sound_status.add_theme_font_size_override("font_size",17);content.add_child(sound_status)
	var sound_actions:=HBoxContainer.new();sound_actions.add_theme_constant_override("separation",12);content.add_child(sound_actions)
	sound_save=Button.new();sound_save.text="保存音效"
	sound_reload=Button.new();sound_reload.text="读取已保存音效"
	sound_reload.tooltip_text="明确用音效文件替换待保存选择；缺少文件时读取默认值。"
	for button: Button in [sound_save,sound_reload]:
		button.size_flags_horizontal=Control.SIZE_EXPAND_FILL;button.custom_minimum_size.y=36
		button.add_theme_font_override("font",ui_font);button.add_theme_font_size_override("font_size",17);sound_actions.add_child(button)
	sound_save.pressed.connect(_save_audio);sound_reload.pressed.connect(_reload_audio)
	if is_instance_valid(audio_preferences):audio_preferences.changed.connect(_on_changed)
	save_button=add_button("重试保存震动",false,"save")
	reload_button=add_button("读取已保存震动",false,"reload")
	reload_button.tooltip_text="明确用文件中的设置替换本次选择；缺少文件时读取默认值。"
	for button: Button in [save_button,reload_button,get_ok_button()]:
		button.add_theme_font_override("font",ui_font);button.add_theme_font_size_override("font_size",17)
	shake_toggle.toggled.connect(_toggle)
	custom_action.connect(_action)
	preferences.changed.connect(_on_changed)
	refresh()

func _active() -> bool:
	return visible and is_inside_tree() and not is_queued_for_deletion() and get_parent()==get_tree().current_scene
func _on_changed() -> void:
	if _active(): _refresh_controls()

func _on_playback_changed() -> void:
	if _active() and preview_button.disabled != _preview_unavailable():
		_refresh_controls()

func _preview_unavailable() -> bool:
	return not is_instance_valid(audio) or not is_instance_valid(audio_preferences) or not audio.enabled() or audio.level() <= 0
func _toggle(value: bool) -> void:
	if _active():preferences.set_shake(value)
func _sound_toggle(value: bool) -> void:
	if _active() and is_instance_valid(audio_preferences):audio_preferences.set_enabled(value)
func _sound_volume(value: float) -> void:
	if _active() and is_instance_valid(audio_preferences):audio_preferences.set_volume(value)
func _save_audio() -> void:
	if _active() and is_instance_valid(audio_preferences):audio_preferences.save()
func _reload_audio() -> void:
	if _active() and is_instance_valid(audio_preferences):audio_preferences.reload()
func _preview() -> void:
	if _active() and is_instance_valid(audio):audio.play_scene("impact",get_parent())
func _action(id: StringName) -> void:
	if not _active():return
	if id==&"save":preferences.save()
	elif id==&"reload":preferences.reload()
func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		_refresh_shortcut()

func _refresh_shortcut() -> void:
	sound_toggle.text = "音效（%s）" % Actions.binding_text("toggle_sound")

func refresh() -> void:
	_refresh_shortcut()
	_refresh_controls()

func _refresh_controls() -> void:
	shake_toggle.set_pressed_no_signal(preferences.screen_shake())
	status_label.text=preferences.status_text();save_button.disabled=not preferences.can_save();refreshes+=1
	var ready_audio: bool=is_instance_valid(audio) and is_instance_valid(audio_preferences)
	sound_toggle.disabled=not ready_audio;sound_level.editable=ready_audio
	sound_toggle.set_pressed_no_signal(ready_audio and audio_preferences.enabled())
	sound_level.set_value_no_signal(audio_preferences.volume_percent() if ready_audio else 0)
	sound_label.text="音效音量 %d%%" % audio_preferences.volume_percent() if ready_audio else "音效暂不可用"
	sound_status.text=audio_preferences.status_text() if ready_audio else "音效设置暂不可用"
	sound_save.disabled=not ready_audio or not audio_preferences.can_save();sound_reload.disabled=not ready_audio
	preview_button.disabled = _preview_unavailable()
	var order: Array[Control]=[shake_toggle]
	if ready_audio:order.append(sound_toggle);order.append(sound_level)
	if not preview_button.disabled:order.append(preview_button)
	if not sound_save.disabled:order.append(sound_save)
	if ready_audio:order.append(sound_reload)
	if not save_button.disabled:order.append(save_button)
	order.append(reload_button);order.append(get_ok_button())
	for index in range(order.size()):
		var control: Control=order[index]
		control.focus_next=control.get_path_to(order[(index+1)%order.size()])
		control.focus_previous=control.get_path_to(order[(index+order.size()-1)%order.size()])
	if visible:
		var focused: Control=get_viewport().gui_get_focus_owner()
		if focused==sound_save and sound_save.disabled:sound_reload.grab_focus()
		elif focused==save_button and save_button.disabled:reload_button.grab_focus()
		elif focused==preview_button and preview_button.disabled:sound_toggle.grab_focus()
func focus_contents() -> void:
	if visible:shake_toggle.grab_focus()
func _unhandled_key_input(event: InputEvent) -> void:
	if _active() and not event.is_echo() and event.is_action_pressed("toggle_sound") and is_instance_valid(audio_preferences):
		audio_preferences.set_enabled(not audio_preferences.enabled());set_input_as_handled();return
	if visible and not event.is_echo() and event.is_action_pressed("ui_cancel"):
		close_requested.emit();hide.call_deferred();set_input_as_handled()
