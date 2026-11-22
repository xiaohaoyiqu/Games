extends AcceptDialog

const Actions = preload("res://game/input/actions.gd")
const TrialReadings = preload("res://game/ui/equipment_trial_readings.gd")
const EXAMPLES_TITLE: String = "装备作用与代价 · 数值样例"
var ui_font: Font
var body: RichTextLabel
var scrollbar: VScrollBar
var view: Dictionary={}
var caption: String=""
var refreshes: int=0

func _ready() -> void:
	title = EXAMPLES_TITLE
	visible=false;exclusive=true;transient=true
	min_size=Vector2i(560,360);ok_button_text="返回对比"
	get_label().hide()
	body=RichTextLabel.new();body.bbcode_enabled=false;body.fit_content=false;body.scroll_active=true
	body.focus_mode=Control.FOCUS_NONE;body.custom_minimum_size=Vector2(520,300)
	body.add_theme_font_override("normal_font",ui_font);body.add_theme_font_size_override("normal_font_size",17)
	body.add_theme_constant_override("line_separation",5);add_child(body)
	scrollbar=body.get_v_scroll_bar();scrollbar.focus_mode=Control.FOCUS_ALL;scrollbar.step=32.0
	var focus:=StyleBoxFlat.new();focus.bg_color=Color(0,0,0,0);focus.border_color=Color("d2bb89");focus.set_border_width_all(2)
	scrollbar.add_theme_stylebox_override("scroll_focus",focus)
	get_ok_button().add_theme_font_override("font",ui_font);get_ok_button().add_theme_font_size_override("font_size",17)
	scrollbar.focus_next=scrollbar.get_path_to(get_ok_button());scrollbar.focus_previous=scrollbar.focus_next
	get_ok_button().focus_next=get_ok_button().get_path_to(scrollbar);get_ok_button().focus_previous=get_ok_button().focus_next

func show_examples(data: Dictionary,context: String) -> bool:
	if not _can_open() or not data.get("ok",false): return false
	view = data.duplicate(true)
	caption = context
	_show_text(EXAMPLES_TITLE,_text())
	return true

func show_trial_results(data: Dictionary) -> bool:
	if not _can_open() or data.get("results",{}).is_empty(): return false
	view = data.duplicate(true)
	caption = ""
	_show_text("本次装备训练结果",TrialReadings.report_text(view))
	return true

func _can_open() -> bool:
	return not visible and is_inside_tree() and not is_queued_for_deletion()

func _show_text(dialog_title: String,contents: String) -> void:
	title = dialog_title
	body.text = contents
	scrollbar.value = 0
	refreshes += 1
	popup_centered_clamped(Vector2i(900,580),0.9)
	focus_contents.call_deferred()

func focus_contents() -> void:
	if not is_inside_tree() or is_queued_for_deletion() or not visible: return
	if scrollbar.is_visible_in_tree():scrollbar.grab_focus()
	else:get_ok_button().grab_focus()

func _text() -> String:
	var lines:=PackedStringArray()
	lines.append(caption)
	lines.append("当前角色 → 所选方案；仅本次打开时的快照，关闭后可重新打开查看。")
	lines.append(Actions.scroll_dialog_hint())
	lines.append("")
	lines.append("最大生命：%d → %d" % [view["before"]["hp"],view["after"]["hp"]])
	lines.append("逐招数值。近战扣血按命中无减伤、生命充足的目标计算。")
	for row: Dictionary in view["attacks"]:
		if not row["available"]:
			lines.append(str(row["label"])+"：招式数据不可用，未生成伤害／动作样例。")
			continue
		var quantity := "投射物发射伤害" if row.get("projectile",false) else "近战命中扣血"
		lines.append("%s · %s %d → %d；准备／有效／收招 %.3f / %.3f / %.3f 秒" % [
			row["label"],quantity,row["before"],row["after"],row["windup"],row["active"],row["recovery"]])
		if row.get("projectile",false):
			lines.append("实际扣血取决于弹体是否命中及目标状态。")
	if view["skill_available"]:
		lines.append("破空斩冷却：%.3f → %.3f 秒，从技能开始计时。" % [view["skill_cooldown_before"],view["skill_cooldown_after"]])
	else:lines.append("破空斩冷却：招式数据不可用，未生成样例。")
	lines.append("冷却结束不代表此刻一定能出招；攻击、受击、冲刺或死亡状态仍会限制操作。")
	lines.append("装备的技能冷却系数不改变普攻衔接、准备／有效／收招时间。")
	lines.append("")
	lines.append("承伤样例：双方各自满血且可受击；输入伤害尚未应用本方承伤系数。")
	for row: Dictionary in view["incoming"]:
		lines.append("给定输入 %d：扣血 %d → %d" % [row["input"],row["before"],row["after"]])
	lines.append("每次扣血先取整、至少为1，再受剩余生命限制；小额攻击可能没有整数差别。")
	lines.append("这些是给定输入示例，不代表某个敌人、难度或一套固定连击。")
	lines.append("")
	var before: Vector2=view["walk_before"];var after: Vector2=view["walk_after"]
	lines.append("无障碍轴向行走：横向 %.2f → %.2f；前后纵深 %.2f → %.2f 像素／秒。" % [before.x,after.x,before.y,after.y])
	lines.append("仅对应可自由走位的地面位移；障碍、攻击和受击会影响实际路线。")
	lines.append("移速不改变冲刺速度、击退或跳跃的竖直速度；空中地面位移仍遵守走位规则。")
	lines.append("")
	lines.append("本页不会购买、装备、强化或保存。强化未穿戴装备时，当前配装的样例可能不变。")
	lines.append("不合并成强弱分数；命中机会、操作、敌人和关卡条件需要分别考虑。")
	return "\n".join(lines)

func _unhandled_key_input(event: InputEvent) -> void:
	if visible and not event.is_echo() and event.is_action_pressed("ui_cancel"):
		close_requested.emit();hide.call_deferred();set_input_as_handled()
