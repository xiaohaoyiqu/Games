extends Node

## Explicitly created under Session. A network connection starts only on host/join.
signal changed
signal preparing
signal started
signal frame_received(frame: Dictionary)
signal input_received(peer_id: int,message: Dictionary)
signal action_received(peer_id: int,message: Dictionary)
signal departed(peer_id: int)
signal navigation_requested(path: String)

const Transport = preload("res://game/network/enet_party.gd")
const Characters = preload("res://game/network/party_character.gd")
const State = preload("res://game/network/party_state.gd")
const Content = preload("res://game/network/party_content.gd")
const HitReview = preload("res://game/network/party_hit_review.gd")
const Trace = preload("res://game/diagnostics/frame_trace.gd")
const LoadRequest = preload("res://game/navigation/scene_load_request.gd")
const SCENES: Array[String] = ["res://game/main/main_menu.tscn","res://game/main/party_lobby.tscn","res://game/main/party_dungeon.tscn"]
var transport: Node
var _session: Node
var _contract: RefCounted
var _manifest: Dictionary = {}
var _frame: Dictionary = {}
var _result_hit_review: Array = []
var _pending_result: Dictionary = {}
var _result_tick: int = -1
var _queued_result_peers: Array[int] = []
var _result_queued: bool = false
var _started: bool = false
var _awaiting_join_roster: bool = false
var committed_save_hint: String = ""
var note: String = "创建队伍，或输入房主地址加入。"
var automatic_navigation: bool = true
var _route_generation: int = 0
var _route_epoch: int = 0
var _handled_route_generation: int = -1
var _threads_started: int = 0
var _threads_joined: int = 0
var _scene_requests: Dictionary = {}
var _navigation_fault: bool = false
var _navigation_failed: bool = false
var _recovery_layer: CanvasLayer
var _recovery_target: String = ""
var _queued_scene: Dictionary = {}
var _route_pending: bool = false
const NAVIGATION_TRACE_CAPACITY: int = 256
var _navigation_trace_enabled: bool = false
var _navigation_trace_rows: Array = []
var _navigation_trace_counts: Dictionary = {}
var _navigation_trace_omitted: int = 0
var _navigation_request_serial: int = 0
var _navigation_exit_serial: int = 0


func _init(session_node: Node=null) -> void:
	_session = session_node
	if _session != null: _session.party_save_finished.connect(_on_save_finished)


func _ready() -> void:
	set_process(false)
	_navigation_trace_enabled = OS.get_environment("DUNGEON_NAV_TRACE") == "1" and not OS.get_environment("DUNGEON_VERIFY_DIR").is_empty()


func _exit_tree() -> void:
	_clear_navigation_recovery()
	_route_pending = false; _queued_scene.clear()
	# Closing may wait; normal frames get a resource only after LOADED.
	var before: Dictionary = {}
	if _navigation_trace_enabled:
		_navigation_exit_serial += 1; _trace_navigation("exit_begin")
		before = navigation_diagnostics()
	for path in _scene_requests.keys():
		# Observe existing polls only: another status query can itself advance loading.
		var status: int = int(_scene_requests[path].get("last_status",-1))
		_collect_scene_request(path,_scene_requests[path],status,true)
	_scene_requests.clear()
	if _navigation_trace_enabled:
		_trace_navigation("exit_end")
		# Persist both snapshots after draining, so file I/O does not delay get.
		_write_navigation_diagnostics("before",before)
		_write_navigation_diagnostics("after",navigation_diagnostics())


func invalidate_navigation() -> void:
	_route_generation+=1; _route_pending=false; _queued_scene.clear()
	_trace_navigation("invalidated")
	set_process(navigation_pending())


func navigation_pending() -> bool: return _route_pending or not _scene_requests.is_empty() or not _queued_scene.is_empty()


# Obsolete reads can still be draining after the selected route has changed.
func has_current_navigation() -> bool:
	if _route_epoch != _session.scene_epoch(): return false
	if _route_pending or _queued_scene.get("generation",-1) == _route_generation: return true
	for request: Dictionary in _scene_requests.values():
		if request["generation"] == _route_generation: return true
	return false


