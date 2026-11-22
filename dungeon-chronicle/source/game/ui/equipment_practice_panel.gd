extends Control

const Actions=preload("res://game/input/actions.gd")
const TrialReadings = preload("res://game/ui/equipment_trial_readings.gd")
var room: Node2D
var buttons: Dictionary={}
var labels: Dictionary={}
var view: Dictionary={}
var rebuilds: int=0
var _revision: int=-1
var _observation_revision: int=-1
var _history_revision: int=-1
var _fixed_trial_revision: int=-1
var _paused: bool=false
var _notice: String=""

func _label(id: String,point: Vector2,width: float,font_size: int=15) -> void:
	var label:=Label.new()
	label.mouse_filter=Control.MOUSE_FILTER_PASS
	label.clip_text=true;label.text_overrun_behavior=TextServer.OVERRUN_TRIM_ELLIPSIS
	label.position=point;label.size=Vector2(width,18)
	label.add_theme_font_override("font",room.ui_font);label.add_theme_font_size_override("font_size",font_size)
	add_child(label);labels[id]=label
func _button(id: String,point: Vector2,width: float,callback: Callable) -> void:
	var button:=Button.new()
	# Combat retains Space/arrows; every HUD action also has a shared keyboard binding.
	button.focus_mode=Control.FOCUS_NONE
	button.clip_text=true;button.text_overrun_behavior=TextServer.OVERRUN_TRIM_ELLIPSIS
	button.position=point;button.size=Vector2(width,32)
	button.add_theme_font_override("font",room.ui_font);button.add_theme_font_size_override("font_size",16)
	for state in ["normal","hover","pressed","disabled"]:
		var style:=StyleBoxFlat.new();style.set_border_width_all(1)
		style.bg_color=Color("334453") if state=="hover" else Color("223140")
		style.border_color=Color("5b6873");button.add_theme_stylebox_override(state,style)
	button.pressed.connect(callback);add_child(button);buttons[id]=button

func _ready() -> void:
	position=Vector2(28,110);size=Vector2(872,236);mouse_filter=Control.MOUSE_FILTER_IGNORE
	_label("title",Vector2(16,5),840,18)
	_label("state",Vector2(16,26),840,15)
	_label("stats",Vector2(16,46),840,14)
	_label("scope",Vector2(16,65),840,13)
	_label("live",Vector2(16,84),840,13)
	_label("last_current",Vector2(16,103),840,13)
	_label("last_candidate",Vector2(16,122),840,13)
	_label("fixed_skill",Vector2(16,141),410,13)
	_label("fixed_incoming",Vector2(440,141),416,13)
	_label("fixed_walk",Vector2(16,160),410,13)
	_label("fixed_evade",Vector2(440,160),416,13)
	_label("notice",Vector2(16,215),840,12)
	_button("current",Vector2(16,181),111,func():room.select_equipment_side("current"))
	_button("candidate",Vector2(135,181),111,func():room.select_equipment_side("candidate"))
	_button("fixed_skill",Vector2(254,181),105,func():room.start_fixed_trial("skill"))
	_button("fixed_incoming",Vector2(367,181),105,func():room.start_fixed_trial("incoming"))
	_button("fixed_walk",Vector2(480,181),105,func():room.start_fixed_trial("walk"))
	_button("fixed_evade",Vector2(593,181),105,func():room.start_fixed_trial("evade"))
	_button("town",Vector2(706,181),150,room.return_equipment_town)
	_process(0.0)

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		_refresh_equipment()

func _process(_delta: float) -> void:
	visible = not room.paused
	if (room.equipment_practice.revision != _revision or room.fixed_trial_revision != _fixed_trial_revision
			or room.paused != _paused or room.equipment_notice != _notice):
		_refresh_equipment()
	if room.equipment_observations.revision != _observation_revision:
		_refresh_observations()

