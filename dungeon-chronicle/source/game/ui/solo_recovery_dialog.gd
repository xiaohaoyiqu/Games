extends ConfirmationDialog

var ui_font: Font
var reviewed_id: String = ""
var _summary: String = ""


func _ready() -> void:
	title = "上次单人出征结算"
	ok_button_text = "保存本局结算"
	cancel_button_text = "稍后"
	dialog_autowrap = true
	dialog_hide_on_ok = false
	transient = true
	exclusive = true
	canceled.connect(func(): reviewed_id = "")
	get_label().add_theme_font_override("font",ui_font)
	get_label().add_theme_font_size_override("font_size",18)
	get_label().add_theme_constant_override("line_spacing",6)
	for button: Button in [get_ok_button(),get_cancel_button()]:
		button.add_theme_font_override("font",ui_font)
		button.add_theme_font_size_override("font_size",18)


func show_review(view: Dictionary) -> void:
	if visible: return
	_set_saving(false)
	reviewed_id = str(view["id"])
	_summary = _review_text(view)
	dialog_text = _summary
	popup_centered_clamped(Vector2i(660,350),0.90)
	_focus_later.call_deferred()


func show_saving() -> void:
	_set_saving(true)
	dialog_text = _summary+"\n\n正在保存结算，请稍候。"
	get_cancel_button().grab_focus()


func show_failure(message: String) -> void:
	_set_saving(false)
	dialog_text = _summary+"\n\n保存失败 · "+message


func _set_saving(saving: bool) -> void:
	get_ok_button().disabled = saving
	cancel_button_text = "返回主菜单" if saving else "稍后"


func _focus_later() -> void:
	if visible: get_cancel_button().grab_focus()


func _review_text(view: Dictionary) -> String:
	var outcome: String = "已通关" if view["cleared"] else "未通关"
	var lines := PackedStringArray([
		"尚未入账 · %s · %s · 击败 %d" % [view["difficulty_name"],outcome,view["kills"]],
		"金币 +%d    经验 +%d    碎片 +%d" % [view["gold"],view["xp"],view["materials"]],
	])
	if int(view["level_after"]) > int(view["level_before"]):
		lines.append("等级 Lv.%d → Lv.%d" % [view["level_before"],view["level_after"]])
	if view["elite_unlocked"]: lines.append("精英难度解锁")
	if not view["item_names"].is_empty():
		lines.append("获得装备 · "+"、".join(view["item_names"]))
	for quest: Dictionary in view["quests"]:
		var suffix: String = " · 保存后可回城领取" if quest["claimable"] else ""
		lines.append("%s · %d → %d / %d%s" % [quest["name"],quest["before"],quest["after"],quest["goal"],suffix])
	return "\n".join(lines)