func _process(_delta: float) -> void:
	for path in _scene_requests.keys():
		var request: Dictionary=_scene_requests[path]
		var status: int=request["task"].poll()
		_note_join(request)
		if _navigation_trace_enabled and int(request.get("last_status",-1))!=status:
			request["last_status"]=status
			_trace_navigation("status",path,int(request["generation"]),int(request["trace_request"]),status)
		if status==LoadRequest.State.LOADING: continue
		_scene_requests.erase(path)
		var packed: Resource=_collect_scene_request(path,request,status,false)
		if int(request["generation"])!=_route_generation or int(request["epoch"])!=_session.scene_epoch():
			_trace_navigation("stale_discard",path,int(request["generation"]),int(request.get("trace_request",0)),status)
			continue
		Trace.end("scene_wait",request["started"],-1)
		if packed is PackedScene: _commit_scene(packed,_route_generation)
		else: _navigation_error(_route_generation)
	if _scene_requests.is_empty() and not _queued_scene.is_empty():
		var queued: Dictionary = _queued_scene.duplicate(); _queued_scene.clear()
		_navigate_now(queued["path"],queued["generation"])
	set_process(navigation_pending())


## Diagnostic values are primitives only. No resource, callable or object is retained.
## IDs are local to this service/process; they do not identify internal engine tokens.
func _trace_navigation(event: String,path: String="",generation: int=-1,request_id: int=0,status: int=-1,error: int=-1,resource_id: int=0,resource_kind: String="") -> void:
	if not _navigation_trace_enabled: return
	_navigation_trace_counts[event] = int(_navigation_trace_counts.get(event,0))+1
	if _navigation_trace_rows.size() >= NAVIGATION_TRACE_CAPACITY:
		_navigation_trace_omitted += 1; return
	_navigation_trace_rows.append({"event":event,"usec":Time.get_ticks_usec(),"path":path if path in SCENES else "","generation":generation,"request":request_id,"status":status,"error":error,"resource_id":str(resource_id),"resource_kind":resource_kind,"status_source":"last_poll" if event.begins_with("exit_get") else ("poll" if status>=0 else "none")})


func _note_join(request: Dictionary) -> void:
	if not request.get("joined",false) and request["task"].joined():
		_threads_joined+=1; request["joined"]=true


func _collect_scene_request(path: String,request: Dictionary,status: int,exiting: bool) -> Resource:
	var identifier: int=int(request.get("trace_request",0))
	var generation: int=int(request["generation"])
	_trace_navigation("exit_get_begin" if exiting else "get_begin",path,generation,identifier,status)
	var resource: Resource=request["task"].take(exiting)
	_note_join(request)
	_session.release_scene_loader(get_instance_id())
	_trace_navigation("exit_get_return" if exiting else "get_return",path,generation,identifier,status,-1,resource.get_instance_id() if resource!=null else 0,resource.get_class() if resource!=null else "")
	return resource


func navigation_diagnostics() -> Dictionary:
	if not _navigation_trace_enabled: return {"enabled":false}
	var outstanding: Array = []
	for path in _scene_requests:
		var request: Dictionary = _scene_requests[path]
		outstanding.append({"path":path,"request":request.get("trace_request",0),"generation":request["generation"],"last_status":request.get("last_status",-1)})
	var returned: int = int(_navigation_trace_counts.get("get_return",0))+int(_navigation_trace_counts.get("exit_get_return",0))
	return {"enabled":true,"schema":2,"backend":"owned-thread" if OS.has_feature("threads") else "synchronous","threads_started":_threads_started,"threads_joined":_threads_joined,"status_semantics":"SceneLoadRequest.State; poll joins a finished worker before READY/FAILED; exit status is last observed state", "service_id":str(get_instance_id()),"rows":_navigation_trace_rows.duplicate(true),"counts":_navigation_trace_counts.duplicate(),"omitted_rows":_navigation_trace_omitted,"capacity":NAVIGATION_TRACE_CAPACITY,"outstanding":outstanding,"accepted_minus_returned":int(_navigation_trace_counts.get("request_accepted",0))-returned,"scope":"Application-owned requests only; scalar resource IDs are process-local and not internal LoadToken IDs. First bounded events, complete aggregate counts. Exit status is last observed poll, not a new query. Exact int64 IDs use decimal strings. Snapshots persist after drain; diagnostic timing may still change scheduling."}


