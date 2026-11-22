extends Node2D

const Actions = preload("res://game/input/actions.gd")
const Guide = preload("res://game/quests/quest_guide.gd")
var host: Node2D
var in_run: bool = false
var party: bool = false
var collapsed: bool = false
var view: Dictionary = {}
var rebuilds: int = 0
var text_lines: Array[Dictionary] = []
var panel_rect := Rect2(0,0,272,184)
var _model: RefCounted
var _session: Node
var _party_profile_run: String = ""
var _profile: Dictionary = {}
var _revision: int = -1
var _kills: int = -1
var _state: int = -1
var _pending: bool = false
var _saving: bool = false
var _awaiting_result: bool = false
var _id: String = ""
var _tracked_quest: String = ""

func _ready() -> void:
	_session = host.service._session if party else Session
	position = Vector2(18,148) if party else Vector2(28,116)
	if party:
		panel_rect.size.x = 218
		# This scene is created after Session.begin_party binds the local profile run.
		_party_profile_run = _session.active_run_id
	_model = Guide.new(_session.rules)
	_process(0.0)

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready(): refresh()


func _process(_delta: float) -> void:
	if party:
		visible = host._active and (host.service.running() or not host.terminal_review.is_empty())
	elif in_run:
		visible = host.run_finished() or not (host.paused and not host.verify_mode)
	else:
		visible = host.panel == null
	_refresh_if_changed()

func toggle_collapsed() -> void:
	collapsed = not collapsed
	_layout()

func refresh() -> void: _refresh_if_changed(true)

func _refresh_if_changed(force: bool=false) -> void:
	var revision: int = int(_session.profile.get("revision",-1))
	var kills: int = host.run.kills if in_run else 0
	var state: int = host.run.state if in_run else -1
	var ended: bool = state == 2 or state == 3
	if in_run and not party: ended = host.run_finished()
	var id: String = _party_profile_run if party else (host.run_id if in_run else "")
	var bound: bool = not id.is_empty() and id == _session.active_run_id
	var pending: bool = false
	var saving: bool = false
	var awaiting_result: bool = false
	if party:
		pending = bound and host.service.pending_save()
		saving = pending and host.service.saving()
		awaiting_result = host.service.running() and ended and host.terminal_review.is_empty()
		bound = bound and (host.service.running() or pending)
	elif in_run:
		pending = not host.rewards_saved and not host.settlement_note.is_empty()
		saving = host.settlement_saving
		bound = bound or host.rewards_saved
	var context_id: String = id if bound else ""
	var tracked: String = _session.tracked_quest_id
	if not force and revision == _revision and is_same(_profile,_session.profile) and kills == _kills and state == _state and context_id == _id and pending == _pending and saving == _saving and awaiting_result == _awaiting_result and tracked == _tracked_quest:
		return
	_profile = _session.profile
	_revision = revision
	_kills = kills
	_state = state
	_id = context_id
	_pending = pending
	_saving = saving
	_awaiting_result = awaiting_result
	_tracked_quest = tracked
	var difficulty: String = str(host.difficulty.get("id","normal")) if in_run else "normal"
	var context := {"in_run":in_run,"party":party,"id":_id,"kills":kills,"difficulty":difficulty,"cleared":state == 2 and not awaiting_result,
		"ended":ended,"pending":pending,"saving":saving,"awaiting_result":awaiting_result,"tracked_quest":tracked}
	if ended and not pending and not awaiting_result:
		var saved_progress: Dictionary = _session.quest_progress_receipt(id)
		if not saved_progress.is_empty():
			# Party settlement clears the active binding after saving.
			context["id"] = id
			context["saved_progress"] = saved_progress
	view = _model.describe(_session.profile,context)
	rebuilds += 1
	_layout()

func _line(y: float, value: String, size: int, color: Color) -> void:
	text_lines.append({"point":Vector2(12,y),"text":value,"size":size,"color":color})

func _layout() -> void:
	text_lines.clear()
	var compact: bool = view["rows"].size() == 3
	var row_step: int = 36 if compact else 51
	var footer_y: int = 50+row_step*view["rows"].size()
	panel_rect.size.y = 34 if collapsed else footer_y+32
	var title: String = "个人委托" if party else "委托目标"
	if not view["tracked_quest"].is_empty(): title = "跟踪委托"
	if in_run: title += " · %s %s" % [Actions.binding_text("quests"),"展开" if collapsed else "收起"]
	_line(24,title,16,Color("e2d4b7"))
	if not collapsed:
		var index: int = 0
		for row: Dictionary in view["rows"]:
			var status: String = "可领奖" if row["claimable"] else ("预计" if row["provisional"] else "")
			var row_y: int = (46 if compact else 52)+index*row_step
			_line(row_y,"%s  %s%d/%d" % [row["name"],status,row["progress"],row["goal"]],15,Color("a9d9bd") if row["claimable"] else Color("d7d9df"))
			var increment: int = row["saved_increment"]
			var detail: String = "本局 +%d · 已保存" % increment if increment > 0 else row["objective"]
			_line(row_y+(17 if compact else 21),detail,14,Color("a9d9bd") if increment > 0 else Color("a7b3c4"))
			index += 1
		_line(footer_y,view["hint"],13,Color("dfc594"))
		_line(footer_y+21,view["detail"],13,Color("a7b3c4"))
	queue_redraw()

func _draw() -> void:
	draw_rect(panel_rect,Color(0.035,0.046,0.071,0.94))
	draw_rect(panel_rect,Color("62576a"),false,1)
	for line: Dictionary in text_lines:
		draw_string(host.ui_font,line["point"],line["text"],HORIZONTAL_ALIGNMENT_LEFT,panel_rect.size.x-line["point"].x-6,line["size"],line["color"])
