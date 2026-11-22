extends Node2D

const Practice = preload("res://game/training/guided_practice.gd")
const Actions = preload("res://game/input/actions.gd")
var practice_button: Button
var host: Node2D
var view: Dictionary = {}
var rebuilds: int = 0
var text_lines: Array[Dictionary] = []
var panel_rect := Rect2(924,177,328,408)
var _model_id: int = 0
var _revision: int = -1
var _state: int = -1
var _damage: int = -1

func _ready() -> void:
	visible = false
	practice_button = Button.new()
	practice_button.position = panel_rect.position + Vector2(14,372)
	practice_button.size = Vector2(panel_rect.size.x-28,32)
	practice_button.focus_mode = Control.FOCUS_NONE
	practice_button.clip_text = true
	practice_button.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	practice_button.add_theme_font_override("font",host.ui_font)
	practice_button.add_theme_font_size_override("font_size",14)
	practice_button.visible = false
	practice_button.pressed.connect(host.open_review_practice)
	add_child(practice_button)

func _process(_delta: float) -> void:
	visible = host.run.state in [2,3] and host.battle_review != null
	if not visible: return
	if _model_id != host.battle_review.get_instance_id() or _revision != host.battle_review.revision or _state != host.run.state or _damage != host.run.damage_taken:
		_model_id = host.battle_review.get_instance_id(); _revision = host.battle_review.revision; _state = host.run.state; _damage = host.run.damage_taken
		view = host.battle_review.describe(_damage,_state == 3)
		rebuilds += 1
		_layout()
	if practice_button.visible:
		_refresh_practice_access()


func _refresh_practice_access() -> void:
	var pending: bool = Session.local_scene_pending()
	var disabled: bool = not host.rewards_saved or pending
	var tip: String = "进入对应的训练项目，完成后可返回出征选择。"
	if pending: tip = "正在切换场景。"
	elif host.settlement_saving: tip = "正在保存本局结算，请稍候。"
	elif disabled: tip = "保存本局结算后可开始练习。" if host.settlement_note.is_empty() else "请先重试保存本局结算。"
	if practice_button.disabled != disabled: practice_button.disabled = disabled
	if practice_button.tooltip_text != tip: practice_button.tooltip_text = tip


func _line(y: float, value: String, size: int=14, color: Color=Color("b8bfca")) -> void:
	text_lines.append({"point":panel_rect.position+Vector2(14,y),"text":value,"size":size,"color":color})

func _layout() -> void:
	text_lines.clear()
	_line(28,"受击回顾",20,Color("e2d4b7"))
	_line(54,"本局受伤 %d · 已记录 %d次" % [view["actual_damage"],view["hits"]])
	_line(77,"扣血口径 · 护甲后，溢出不计" if view["complete"] else "记录有缺项 · 未归因伤害 %d" % view["unattributed_damage"],13,Color("a9d9bd") if view["complete"] else Color("e0a280"))
	if view["last"].is_empty():
		_line(116,"没有记录到敌人命中。")
		_line(142,"这不代表所有伤害来源均已记录。",13)
	else:
		_line(106,"最后一击" if view["fatal"] else "最近一次受击",14,Color("dfc594"))
		_line(130,"%s · 扣血 %d" % [view["last"]["source"],view["last"]["damage"]])
		_line(151,view["last"]["label"],14)
	_line(182,"最近%d次（从新到旧）" % view["recent"].size(),13,Color("dfc594"))
	for i in range(view["recent"].size()):
		var entry: Dictionary = view["recent"][i]
		var seconds: int = int(entry["time"])
		_line(207+i*41,"%02d:%02d  %s  -%d" % [seconds/60,seconds%60,entry["source"],entry["damage"]],14)
		_line(225+i*41,"第%d间 · %s" % [entry["room"],entry["label"]],13)
	_line(338,view["hints"][0],13,Color("b8d0bd"))
	_line(358,view["hints"][1],13,Color("b8d0bd"))
	var lesson: int = int(view["practice_lesson"])
	practice_button.visible = Practice.valid_lesson(lesson)
	panel_rect.size.y = 432 if practice_button.visible else 408
	if practice_button.visible:
		practice_button.text = "练习：" + Practice.TITLES[lesson] + "  " + Actions.binding_text("guidance_start")
	_line(420 if practice_button.visible else 391,"仅本局记录 · 离开后清除",12)
	queue_redraw()

func _draw() -> void:
	if view.is_empty(): return
	draw_rect(panel_rect,Color("161f2e"))
	draw_rect(panel_rect,Color("62576a"),false,1)
	for line: Dictionary in text_lines:
		draw_string(host.ui_font,line["point"],line["text"],HORIZONTAL_ALIGNMENT_LEFT,-1,line["size"],line["color"])