func _write_navigation_diagnostics(stage: String,data: Dictionary) -> void:
	var directory: String = OS.get_environment("DUNGEON_VERIFY_DIR")
	if not _navigation_trace_enabled or directory.is_empty() or not DirAccess.dir_exists_absolute(directory): return
	var path: String = directory.path_join("navigation-"+str(get_instance_id())+"-exit-"+str(_navigation_exit_serial)+"-"+stage+".json")
	var file := FileAccess.open(path,FileAccess.WRITE)
	if file == null:
		push_warning("Navigation diagnostic write failed"); return
	file.store_string(JSON.stringify(data,"  ")); file.close()


func _ensure_transport() -> bool:
	if not _session.configuration_ready(): note = _session.configuration_error(); return false
	if transport != null: return true
	var characters := Characters.new(_session.rules)
	var content_id: String = Content.fingerprint()
	transport = Transport.new()
	if not transport.configure(characters,str(ProjectSettings.get_setting("application/config/version")),content_id):
		transport.free(); transport = null; note = "组队配置不可用"; return false
	add_child(transport)
	transport.room_changed.connect(_on_room)
	transport.run_preparing.connect(_on_prepare)
	transport.run_started.connect(_on_start)
	transport.run_cancelled.connect(_on_cancel)
	transport.connection_closed.connect(_on_closed)
	transport.battle_input.connect(func(peer,message): input_received.emit(peer,message))
	transport.battle_action.connect(func(peer,message): action_received.emit(peer,message))
	transport.battle_state.connect(_on_frame)
	transport.battle_result.connect(_on_result)
	transport.member_left.connect(func(peer): departed.emit(peer))
	return true


func connection_issue() -> String:
	if _session.local_scene_pending(): return "场景正在切换，请稍候。"
	if navigation_failed(): return "请先返回队伍入口或主菜单，再重新连接。"
	if _session.solo_save_pending() or saving(): return "本机结算正在保存，请稍候。"
	if pending_save(): return "请先重试保存本机组队结算。"
	if not _session.active_run_id.is_empty(): return "请先保存当前出征的结算"
	if _session.store.read_only:
		return _session.store.last_error if not _session.store.last_error.is_empty() else "本机档案不可写，请返回入口检查档案。"
	return Transport.platform_issue(OS.get_name())


func _may_connect() -> bool:
	var issue := connection_issue()
	if not issue.is_empty():
		note = issue
		return false
	return _ensure_transport()


func host(port: int,name: String,bind_address: String="*") -> bool:
	if not _may_connect(): changed.emit(); return false
	var status: Error = transport.host(port,name,Characters.new(_session.rules).describe(_session.profile),bind_address)
	note = "队伍已创建，等待队友加入。" if status == OK else "创建失败，请检查端口和角色名称。"
	changed.emit(); return status == OK


func join(address: String,port: int,name: String) -> bool:
	if not _may_connect(): changed.emit(); return false
	var status: Error = transport.join(address,port,name,Characters.new(_session.rules).describe(_session.profile))
	if status == OK: _awaiting_join_roster = true
	note = "正在连接房主……" if status == OK else "加入失败，请检查地址、端口和角色名称。"
	changed.emit(); return status == OK


func snapshot() -> Dictionary: return transport.snapshot() if transport != null else {}
func manifest() -> Dictionary: return _manifest.duplicate(true)
func frame() -> Dictionary: return _frame.duplicate(true)
func owns_run(identifier: String) -> bool:
	return not identifier.is_empty() and identifier == _manifest.get("run","")


func accepted_result_tick(identifier: String) -> int:
	return _result_tick if owns_run(identifier) else -1


func result_queued(identifier: String) -> bool:
	return _result_queued and accepted_result_tick(identifier) >= 0


func result_queue_pending(identifier: String) -> bool:
	return hosting() and _started and accepted_result_tick(identifier) >= 0 and not _result_queued


func _clear_result_queue() -> void:
	_queued_result_peers.clear()
	_result_queued = false


func local_hit_review(identifier: String) -> Dictionary:
	if accepted_result_tick(identifier) < 0: return {}
	var own_peer: int = local_id()
	for row: Dictionary in _result_hit_review:
		if row["peer"] == own_peer: return row.duplicate(true)
	return {}