func _refresh_equipment() -> void:
	_fixed_trial_revision=room.fixed_trial_revision
	_revision=room.equipment_practice.revision;_paused=room.paused;_notice=room.equipment_notice
	view=room.equipment_practice.describe();rebuilds+=1
	labels["title"].text="装备训练试用 · 冻结 Lv.%d · 不修改角色配装" % int(view["level"])
	labels["state"].text="A 当前：%s +%d   /   B 所选：%s +%d%s" % [view["current_name"],view["current_rank"],view["candidate_name"],view["candidate_rank"]," · 进入时未获得，仅临时试用" if not view["owned"] else ""]
	var stats: Dictionary=view["stats"]
	labels["stats"].text="正在使用 %s · 生命 %d / 伤害 %.2f× / 承伤 %.0f%% / 移速 %.0f%% / 冷却 %.0f%%" % ["A 当前配装" if view["side"]=="current" else "B 所选装备",stats["hp"],stats["damage"],stats["incoming"]*100,stats["speed"]*100,stats["cooldown"]*100]
	var reset_binding: String = Actions.binding_text("reset_room")
	var interrupt_options := "切换配装" if reset_binding == "未绑定" else "重置（%s）或切换配装" % reset_binding
	labels["scope"].text = interrupt_options+"可保留最近一轮记录。自动复位继续累计，倒地和击倒按次数记录。试用不发奖励、不购买或装备。"
	var fixed_results: Dictionary = room.fixed_trial_results("skill")
	labels["fixed_skill"].text = "试斩 · %s / %s" % [TrialReadings.reading("skill","A",fixed_results.get("current",{})),TrialReadings.reading("skill","B",fixed_results.get("candidate",{}))]
	var incoming: Dictionary = room.fixed_trial_results("incoming")
	labels["fixed_incoming"].text = "重锤 · %s / %s" % [TrialReadings.reading("incoming","A",incoming.get("current",{})),TrialReadings.reading("incoming","B",incoming.get("candidate",{}))]
	var walk: Dictionary = room.fixed_trial_results("walk")
	labels["fixed_walk"].text = "定距 · %s / %s" % [TrialReadings.reading("walk","A",walk.get("current",{})),TrialReadings.reading("walk","B",walk.get("candidate",{}))]
	var evade: Dictionary = room.fixed_trial_results("evade")
	var evade_a: Dictionary = evade.get("current",{})
	var evade_b: Dictionary = evade.get("candidate",{})
	labels["fixed_evade"].text = "避锤 · %s / %s" % [TrialReadings.reading("evade","A",evade_a),TrialReadings.reading("evade","B",evade_b)]
	if not _notice.is_empty():
		labels["notice"].text = _notice
	elif room.fixed_trial_kind == "evade":
		labels["notice"].text = "看重锤预警，按上下方向离开范围，等重锤收招。"
	elif room.fixed_trial_kind == "walk":
		labels["notice"].text = "沿标线路段自动向上行走。"
	elif room.fixed_trial_kind == "incoming":
		labels["notice"].text = "守卫将出一次重锤，固定承伤期间站位锁定。"
	elif room.fixed_trial_kind == "skill":
		labels["notice"].text = "正在用所选配装试斩静止守卫，出招结束后回到自由试用。"
	else:
		var ai_key := Actions.binding_text("toggle_ai")
		var ai_hint := "守卫进攻切换键未绑定。" if ai_key == "未绑定" else "切换守卫进攻（%s）。" % ai_key
		labels["notice"].text = ai_hint+"中途切换会标为混合条件。自由试用的时长和操作可不同。"
	if room.fixed_trial_running():
		labels["notice"].text += interrupt_options+"可中断。"
	var return_caption: String = {"inventory":"返回背包","forge":"返回铁匠"}.get(room.practice_context.get("kind"),"返回城镇")
	for id: String in ["current","candidate","town"]:
		var action: String={"current":"equipment_current","candidate":"equipment_candidate","town":"equipment_town"}[id]
		buttons[id].text={"current":"A 当前配装","candidate":"B 所选装备","town":return_caption}[id]+" "+Actions.binding_text(action)
		buttons[id].disabled=_paused or (id!="town" and view["side"]==id)
		buttons[id].tooltip_text=buttons[id].text+("；每次切换重置场地" if id!="town" else "；角色金币和装备保持")
	buttons["fixed_skill"].text = "固定试斩 " + Actions.binding_text("equipment_fixed_skill")
	buttons["fixed_skill"].disabled = _paused or room.fixed_trial_running()
	buttons["fixed_skill"].tooltip_text = "结束当前自由试用，复位后朝同线满血守卫出一次破空斩。记录实际损血和出招时设置的冷却。"
	buttons["fixed_incoming"].text = "固定承伤 " + Actions.binding_text("equipment_fixed_incoming")
	buttons["fixed_incoming"].disabled = _paused or room.fixed_trial_running()
	buttons["fixed_incoming"].tooltip_text = "结束当前自由试用，由同线守卫对满血的你使出一次重锤。记录实际损血和受击后生命。"
	buttons["fixed_walk"].text = "定距走位 " + Actions.binding_text("equipment_fixed_walk")
	buttons["fixed_walk"].disabled = _paused or room.fixed_trial_running()
	buttons["fixed_walk"].tooltip_text = "用所选配装自动走同一段纵深路线，记录脚点通过终点的模拟用时。"
	buttons["fixed_evade"].text = "走位避锤 " + Actions.binding_text("equipment_fixed_evade")
	buttons["fixed_evade"].disabled = _paused or room.fixed_trial_running()
	buttons["fixed_evade"].tooltip_text = "从同线、相距 60 开始，只用上下走位避开一次重锤。"
	for label: Label in labels.values():label.tooltip_text=label.text
	if not evade_a.is_empty():
		labels["fixed_evade"].tooltip_text += "\n" + TrialReadings.evade_detail("A",evade_a)
	if not evade_b.is_empty():
		labels["fixed_evade"].tooltip_text += "\n" + TrialReadings.evade_detail("B",evade_b)
	labels["fixed_walk"].tooltip_text += "。路线长度 192 单位，读数为模拟秒数。"
	if not fixed_results.is_empty():
		var reference: Dictionary = fixed_results.get("current",fixed_results.get("candidate",{}))
		labels["fixed_skill"].tooltip_text += "。同线相距 60，守卫起始生命 %d。" % int(reference["target_hp"])
	labels["fixed_incoming"].tooltip_text += "。同线相距 60，角色以本配装的满血开始。"
	queue_redraw()

