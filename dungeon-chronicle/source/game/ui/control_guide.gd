extends AcceptDialog

signal training_requested
const Actions = preload("res://game/input/actions.gd")
const SECTIONS: Array = [
	["走位与战斗",[
		["move_left","向左走位"],["move_right","向右走位"],["move_up","向远处走位"],["move_down","向近处走位"],
		["attack","普攻；及时再次输入衔接三段"],["jump","跳跃；高度与地面前后位置分别判断"],
		["dash","朝面向方向冲刺，短暂回避"],["skill","破空斩；实际命中可挑起，有独立冷却"]]],
	["城镇与本局",[
		["interact","城镇交互／清怪后过门／结算保存失败时明确重试"],
		["inventory","城镇背包与装备比较"],["quests","城镇委托／单人副本目标栏收起与展开"],
		["pause_game","单人暂停或继续；组队只暂停个人操作"],
		["return_menu","单人战斗回城；城镇与训练房回菜单；组队按身份结束本局、回队或离队"],
		["reset_room","单人副本重开；训练房重置或重试当前项目"],
		["confirm_result","单人结算：胜利回城，失败重新开始"]]],
	["训练房",[
		["guidance_toggle","从第一项开始引导／退出引导回自由练习"],
		["guidance_previous","预选上一项练习"],["guidance_next","预选下一项练习"],
		["guidance_start","开始所选项目；这一步才重置当前练习"],
		["toggle_ai","仅自由练习切换守卫主动进攻"],
		["equipment_current","铁匠装备试用：当前配装，切换会重置场地"],
		["equipment_candidate","铁匠装备试用：所选装备，切换会重置场地"],
		["equipment_fixed_skill","铁匠装备试用 · 固定试斩"],
		["equipment_fixed_incoming","铁匠装备试用 · 固定承伤"],
		["equipment_fixed_walk","铁匠装备试用 · 定距走位"],
		["equipment_fixed_evade","铁匠装备试用 · 走位避锤"],
		["equipment_town","铁匠装备试用：返回城镇；不购买或装备"]]],
	["显示与主菜单",[
		["toggle_debug","显示或隐藏判定范围"],["toggle_shake","切换屏幕震动并尝试保存；失败时本次仍生效"],
		["display_settings","主菜单视听设置：震动／音效保存、音量与试听"],
		["toggle_sound","立即切换音效；在视听设置中保存后下次沿用"],
		["controls_help","在主菜单打开这份操作说明"]]]]
var ui_font: Font
var body: RichTextLabel
var scrollbar: VScrollBar
var training_button: Button
var refreshes: int = 0

func _ready() -> void:
	title = "操作说明"
	visible = false
	exclusive = true
	transient = true
	min_size = Vector2i(560,360)
	ok_button_text = "关闭"
	get_label().hide()
	body = RichTextLabel.new()
	body.name = "ControlNotes"
	body.bbcode_enabled = false
	body.fit_content = false
	body.scroll_active = true
	body.focus_mode = Control.FOCUS_NONE
	body.custom_minimum_size = Vector2(520,280)
	body.add_theme_font_override("normal_font",ui_font)
	body.add_theme_font_size_override("normal_font_size",18)
	body.add_theme_constant_override("line_separation",5)
	add_child(body)
	scrollbar = body.get_v_scroll_bar()
	scrollbar.focus_mode = Control.FOCUS_ALL
	scrollbar.step = 32.0
	var focus := StyleBoxFlat.new()
	focus.bg_color = Color(0,0,0,0)
	focus.border_color = Color("d2bb89")
	focus.set_border_width_all(2)
	scrollbar.add_theme_stylebox_override("scroll_focus",focus)
	training_button = add_button("前往训练房",false,"training")
	for button: Button in [training_button,get_ok_button()]:
		button.add_theme_font_override("font",ui_font)
		button.add_theme_font_size_override("font_size",18)
	scrollbar.focus_next = scrollbar.get_path_to(training_button)
	training_button.focus_previous = training_button.get_path_to(scrollbar)
	training_button.focus_next = training_button.get_path_to(get_ok_button())
	get_ok_button().focus_previous = get_ok_button().get_path_to(training_button)
	get_ok_button().focus_next = get_ok_button().get_path_to(scrollbar)
	scrollbar.focus_previous = scrollbar.get_path_to(get_ok_button())
	custom_action.connect(_on_custom_action)

func _on_custom_action(action: StringName) -> void:
	if action == &"training": training_requested.emit()

func _unhandled_key_input(event: InputEvent) -> void:
	if visible and not event.is_echo() and event.is_action_pressed("ui_cancel"):
		close_requested.emit()
		hide.call_deferred()
		set_input_as_handled()

func focus_contents() -> void:
	if not visible: return
	if scrollbar.is_visible_in_tree(): scrollbar.grab_focus()
	else: training_button.grab_focus()

func refresh_bindings() -> void:
	var lines := PackedStringArray()
	lines.append("当前键盘绑定")
	lines.append(Actions.scroll_dialog_hint())
	lines.append("")
	for section: Array in SECTIONS:
		lines.append(str(section[0]))
		for row: Array in section[1]:
			lines.append("%s  —  %s" % [Actions.binding_text(row[0]),row[1]])
		lines.append("")
	lines.append("城镇面板 · %s · 关闭 %s" % [Actions.panel_navigation_hint(),Actions.binding_text("pause_game")])
	lines.append("移动焦点 ↑%s ↓%s ←%s →%s。翻页或比较不会购买。" % [
		Actions.binding_text("ui_up"),Actions.binding_text("ui_down"),Actions.binding_text("ui_left"),Actions.binding_text("ui_right")])
	lines.append("练习提示")
	lines.append("先看脚点的前后位置，再确认朝向与距离。预选课程不会清掉当前尝试，明确开始后才重置。")
	lines.append("橙框表示近战的地面范围投影；跳跃高度与无敌状态另行判定。菱形“射”表示射击准备，箭矢在出手时定向，不是橙框直线。")
	lines.append("训练第六项可单独练起跳与落地：金框看脚点，影子仍在地面；跳跃不等于无敌。")
	lines.append("训练右下角解释破空斩当前动作、冷却与短缓冲。过期输入需再按。出招次数包含挥空。重置练习（%s）或切换配装会开始新一轮。" % Actions.binding_text("reset_room"))
	lines.append("单人失焦会暂停；组队失焦只清理本人操作，队伍战斗继续。")
	lines.append("结算会区分正在保存、待办已写入与角色成长已保存；已收到的组队结算保存失败时，可按交互键重试。")
	var next_text: String = "\n".join(lines)
	if body.text != next_text: body.text = next_text
	body.get_v_scroll_bar().value = 0
	refreshes += 1