func run_plan() -> Dictionary: return _contract.plan.duplicate(true) if _contract != null else {}
func hosting() -> bool: return transport != null and transport._hosting
func local_id() -> int: return transport._local_id if transport != null else 0
func running() -> bool: return _started
func pending_save() -> bool: return not _pending_result.is_empty()


func pending_save_hint() -> String:
	# Scalar Session observation only: no file checks, hashes or worker Store reads.
	if not pending_save(): return ""
	if saving(): return "正在保存结算，请等待保存结果。"
	if _session.party_retry_recorded(str(_pending_result["run"])):
		return "结算尚未保存到角色；本次待办已写入。保持当前游戏版本和存档，重启后进入组队冒险核对，符合恢复条件时可重试。"
	return "结算尚未保存到角色，也尚未确认本次待办已写入。请先重试保存；关闭游戏可能无法恢复本次结算。"
func saving() -> bool: return _session != null and _session.party_save_pending()


func local_departure_issue(view: Dictionary={}) -> String:
	if pending_save(): return "本机结算正在保存。" if saving() else "请先重试保存本机结算。"
	var issue: String = _session.party_entry_issue()
	if not issue.is_empty(): return issue
	var party: Dictionary = snapshot() if view.is_empty() else view
	if party.get("phase") != "lobby": return ""
	if transport != null and transport.character_refresh_pending():
		return "正在等待房主同步本机角色资料。"
	return _session.party_layout_issue(str(party["mode"]),maxi(2,party["members"].size()))


func departure_issue(view: Dictionary) -> String:
	var local_issue: String = local_departure_issue(view)
	if not local_issue.is_empty(): return local_issue
	if transport != null and transport.lobby_roster_pending():
		return "正在向队友同步队伍名单，请稍候。"
	match State.begin_block(str(view.get("phase","")),str(view.get("mode","")),view.get("members",[])):
		State.BeginBlock.PHASE: return "请先返回队伍大厅。"
		State.BeginBlock.SIZE: return "等待队友加入，至少两人出征。"
		State.BeginBlock.UNLOCK: return "有队员尚未解锁精英，请切换到普通。"
		State.BeginBlock.UNREADY: return "等待名单中未准备的队员。"
	return ""


func set_ready(value: bool) -> bool:
	if pending_save() or (value and not local_departure_issue().is_empty()):
		changed.emit()
		return false
	return transport != null and transport.set_ready(value)


func begin() -> bool:
	if not hosting(): return false
	if not departure_issue(snapshot()).is_empty():
		changed.emit()
		return false
	if not transport.begin_dungeon():
		note = "队伍状态已变化，请核对准备状态。"
		changed.emit()
		return false
	return true


func arrival_states(identifier: String) -> Dictionary:
	if identifier.is_empty() or identifier != _manifest.get("run","") or running(): return {}
	var view: Dictionary = snapshot()
	if view.get("phase") not in ["loading","running"] or view.get("run","") != identifier:
		return {}
	if view.get("epoch","") != _manifest["epoch"] or view.get("generation",-1) != _manifest["generation"]:
		return {}
	var result: Dictionary = {}
	for member: Dictionary in view["members"]:
		result[int(member["peer"])] = bool(member["loaded"])
	return result


func can_cancel_loading() -> bool:
	return hosting() and not running() and not pending_save() and not _manifest.is_empty() and transport._entry_in_progress()


func cancel_loading() -> bool:
	return can_cancel_loading() and transport.cancel_loading()


func navigation_failed() -> bool: return _navigation_failed


func recover_navigation(path: String) -> bool:
	if not _navigation_failed or path not in SCENES.slice(0,2): return false
	if navigation_pending() and path == _recovery_target: return false
	_route(path)
	return true


func _clear_navigation_recovery() -> void:
	if not _navigation_failed: return
	_navigation_failed = false; _recovery_target = ""
	if is_instance_valid(_recovery_layer):
		_recovery_layer.visible = false; _recovery_layer.queue_free()
	_recovery_layer = null
	note = "已返回入口，请重新创建或加入队伍。"+(" 结算正在保存，请稍候。" if saving() else (" 结算尚未保存，请重试。" if pending_save() else ""))