func _refresh_observations() -> void:
	var log_model: RefCounted = room.equipment_observations
	_observation_revision = log_model.revision
	_set_observation(labels["live"],"本轮",log_model.live_snapshot())
	if log_model.history_revision == _history_revision: return
	_history_revision = log_model.history_revision
	var history: Dictionary = log_model.history_snapshot()
	_set_observation(labels["last_current"],"A 最近",history.get("current",{}))
	_set_observation(labels["last_candidate"],"B 最近",history.get("candidate",{}))

func _set_observation(label: Label, prefix: String, record: Dictionary) -> void:
	label.text = _observation_text(prefix,record)
	label.tooltip_text = label.text

func _observation_text(prefix: String, record: Dictionary) -> String:
	if record.is_empty(): return prefix+"：暂无记录。技能出招、命中或受击后，重置或切换配装可保留记录。"
	var mode: String="混合条件" if record["ai_changed"] else ("主动进攻" if record["ai"] else "静止")
	var side: String="A" if record["side"]=="current" else "B"
	var heading: String=prefix+" "+side if prefix=="本轮" else prefix
	return "%s · %s · 技能 %d · 命中 %d/伤害 %d · 受击 %d/损血 %d · 倒地 %d/击倒 %d%s" % [heading,mode,record["skill_starts"],record["outgoing_hits"],record["outgoing_damage"],record["incoming_hits"],record["incoming_damage"],record["player_defeats"],record["target_defeats"]," · 计数不完整" if record["incomplete"] else ""]

func _draw() -> void:
	draw_rect(Rect2(Vector2.ZERO,size),Color("172635"))
	draw_rect(Rect2(Vector2.ZERO,size),Color("687a83"),false,1)
