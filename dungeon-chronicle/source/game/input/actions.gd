extends RefCounted

const KEY_BINDINGS: Dictionary = {
	"move_left":[KEY_A,KEY_LEFT],"move_right":[KEY_D,KEY_RIGHT],"move_up":[KEY_W,KEY_UP],"move_down":[KEY_S,KEY_DOWN],
	"attack":[KEY_J],"jump":[KEY_K,KEY_SPACE],"dash":[KEY_L,KEY_SHIFT],"skill":[KEY_U],
	"reset_room":[KEY_R],"toggle_ai":[KEY_F2],"toggle_debug":[KEY_F3],"toggle_shake":[KEY_F4],
	"pause_game":[KEY_ESCAPE],"interact":[KEY_E],"return_menu":[KEY_F1],"inventory":[KEY_I],"quests":[KEY_Q],
	"guidance_toggle":[KEY_F5],"guidance_previous":[KEY_F6],"guidance_next":[KEY_F7],"guidance_start":[KEY_F8],
	"equipment_current":[KEY_1],"equipment_candidate":[KEY_2],"equipment_town":[KEY_BACKSPACE],
	"equipment_fixed_skill":[KEY_3],"equipment_fixed_incoming":[KEY_4],"equipment_fixed_walk":[KEY_5],"equipment_fixed_evade":[KEY_6],
	"toggle_sound":[KEY_F11],"display_settings":[KEY_F9],"controls_help":[KEY_F10],"confirm_result":[KEY_ENTER,KEY_KP_ENTER]}

static func setup() -> void:
	for action: String in KEY_BINDINGS:
		if InputMap.has_action(action): continue
		InputMap.add_action(action)
		for code: int in KEY_BINDINGS[action]:
			var event := InputEventKey.new()
			event.physical_keycode = code
			InputMap.action_add_event(action,event)

# Read the current keyboard map when preparing control hints.
static func binding_text(action: String) -> String:
	if not KEY_BINDINGS.has(action) and action not in ["ui_focus_next","ui_focus_prev","ui_accept","ui_cancel","ui_up","ui_down","ui_left","ui_right"]: return ""
	if not InputMap.has_action(action): return "未绑定"
	var labels := PackedStringArray()
	for event: InputEvent in InputMap.action_get_events(action):
		if not event is InputEventKey: continue
		# Match the field Godot's InputEventKey.action_match uses for this binding.
		var label: String
		if event.keycode != KEY_NONE:
			label = event.as_text_keycode()
		elif event.physical_keycode != KEY_NONE:
			label = event.as_text_physical_keycode()
			if event.location == KEY_LOCATION_LEFT: label += "（左侧）"
			elif event.location == KEY_LOCATION_RIGHT: label += "（右侧）"
		elif event.key_label != KEY_NONE:
			label = event.as_text_key_label()
		else:
			continue
		if not label.is_empty() and not labels.has(label): labels.append(label)
	return "未绑定" if labels.is_empty() else " / ".join(labels)


static func scroll_dialog_hint() -> String:
	return "滚轮翻阅 · 上翻 %s · 下翻 %s · 下一项 %s · 关闭 %s" % [
		binding_text("ui_up"),binding_text("ui_down"),binding_text("ui_focus_next"),binding_text("ui_cancel")]


static func panel_navigation_hint() -> String:
	return "上一项 %s · 下一项 %s · 确认 %s" % [
		binding_text("ui_focus_prev"),binding_text("ui_focus_next"),binding_text("ui_accept")]


static func movement_hint() -> String:
	var directions: Array[String] = []
	for action: String in ["move_up","move_down","move_left","move_right"]:
		directions.append(binding_text(action).get_slice(" / ",0))
	return "走位 ↑%s ↓%s ←%s →%s" % directions


static func combat_hint(skill_name: String = "技能") -> String:
	return "%s    普攻 %s    跳跃 %s    冲刺 %s    %s %s" % [
		movement_hint(),binding_text("attack"),binding_text("jump"),
		binding_text("dash"),skill_name,binding_text("skill")]