func _route(path: String) -> void:
	if _navigation_fault or _session.local_scene_pending(): return
	if path not in SCENES: note = "场景入口不可用。"; changed.emit(); return
	_route_generation += 1
	_route_epoch = _session.scene_epoch()
	_trace_navigation("route",path,_route_generation)
	if _navigation_failed: _recovery_target = path
	if automatic_navigation: _route_pending = true; set_process(true)
	var generation := _route_generation
	navigation_requested.emit(path)
	if automatic_navigation and generation == _route_generation:
		_navigate.call_deferred(path,generation)


func _navigate(path: String,generation: int) -> void:
	var measured: int = Trace.begin()
	_navigate_now(path,generation)
	Trace.end("navigate",measured,-1)


func _new_load_request() -> RefCounted: return LoadRequest.new(_navigation_trace_enabled)


func _navigate_now(path: String,generation: int) -> void:
	if generation != _route_generation or not is_inside_tree() or path not in SCENES: return
	_route_pending = false
	if _route_epoch!=_session.scene_epoch() or _session.local_scene_pending():
		_queued_scene.clear(); return
	if _scene_requests.has(path):
		_scene_requests[path]["generation"] = generation
		_scene_requests[path]["epoch"] = _route_epoch
		_handled_route_generation=generation
		_trace_navigation("request_reused",path,generation,int(_scene_requests[path].get("trace_request",0)))
		_queued_scene.clear()
		return
	if generation==_handled_route_generation:
		_queued_scene.clear(); return
	if not _scene_requests.is_empty():
		_queued_scene = {"path":path,"generation":generation}
		_trace_navigation("queued",path,generation)
		return
	if not _session.acquire_scene_loader(get_instance_id()):
		_queued_scene={"path":path,"generation":generation}; set_process(true); return
	var task: RefCounted=_new_load_request()
	var status: Error=task.start(path)
	if status != OK:
		_session.release_scene_loader(get_instance_id())
		_trace_navigation("request_failed",path,generation,0,-1,status)
		_navigation_error(generation); return
	_handled_route_generation=generation; _queued_scene.clear()
	if task.started(): _threads_started+=1
	_scene_requests[path] = {"generation":generation,"epoch":_route_epoch,"started":Trace.begin(),"task":task}
	if _navigation_trace_enabled:
		_navigation_request_serial += 1
		_scene_requests[path]["trace_request"] = _navigation_request_serial
		_trace_navigation("request_accepted",path,generation,_navigation_request_serial)
	set_process(true)


func _commit_scene(packed: PackedScene,generation: int) -> void:
	if generation != _route_generation or not is_inside_tree(): return
	var measured: int = Trace.begin()
	var status: Error = get_tree().change_scene_to_packed(packed)
	_trace_navigation("commit",packed.resource_path,generation,0,-1,status)
	Trace.end("scene_commit",measured,-1)
	if status != OK: _navigation_error(generation)
	else: _clear_navigation_recovery()


func _navigation_error(generation: int) -> void:
	if generation != _route_generation: return
	_route_generation += 1
	_route_pending = false; _queued_scene.clear()
	_navigation_fault = true
	if transport != null: transport.close("cancelled")
	_navigation_fault = false
	note = "场景加载失败，请重试返回队伍或返回主菜单。"
	_navigation_failed = true; _recovery_target = ""
	if not is_instance_valid(_recovery_layer):
		_recovery_layer = preload("res://game/ui/navigation_recovery.gd").new()
		_recovery_layer.service = self
		add_child(_recovery_layer)
	changed.emit()


func _on_room(value: Dictionary) -> void:
	if _awaiting_join_roster:
		_awaiting_join_roster = false
		note = "已加入队伍。"
	if value["phase"] == "lobby" and not _manifest.is_empty():
		# The lobby roster can reach us even if the cancellation notice never queued.
		if _result_tick < 0 and not pending_save(): note = "本次入场已取消，请重新准备。"
		if not pending_save(): _session.cancel_party(_manifest["run"])
		_manifest.clear(); _started = false; _contract = null; _frame.clear(); _result_hit_review.clear()
		_clear_result_queue()
		_route("res://game/main/party_lobby.tscn")
	if value["phase"] == "lobby" and not pending_save() and _session.active_run_id.is_empty():
		var current: Dictionary = Characters.new(_session.rules).describe(_session.profile)
		for member in value["members"]:
			if int(member["peer"]) == local_id() and member["character"] != current:
				transport.refresh_character(current); break
	changed.emit()


