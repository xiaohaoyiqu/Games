extends Node2D

const Actions = preload("res://game/input/actions.gd")
const Practice = preload("res://game/training/guided_practice.gd")
var room: Node2D
var buttons: Dictionary = {}
var lesson_picker: OptionButton
var view: Dictionary = {}
var text_lines: Array[Dictionary] = []
var panel_rect := Rect2(28,116,520,268)
var rebuilds: int = 0
var _revision: int = -2
var _model_id: int = 0
var _selection: int = -1
var _active: bool = false
var _paused: bool = false

func _style_button(button: Button) -> void:
	button.focus_mode = Control.FOCUS_NONE
	button.clip_text = true
	button.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	button.add_theme_font_override("font",room.ui_font)
	button.add_theme_font_size_override("font_size",16)
	for state in ["normal","hover","pressed","disabled"]:
		var style := StyleBoxFlat.new()
		style.bg_color = Color("223140") if state != "hover" else Color("334453")
		style.border_color = Color("5b6873")
		style.set_border_width_all(1)
		button.add_theme_stylebox_override(state,style)

func _ready() -> void:
	for id: String in ["toggle","retry","next","start"]:
		var button := Button.new()
		_style_button(button)
		add_child(button); buttons[id] = button
	lesson_picker = OptionButton.new()
	_style_button(lesson_picker)
	lesson_picker.fit_to_longest_item = false
	for index in range(Practice.TITLES.size()):
		var available: bool = room.guidance_lesson_available(index)
		var caption: String = "%d  %s" % [index+1,Practice.TITLES[index]]
		lesson_picker.add_item(caption if available else caption+" · 不可用",index)
		lesson_picker.set_item_disabled(index,not available)
	lesson_picker.get_popup().add_theme_font_override("font",room.ui_font)
	lesson_picker.get_popup().add_theme_font_size_override("font_size",16)
	add_child(lesson_picker)
	lesson_picker.item_selected.connect(_select_lesson)
	buttons["toggle"].pressed.connect(room.toggle_guidance)
	buttons["retry"].pressed.connect(room.retry_guidance)
	buttons["next"].pressed.connect(room.next_guidance)
	buttons["start"].pressed.connect(room.start_selected_guidance)
	_refresh()

func _select_lesson(index: int) -> void:
	if index < 0 or index >= lesson_picker.item_count: return
	room.select_guidance_lesson(lesson_picker.get_item_id(index))
	# A stale popup event after focus loss cannot change the room selection.
	lesson_picker.select(room.selected_lesson)

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		_refresh()

func _process(_delta: float) -> void:
	visible = not room.paused
	if room.paused and lesson_picker.get_popup().visible:
		lesson_picker.get_popup().hide()
	var active: bool = room.guidance != null
	var revision: int = room.guidance.revision if active else -1
	var model_id: int = room.guidance.get_instance_id() if active else 0
	if active != _active or revision != _revision or model_id != _model_id or room.selected_lesson != _selection or room.paused != _paused:
		_refresh()

func _line(point: Vector2, value: String, size: int, color: Color) -> void:
	text_lines.append({"point":point,"text":value,"size":size,"color":color})

func _shortcut(button: Button,caption: String,action: String) -> void:
	button.text = caption+" "+Actions.binding_text(action)
	button.tooltip_text = button.text

func _refresh() -> void:
	_active = room.guidance != null
	_revision = room.guidance.revision if _active else -1
	_model_id = room.guidance.get_instance_id() if _active else 0
	_selection = room.selected_lesson
	_paused = room.paused
	rebuilds += 1; text_lines.clear(); view.clear()
	buttons["toggle"].position = Vector2(406,267) if _active else Vector2(28,116)
	buttons["toggle"].size = Vector2(126,36) if _active else Vector2(200,36)
	_shortcut(buttons["toggle"],"自由练习" if _active else "从首项开始","guidance_toggle")
	buttons["toggle"].disabled = _paused
	buttons["retry"].visible = _active; buttons["next"].visible = _active
	lesson_picker.position = Vector2(44,315) if _active else Vector2(244,116)
	lesson_picker.size = Vector2(330,36) if _active else Vector2(304,36)
	lesson_picker.disabled = _paused
	lesson_picker.select(_selection)
	buttons["start"].position = Vector2(390,315) if _active else Vector2(28,160)
	buttons["start"].size = Vector2(142,36) if _active else Vector2(200,36)
	_shortcut(buttons["start"],"开始所选","guidance_start")
	buttons["start"].disabled = _paused
	if _active:
		view = room.guidance.describe()
		_line(Vector2(44,148),view["title"],20,Color("e2d4b7"))
		_line(Vector2(44,181),view["instructions"][0],16,Color("ccd3dd"))
		_line(Vector2(44,207),view["instructions"][1],16,Color("a9b6c9"))
		_line(Vector2(44,242),view["status"],14,Color("a9d9bd") if view["completed"] else Color("dfc594"))
		buttons["retry"].position = Vector2(44,267)
		buttons["retry"].size = Vector2(140,36)
		_shortcut(buttons["retry"],"重试本项","reset_room")
		buttons["retry"].disabled = _paused
		buttons["next"].position = Vector2(200,267)
		buttons["next"].size = Vector2(190,36)
		_shortcut(buttons["next"],"结束引导" if view["lesson"] >= Practice.LAST_LESSON else "下一项","interact")
		buttons["next"].disabled = _paused or not view["completed"]
	var selection_hint := "%s / %s 选课 · %s" % [Actions.binding_text("guidance_previous"),Actions.binding_text("guidance_next"),buttons["start"].text]
	lesson_picker.tooltip_text = "选课后，点击「开始所选」进入项目。\n"+selection_hint
	if room.ui_font.get_string_size(selection_hint,HORIZONTAL_ALIGNMENT_LEFT,-1,13).x > 480:
		selection_hint = "在列表中选课，再点击「开始所选」。"
	_line(Vector2(44,372) if _active else Vector2(244,183),selection_hint,13,Color("a9b6c9"))
	queue_redraw()

func _draw() -> void:
	if _active:
		draw_rect(panel_rect,Color(0.035,0.046,0.071,0.96)); draw_rect(panel_rect,Color("62576a"),false,1)
	for line: Dictionary in text_lines:
		draw_string(room.ui_font,line["point"],line["text"],HORIZONTAL_ALIGNMENT_LEFT,-1,line["size"],line["color"])