func _on_prepare(value: Dictionary) -> void:
	var result: Dictionary = _session.begin_party(value,local_id())
	if not result["ok"]:
		transport.close("cancelled")
		note = result["message"]
		changed.emit()
		return
	committed_save_hint = ""
	_manifest = value.duplicate(true); _contract = _session._party_run
	_frame.clear(); _result_hit_review.clear(); _pending_result.clear(); _result_tick = -1; _started = false
	_clear_result_queue()
	note = "正在加载副本，等待全部队员。"
	var contract := _contract
	var route_generation := _route_generation
	preparing.emit()
	if contract != _contract or route_generation != _route_generation: return
	_route("res://game/main/party_dungeon.tscn")
	changed.emit()


func acknowledge_loaded(identifier: String) -> bool:
	return transport != null and not _manifest.is_empty() and identifier == _manifest["run"] and transport.mark_loaded(identifier)


func loading_confirmation_pending(identifier: String) -> bool:
	return transport != null and identifier == _manifest.get("run","") and transport.loading_confirmation_pending(identifier)


func _on_start(_value: Dictionary) -> void:
	_started = true; note = "队伍已进入回响遗迹。"; started.emit(); changed.emit()


func _on_cancel(identifier: String) -> void:
	if not pending_save(): _session.cancel_party(identifier)
	_manifest.clear(); _contract = null; _started = false; _frame.clear(); _result_hit_review.clear()
	_clear_result_queue()
	note = "本次入场已取消，请重新准备。"
	_route("res://game/main/party_lobby.tscn"); changed.emit()


func _on_closed(reason: String) -> void:
	if not _manifest.is_empty() and not pending_save(): _session.cancel_party(_manifest["run"])
	_manifest.clear(); _contract = null; _frame.clear(); _result_hit_review.clear(); _started = false
	_clear_result_queue()
	var reasons := {"version":"游戏版本不同，请使用相同版本。","content":"游戏内容不同，请使用相同工程文件。","full":"队伍已满。","busy":"队伍正在出征，回到大厅后可重新加入。","locked":"尚未解锁队伍选择的难度。","timeout":"连接超时。","host_left":"与房主的连接已断开。","packet":"连接消息无效，已断开。","cancelled":"出征已取消。"}
	reasons[Transport.LINK_DISCONNECTED] = "网络连接已断开，可重新创建或加入队伍。"
	if _awaiting_join_roster and reason in [Transport.LINK_DISCONNECTED,"host_left"]:
		reasons[reason] = "加入队伍前连接已断开，请核对房主地址和端口后重试。"
	var fallback: String = "已取消连接。" if _awaiting_join_roster and reason == "left" else "已离开队伍。"
	_awaiting_join_roster = false
	note = reasons.get(reason,fallback)+(" 结算正在保存，请稍候。" if saving() else (" 结算尚未保存，可重试。" if pending_save() else ""))
	var lobby_path: String = "res://game/main/party_lobby.tscn"
	var scene: Node = get_tree().current_scene if is_inside_tree() else null
	var in_lobby: bool = (is_instance_valid(scene) and not scene.is_queued_for_deletion()
		and scene.scene_file_path == lobby_path and scene.get("service") == self)
	if not _navigation_fault and (not in_lobby or navigation_pending()):
		_route(lobby_path)
	changed.emit()


func _continues_progress(value: Dictionary) -> bool:
	if _frame.is_empty(): return true
	return (int(value["room"]) >= int(_frame["room"])
		and int(value["kills"]) >= int(_frame["kills"])
		and int(value["gold"]) >= int(_frame["gold"])
		and roundi(float(value["elapsed"])*1000.0) >= roundi(float(_frame["elapsed"])*1000.0))


func _on_frame(value: Dictionary) -> void:
	if not _started or _contract == null or _result_tick >= 0 or not _contract.valid_frame(value,local_id()): return
	if not _frame.is_empty() and int(value["tick"]) <= int(_frame["tick"]): return
	if not _continues_progress(value): return
	_frame = value.duplicate(true); frame_received.emit(_frame.duplicate(true))


func _on_result(value: Dictionary,hit_review: Array=[]) -> void:
	if not _started or _contract == null or _result_tick >= 0 or value["phase"] not in [2,3] or not _contract.valid_frame(value,local_id()): return
	if not _frame.is_empty() and int(value["tick"]) < int(_frame["tick"]): return
	if not _continues_progress(value): return
	_result_tick = int(value["tick"]); _frame = value.duplicate(true)
	_result_hit_review = hit_review.duplicate(true) if HitReview.agrees_with_terminal(hit_review,value,_contract.plan["dungeon"]) else []
	_pending_result = {"run":_manifest["run"],"frame":value.duplicate(true)}
	frame_received.emit(_frame.duplicate(true)); retry_save()


func retry_save() -> bool:
	if not pending_save(): return true
	if saving(): return false
	var identifier: String = _pending_result["run"]
	var result: Dictionary = _session.bank_party(identifier,_pending_result["frame"])
	if not result.get("pending",false):
		_on_save_finished(identifier,result)
		return result["ok"]
	note = str(result["message"])
	changed.emit(); return false


func _on_save_finished(identifier: String,result: Dictionary) -> void:
	if _pending_result.is_empty() or _pending_result["run"] != identifier: return
	if result["ok"]:
		_pending_result.clear()
		note = _session.last_receipt
		committed_save_hint = "本机奖励已入账，档案只读，请重启核对。" if not _session.recovery_notice.is_empty() else ""
		var view := snapshot()
		if view.get("phase") == "lobby":
			_on_room(view)
			return
	else:
		note = str(result["message"])
	changed.emit()


func publish(value: Dictionary,finished: bool=false,hit_review: Array=[]) -> bool:
	var measured: int = Trace.begin()
	var result: bool = _publish_frame(value,finished,hit_review)
	Trace.end("publish",measured)
	return result


func _publish_frame(value: Dictionary,finished: bool=false,hit_review: Array=[]) -> bool:
	if not hosting() or _contract == null: return false
	if _result_tick >= 0:
		return finished and retry_result_delivery(_manifest.get("run",""))
	var measured: int = Trace.begin()
	var valid_frame: bool = _contract.valid_frame(value,local_id())
	Trace.end("publish_validate",measured)
	if not valid_frame: return false
	if not finished: return transport.publish_frame(value)
	var identifier: String = _manifest["run"]
	_on_result(value,hit_review)
	return retry_result_delivery(identifier)


func retry_result_delivery(identifier: String) -> bool:
	var tick: int = accepted_result_tick(identifier)
	if not hosting() or not _started or _contract == null or tick < 0: return false
	if _result_queued: return true
	# The transport records local enqueue success. Keep partial progress only
	# while the same terminal result still owns this connection.
	var queued_peers: Array[int] = _queued_result_peers.duplicate()
	var sent: bool = transport.publish_frame(_frame,true,_result_hit_review,queued_peers)
	if not _started or accepted_result_tick(identifier) != tick: return false
	_queued_result_peers = queued_peers
	_result_queued = sent
	return sent


func return_to_lobby() -> bool:
	if not hosting() or _result_tick < 0 or pending_save(): return false
	if not retry_result_delivery(_manifest.get("run","")): return false
	return transport.return_to_lobby()


func leave() -> void:
	if transport != null: transport.close()


func send_input(walk: Vector2,room_index: int=0) -> bool: return transport != null and transport.submit_input(walk,room_index)
func send_action(action: String,tick: int,room_index: int=0) -> bool: return transport != null and transport.submit_action(action,tick,room_index)


func restore_pending_result(value: Dictionary,frame: Dictionary) -> void:
	# Session validated the local journal and bound its original run before this call.
	# No connection, scene navigation, battle restart or reward publication occurs.
	_manifest=value.duplicate(true);_pending_result={"run":value["run"],"frame":frame.duplicate(true)}
	_result_tick=int(frame["tick"]);_frame=frame.duplicate(true);_result_hit_review.clear();_started=false
	_clear_result_queue()
	note="上次结算尚未保存，请重试保存；无需重新连接队伍。"
