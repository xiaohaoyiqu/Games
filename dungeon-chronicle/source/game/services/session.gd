extends Node

signal party_save_finished(identifier: String,result: Dictionary)
signal solo_save_finished(identifier: String,result: Dictionary)

const Rules = preload("res://game/equipment/profile_rules.gd")
const Store = preload("res://game/persistence/profile_store.gd")
const PartyRun = preload("res://game/network/party_run.gd")
const PartyWire = preload("res://game/network/party_protocol.gd")
const Characters = preload("res://game/network/party_character.gd")
const QuestGuide = preload("res://game/quests/quest_guide.gd")
const UiFont = preload("res://game/ui/ui_font.gd")
const Practice = preload("res://game/training/guided_practice.gd")
var rules = Rules.new()
var store
var display_preferences: RefCounted
var audio_preferences: RefCounted
var sound_cues: Node
var profile: Dictionary
var tracked_quest_id: String = ""
var active_run_id: String = ""
var run_stats: Dictionary = {}
var last_receipt: String = ""
var _quest_receipt: Dictionary = {}
var last_message: String = ""
var recovery_notice: String = ""
var _profile_load_notice: String = ""
var _temporary_browser_storage: bool = false
var _next_difficulty: String = "normal"
var _active_plan: Dictionary = {}
var _solo_reward_request: Dictionary = {}
var _solo_retry_recorded: bool = false
var _recovered_solo_entry: Dictionary = {}
const SOLO_RECOVERY_ACTION: String = "请在主菜单查看并保存上次单人结算"
var _party_run: RefCounted
var _party_self: int = 0
var _party_node: Node
var _party_layout_catalog: RefCounted
var _party_layout_issues: Dictionary = {}
var _save_thread: Thread
var _save_job: Dictionary = {}
# Main-thread observation for one run; never query the Store from UI during I/O.
var _party_retry_recorded_run: String = ""
var _party_reward_frame: Dictionary = {}
const TOWN_SCENE: String = "res://game/main/town.tscn"
const TRAINING_SCENE: String = "res://game/main/training_room.tscn"
const DUNGEON_SCENE: String = "res://game/main/dungeon.tscn"
const MAIN_MENU_SCENE: String = "res://game/main/main_menu.tscn"
const LOCAL_SCENES: Array[String] = [MAIN_MENU_SCENE,TOWN_SCENE,TRAINING_SCENE,DUNGEON_SCENE,"res://game/main/party_lobby.tscn"]
var _scene_epoch: int = 0
var _scene_loader_owner: int = 0
var _pending_local_scene: String = ""
var _queued_local_failure: Callable
var _equipment_practice: RefCounted
var _equipment_practice_epoch: int=-1
var _practice_handoff: Dictionary = {}
var _quit_requested: bool=false
var _quit_waiting_for_save: bool = false
var _quit_exit_code: int = 0
var _quit_confirmation: ConfirmationDialog


func _ready() -> void:
	if get_node_or_null("/root/Session")==self:get_tree().auto_accept_quit=false
	var directory := OS.get_environment("DUNGEON_PROFILE_DIR")
	if directory.is_empty():
		for argument in OS.get_cmdline_args()+OS.get_cmdline_user_args():
			if argument.begins_with("res://tests/") or argument.begins_with("--verify-"):
				directory = ProjectSettings.globalize_path("res://.toolchain/verification-profiles/manual-"+str(Time.get_ticks_usec()))
				break
	_temporary_browser_storage = directory.is_empty() and OS.has_feature("web") and not OS.is_userfs_persistent()
	store = Store.new(directory)
	# Read saved audio before any scene may request a cue; no startup preview.
	audio_preferences=preload("res://game/persistence/audio_preferences.gd").new(store.folder)
	sound_cues=preload("res://game/audio/sound_cues.gd").new()
	sound_cues.name="SoundCues";add_child(sound_cues)
	audio_preferences.changed.connect(_apply_audio_preferences)
	_apply_audio_preferences()
	display_preferences=preload("res://game/persistence/display_preferences.gd").new(store.folder)
	profile = store.load_profile()
	last_message = store.last_error
	if store.read_only:
		_profile_load_notice = "角色档案只读 · "+store.last_error
	elif store.recovered:
		_profile_load_notice = store.last_error
	set_process(false)
	if not configuration_ready():
		store.read_only = true; recovery_notice = configuration_error(); last_message = recovery_notice; return
	if not _restore_solo_settlement(): _restore_party_settlement()


func _process(_delta: float) -> void:
	if _save_thread != null and not _save_thread.is_alive(): _finish_save(true)
	if not _pending_local_scene.is_empty() and _scene_loader_owner==0:
		var path: String = _pending_local_scene
		var on_failure := _queued_local_failure
		var epoch := _scene_epoch
		_pending_local_scene = ""
		_queued_local_failure = Callable()
		var status: Error = _change_local_now(path)
		if status != OK and epoch == _scene_epoch:
			var handled := false
			var scene := get_tree().current_scene
			var receiver := on_failure.get_object() as Node if on_failure.is_valid() else null
			if scene != null and not scene.is_queued_for_deletion() and receiver != null and not receiver.is_queued_for_deletion() and (receiver == scene or scene.is_ancestor_of(receiver)):
				handled = on_failure.call(last_message)
			if not handled: party_service()._navigation_error(_party_node._route_generation)
	set_process(_save_thread!=null or not _pending_local_scene.is_empty())


func _apply_audio_preferences() -> void:
	if not is_instance_valid(audio_preferences) or not is_instance_valid(sound_cues):return
	# Mute before updating the gain; never unmute at an old louder level.
	if not audio_preferences.enabled():sound_cues.set_enabled(false)
	sound_cues.set_level(audio_preferences.level())
	if audio_preferences.enabled():sound_cues.set_enabled(true)


func _unhandled_input(event: InputEvent) -> void:
	if get_node_or_null("/root/Session")!=self or _quit_requested or event.is_echo():return
	if InputMap.has_action("toggle_sound") and event.is_action_pressed("toggle_sound") and is_instance_valid(sound_cues):
		audio_preferences.set_enabled(not audio_preferences.enabled());get_viewport().set_input_as_handled()


func _notification(what: int) -> void:
	if what==NOTIFICATION_WM_CLOSE_REQUEST and is_inside_tree() and get_node_or_null("/root/Session")==self:
		request_quit()


func request_quit(exit_code: int=0) -> void:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id() or not is_inside_tree():return
	if get_node_or_null("/root/Session")!=self or _quit_requested:return
	if exit_code == 0 and _save_thread == null:
		var room: Node = get_tree().current_scene
		if _current_scene_matches(room,DUNGEON_SCENE): room.settle_retreat_for_quit()
		if _save_thread == null and not _unrecorded_settlement().is_empty():
			_show_quit_confirmation.call_deferred()
			return
	_begin_quit(exit_code)


func _unrecorded_settlement() -> String:
	# The writer owns the store until it is joined.
	if _save_thread != null: return ""
	var solo_run: String = active_run_id if not _solo_reward_request.is_empty() else _recovered_solo_entry.get("id","")
	# A finished room can have a rejected reward before there is a candidate to journal.
	if solo_run.is_empty() and not _active_plan.is_empty() and not active_run_id.is_empty() and is_inside_tree():
		var room: Node = get_tree().current_scene
		if _current_scene_matches(room,DUNGEON_SCENE) and room.run_id == active_run_id and not room.rewards_saved and room.run_finished():
			solo_run = active_run_id
	if not solo_run.is_empty() and (not store is Store or not store.has_matching_solo_journal(solo_run)):
		return solo_run
	if _party_run != null and (not _party_reward_frame.is_empty() or (is_instance_valid(_party_node) and _party_node.pending_save())):
		var identifier: String = _party_run.manifest["run"]
		if not party_retry_recorded(identifier) or not store is Store or not store.has_matching_party_journal(identifier):
			return active_run_id
	return ""


func _show_quit_confirmation() -> void:
	if _quit_requested or is_instance_valid(_quit_confirmation): return
	var run: String = _unrecorded_settlement()
	if _save_thread != null or run.is_empty():
		request_quit()
		return
	var dialog := ConfirmationDialog.new()
	dialog.title = "本局结算尚未保存"
	dialog.dialog_text = "本局结算尚未入账，也未确认已写入恢复记录。关闭游戏后可能无法找回。"
	dialog.ok_button_text = "仍然退出"
	dialog.cancel_button_text = "继续留在游戏"
	dialog.dialog_autowrap = true
	dialog.dialog_hide_on_ok = false
	var font := UiFont.acquire()
	dialog.get_label().add_theme_font_override("font",font)
	dialog.get_label().add_theme_font_size_override("font_size",18)
	for button: Button in [dialog.get_ok_button(),dialog.get_cancel_button()]:
		button.add_theme_font_override("font",font)
		button.add_theme_font_size_override("font_size",18)
	_quit_confirmation = dialog
	dialog.canceled.connect(_dismiss_quit_confirmation.bind(dialog))
	dialog.confirmed.connect(_confirm_unsaved_quit.bind(dialog,run))
	dialog.visibility_changed.connect(func():
		if not dialog.visible: _dismiss_quit_confirmation(dialog))
	dialog.popup_exclusive_centered_clamped(self,Vector2i(500,165),0.90)
	if dialog.visible:
		dialog.get_cancel_button().grab_focus()
	else:
		_dismiss_quit_confirmation(dialog)


func _dismiss_quit_confirmation(dialog: ConfirmationDialog) -> void:
	if _quit_confirmation != dialog: return
	_quit_confirmation = null
	dialog.hide()
	dialog.queue_free()


func _confirm_unsaved_quit(dialog: ConfirmationDialog,run: String) -> void:
	if _quit_confirmation != dialog or not dialog.visible: return
	_dismiss_quit_confirmation(dialog)
	if _save_thread == null and _unrecorded_settlement() == run:
		_begin_quit(0)
	else:
		request_quit()


func _begin_quit(exit_code: int) -> void:
	if is_instance_valid(_quit_confirmation): _dismiss_quit_confirmation(_quit_confirmation)
	_quit_requested = true
	_pending_local_scene = ""
	_queued_local_failure = Callable()
	if _save_thread != null:
		_quit_waiting_for_save = true
		_quit_exit_code = exit_code
		return
	_drain_and_quit(exit_code)


func _drain_and_quit(exit_code: int) -> void:
	get_tree().paused=true
	var released: bool=true
	if is_instance_valid(sound_cues):released=await sound_cues.drain()
	if not released:push_error("Owned audio playback drain timed out")
	get_tree().quit(exit_code if released or exit_code!=0 else 2)


func _exit_tree() -> void:
	# Closing waits for this owned writer; no callbacks enter scenes being destroyed.
	if _save_thread != null and _save_thread.is_started(): _finish_save(false)


func menu_status_hint(navigation_hint: String) -> String:
	if not configuration_ready(): return configuration_error()
	if local_scene_pending(): return "正在切换场景，请稍候……"
	if not recovery_notice.is_empty(): return recovery_notice
	if not _profile_load_notice.is_empty(): return _profile_load_notice
	if _temporary_browser_storage: return "浏览器未提供持久存储，关闭页面后可能丢失进度。"
	return navigation_hint


func configuration_ready() -> bool:
	return rules.available() and (not store is Store or store.rules.available())
func configuration_error() -> String:
	if not rules.available(): return rules.configuration_error()
	return store.rules.configuration_error() if store is Store else ""


func party_save_pending() -> bool: return _save_thread != null and not solo_save_pending()
func solo_save_pending() -> bool: return _save_thread != null and bool(_save_job.get("solo",false))
func party_retry_recorded(identifier: String) -> bool:
	return not identifier.is_empty() and _party_retry_recorded_run == identifier


func scene_epoch() -> int: return _scene_epoch
func local_scene_pending() -> bool: return not _pending_local_scene.is_empty()
func scene_loader_busy() -> bool: return _scene_loader_owner!=0
func acquire_scene_loader(owner: int) -> bool:
	if owner==0 or _scene_loader_owner!=0 or local_scene_pending(): return false
	_scene_loader_owner=owner; return true
func release_scene_loader(owner: int) -> void:
	if _scene_loader_owner==owner: _scene_loader_owner=0


func change_local_scene(path: String,on_queued_failure: Callable=Callable()) -> Error:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id(): return ERR_UNAUTHORIZED
	if _quit_requested:return ERR_BUSY
	if solo_save_pending():
		last_message = "正在保存单人结算，请稍候"
		return ERR_BUSY
	if path not in LOCAL_SCENES: return ERR_INVALID_PARAMETER
	if path not in ["res://game/main/main_menu.tscn","res://game/main/training_room.tscn"] and not configuration_ready():
		last_message = configuration_error(); return ERR_UNAVAILABLE
	if is_instance_valid(_party_node) and _party_node.pending_save() and path not in ["res://game/main/main_menu.tscn","res://game/main/party_lobby.tscn"]:
		last_message="请先保存组队结算，再进入其他场景。"; return ERR_BUSY
	_clear_equipment_practice()
	_practice_handoff.clear()
	_scene_epoch+=1; _pending_local_scene=path
	_queued_local_failure = on_queued_failure
	if is_instance_valid(_party_node):
		_party_node.leave(); _party_node.invalidate_navigation()
	if _scene_loader_owner!=0:
		last_message="正在切换场景，请稍候……"; set_process(true); return OK
	_pending_local_scene=""
	_queued_local_failure = Callable()
	return _change_local_now(path)


func _change_local_now(path: String) -> Error:
	var status: Error=get_tree().change_scene_to_file(path)
	if status==OK and last_message=="正在切换场景，请稍候……": last_message=""
	elif status!=OK:
		_clear_equipment_practice()
		_practice_handoff.clear()
		last_message="场景加载失败，请返回入口重试。"
	return status


func _current_scene_matches(source: Node,path: String) -> bool:
	return is_instance_valid(source) and source.is_inside_tree() and not source.is_queued_for_deletion() and get_tree().current_scene == source and source.scene_file_path == path


func start_review_practice(source: Node,on_queued_failure: Callable=Callable()) -> Error:
	if OS.get_thread_caller_id() != OS.get_main_thread_id(): return ERR_UNAUTHORIZED
	if not _current_scene_matches(source,DUNGEON_SCENE): return ERR_UNAVAILABLE
	if local_scene_pending():
		last_message = "正在切换场景，请稍候。"
		return ERR_BUSY
	if not source.rewards_saved or not active_run_id.is_empty() or _party_run != null or party_save_pending():
		last_message = "正在保存本局结算，请稍候。" if solo_save_pending() else "请先完成本局结算，再开始练习。"
		return ERR_BUSY
	var lesson: int = source.review_practice_lesson()
	if lesson < 0:
		last_message = "本次受击没有对应练习。"
		return ERR_UNAVAILABLE
	var difficulty: String = str(source.settlement_receipt.get("difficulty","normal"))
	return _change_practice_scene(TRAINING_SCENE,{"kind":"review","lesson":lesson,"difficulty":difficulty},on_queued_failure)


func start_portal_practice(difficulty: String,source: Node,lesson: int=Practice.Lesson.ARROW_DODGE,on_queued_failure: Callable=Callable()) -> Error:
	if OS.get_thread_caller_id() != OS.get_main_thread_id(): return ERR_UNAUTHORIZED
	if not _current_scene_matches(source,TOWN_SCENE) or not is_instance_valid(source.player):
		return ERR_UNAVAILABLE
	if local_scene_pending() or not active_run_id.is_empty() or _party_run != null or party_save_pending():
		last_message = "请先完成当前出征或场景切换。"
		return ERR_BUSY
	var preview: Dictionary = preview_dungeon(difficulty)
	var arrow_practice: bool = lesson == Practice.Lesson.ARROW_DODGE and difficulty == "veteran" and int(preview.get("formation_rooms",0)) > 0
	if difficulty not in ["normal","veteran"] or not preview["unlocked"] or (lesson != Practice.Lesson.WARDEN and not arrow_practice):
		last_message = "当前难度未开放所选练习。"
		return ERR_UNAVAILABLE
	return _change_practice_scene(TRAINING_SCENE,{
		"kind":"portal","lesson":lesson,"difficulty":difficulty,"position":source.player.position},on_queued_failure)


func return_from_practice(source: Node,on_queued_failure: Callable=Callable()) -> Error:
	if OS.get_thread_caller_id() != OS.get_main_thread_id(): return ERR_UNAUTHORIZED
	if not _current_scene_matches(source,TRAINING_SCENE): return ERR_UNAVAILABLE
	var origin: Dictionary = source.practice_context
	var kind: String = str(origin.get("kind",""))
	if source.equipment_practice == null and kind not in ["portal","review"]: return ERR_UNAVAILABLE
	if local_scene_pending():
		last_message = "正在切换场景，请稍候。"
		return ERR_BUSY
	var destination: Dictionary = origin.duplicate()
	if kind in ["inventory","forge"]:
		destination["equipment_trials"] = source.completed_equipment_trials()
	elif kind == "review":
		destination = {"kind":"portal","difficulty":origin["difficulty"]}
	return _change_practice_scene(TOWN_SCENE,destination,on_queued_failure)


func _change_practice_scene(path: String,context: Dictionary,on_queued_failure: Callable=Callable()) -> Error:
	var status: Error = change_local_scene(path,on_queued_failure)
	if status == OK and not context.is_empty():
		_practice_handoff = {"epoch":_scene_epoch,"path":path,"context":context.duplicate()}
		if not local_scene_pending(): last_message = ""
	return status


func take_practice_context(source: Node) -> Dictionary:
	if OS.get_thread_caller_id() != OS.get_main_thread_id(): return {}
	if local_scene_pending() or _practice_handoff.get("epoch",-1) != _scene_epoch: return {}
	if not _current_scene_matches(source,_practice_handoff["path"]): return {}
	var context: Dictionary = _practice_handoff["context"]
	_practice_handoff.clear()
	return context


func _clear_equipment_practice() -> void:
	_equipment_practice=null;_equipment_practice_epoch=-1


func start_equipment_practice(id: String,source: Node,return_view: Dictionary={},on_queued_failure: Callable=Callable()) -> Error:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id():return ERR_UNAUTHORIZED
	if not configuration_ready():last_message=configuration_error();return ERR_UNAVAILABLE
	if party_save_pending() or _party_run!=null or not active_run_id.is_empty():
		last_message="请先完成并保存当前出征，再试用装备。";return ERR_BUSY
	if local_scene_pending():last_message="场景正在切换，请稍候。";return ERR_BUSY
	if not _current_scene_matches(source,TOWN_SCENE):
		last_message="请从城镇的装备对比开始试用。";return ERR_UNAVAILABLE
	var origin: Dictionary = {}
	if not return_view.is_empty():
		var kind: String = str(return_view.get("kind",""))
		var filter: String = str(return_view.get("filter",""))
		var inventory_page: Variant = return_view.get("inventory_page",0)
		var shop_page: Variant = return_view.get("shop_page",0)
		if (not is_instance_valid(source.player) or kind not in ["inventory","forge"]
			or filter not in ["all","weapon","armor","charm"] or not inventory_page is int or not shop_page is int):
			last_message = "无法保留当前装备对比，请重新选择。"
			return ERR_INVALID_PARAMETER
		if not source.equipment_choice_available(kind,id):
			last_message = "背包只试用已拥有的装备。" if kind == "inventory" else "铁匠铺只试用已拥有或在售的装备。"
			return ERR_INVALID_PARAMETER
		origin = {"kind":kind,"item":id,"filter":filter,"position":source.player.position,
			"inventory_page":maxi(0,inventory_page),"shop_page":maxi(0,shop_page)}
	var practice:=preload("res://game/training/equipment_practice.gd").new(rules,profile,id)
	if not practice.available():last_message=practice.reason();return ERR_INVALID_PARAMETER
	# SceneTree removes the old scene now and adds the new one at frame end.
	# Stage only after acceptance; the epoch also covers a queued local handoff.
	var status: Error = _change_practice_scene(TRAINING_SCENE,origin,on_queued_failure)
	if status==OK:
		_equipment_practice=practice;_equipment_practice_epoch=_scene_epoch
	return status


func take_equipment_practice(source: Node) -> RefCounted:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id():return null
	if not _current_scene_matches(source,"res://game/main/training_room.tscn"):return null
	if local_scene_pending():return null
	var practice: RefCounted=_equipment_practice if _equipment_practice_epoch==_scene_epoch else null
	_clear_equipment_practice()
	return practice


func set_tracked_quest(id: String) -> bool:
	if not id.is_empty() and (not rules.quest_defs.has(id) or not QuestGuide.trackable(profile,id)): return false
	tracked_quest_id = id
	return true


func act(command: String,id: String) -> Dictionary:
	if has_recovered_solo_settlement(): return {"ok":false,"message":SOLO_RECOVERY_ACTION}
	if _party_run != null: return {"ok":false,"message":"组队出征或待保存结算期间不能更改角色"}
	if not active_run_id.is_empty(): return {"ok":false,"message":"请先保存当前出征的结算，再更改角色"}
	var result: Dictionary = _commit(rules.act(profile,command,id))
	if result["ok"] and command == "claim" and id == tracked_quest_id:
		tracked_quest_id = ""
	return result


func _commit(result: Dictionary,solo_entry: Dictionary={}) -> Dictionary:
	if not configuration_ready(): return {"ok":false,"message":configuration_error()}
	if _save_thread != null or _quit_requested: return {"ok":false,"message":"正在保存结算或关闭游戏，请稍候"}
	if not result["ok"]:
		last_message = result["message"]
		return result
	var revision: int = int(profile["revision"])
	var saved: bool = (
		store.save_profile(result["data"],revision) if solo_entry.is_empty()
		else store.save_solo_profile(result["data"],revision,solo_entry)
	)
	if not saved:
		last_message = store.last_error
		return {"ok":false,"message":last_message}
	return _publish_profile(result["data"],result["message"])


func _publish_profile(data: Dictionary,message: String) -> Dictionary:
	_profile_load_notice = ""
	profile = data
	profile["revision"] = int(profile["revision"])+1
	last_message = message
	return {"ok":true,"message":last_message}


func quest_progress_receipt(identifier: String) -> Dictionary:
	if identifier.is_empty() or _quest_receipt.get("id") != identifier: return {}
	return _quest_receipt["progress"].duplicate()


func _record_quest_receipt(identifier: String,previous_quests: Dictionary) -> void:
	_quest_receipt = {"id":identifier,"progress":QuestGuide.committed_increments(previous_quests,profile["quests"])}


func preview_dungeon(difficulty: String) -> Dictionary:
	var preview: Dictionary = rules.dungeons.preview(difficulty)
	if preview.is_empty():
		var reason: String = rules.dungeons.error_message()
		return {"available":false,"unlocked":false,"message":reason if not reason.is_empty() else "副本配置暂不可用"}
	preview["available"] = true
	preview["unlocked"] = rules.valid(profile) and rules.dungeons.unlocked(difficulty,int(rules.level_info(profile)["level"]),int(profile["clears"]))
	preview["message"] = "" if preview["unlocked"] else "需达到 Lv.%d，并完成 %d 次通关" % [preview["unlock_level"],preview["unlock_clears"]]
	return preview


func prepare_run(difficulty: String="normal") -> Dictionary:
	if not configuration_ready(): return {"ok":false,"message":configuration_error()}
	if has_recovered_solo_settlement(): return {"ok":false,"message":SOLO_RECOVERY_ACTION}
	if _save_thread != null or _quit_requested: return {"ok":false,"message":"正在保存结算或关闭游戏，请稍候"}
	if store.read_only: return {"ok":false,"message":store.last_error}
	if rules.at_revision_limit(profile): return {"ok":false,"message":Rules.REVISION_LIMIT_MESSAGE}
	if not active_run_id.is_empty(): return {"ok":false,"message":"当前出征尚未结算，请先完成结算"}
	var preview := preview_dungeon(difficulty)
	if not preview["unlocked"]: return {"ok":false,"message":preview["message"]}
	_next_difficulty = difficulty
	return {"ok":true,"message":""}


func active_plan() -> Dictionary:
	return _active_plan.duplicate(true)


func begin_run() -> String:
	var checked := prepare_run(_next_difficulty)
	if not checked["ok"]:
		last_message = checked["message"]
		return ""
	_active_plan = rules.dungeons.plan(_next_difficulty)
	_solo_reward_request.clear()
	_solo_retry_recorded = false
	active_run_id = str(Time.get_ticks_usec())+"-"+Crypto.new().generate_random_bytes(12).hex_encode()
	run_stats = rules.stats(profile)
	last_receipt = ""
	_quest_receipt.clear()
	return active_run_id


func bank_run(id: String,kills: int,gold: int,cleared: bool,background: bool=false) -> Dictionary:
	if OS.get_thread_caller_id() != OS.get_main_thread_id() or _quit_requested:
		return {"ok":false,"message":"当前无法开始结算保存"}
	if _party_run != null: return {"ok":false,"message":"组队出征需要对应的房主结算"}
	if id.is_empty() or id != active_run_id: return {"ok":false,"message":"这一局已经结算"}
	if _active_plan.is_empty(): return {"ok":false,"message":"出征记录不完整，本次未结算"}
	var request := {"kills":kills,"gold":gold,"cleared":cleared}
	if not _solo_reward_request.is_empty() and request != _solo_reward_request:
		return {"ok":false,"message":"本局结算已固定，请使用原结果重试"}
	if _save_thread != null: return {"ok":false,"pending":solo_save_pending(),"message":"正在保存结算，请稍候……"}
	var terms: Dictionary = _active_plan["preview"]
	var candidate: Dictionary = rules.settle(profile,id,kills,gold,cleared,terms["id"])
	var intent: Dictionary = {}
	if candidate["ok"]:
		_solo_reward_request = request
		if store is Store:
			intent = store.solo_intent(profile,candidate["data"],id,terms["id"],request)
	return _submit_solo(id,terms,candidate,intent,false,background)


func _submit_solo(id: String,terms: Dictionary,candidate: Dictionary,intent: Dictionary,recovered: bool,background: bool) -> Dictionary:
	var before: Dictionary = profile
	var job := {"solo":true,"run":id,"terms":terms,"before":before,"recovered":recovered,"journaled":not intent.is_empty()}
	if background and candidate["ok"] and configuration_ready() and _save_threads_available():
		job["data"] = candidate["data"]
		job["message"] = candidate["message"]
		var data: Dictionary = candidate["data"].duplicate(true)
		var revision: int = int(before["revision"])
		var write: Callable = store.save_profile.bind(data,revision)
		if not intent.is_empty(): write = store.save_solo_profile.bind(data,revision,intent.duplicate(true))
		var started: Dictionary = _start_save(job,write)
		# Preserve the Web fallback if a supported worker cannot be started.
		if started.get("pending",false) or not OS.has_feature("web"):
			if not started.get("pending",false): return _finish_solo_result(job,started)
			return started
	return _finish_solo_result(job,_commit(candidate,intent))


func _finish_solo_result(job: Dictionary,result: Dictionary) -> Dictionary:
	var recovered: bool = job["recovered"]
	if not result["ok"]:
		result["retry_recorded"] = job["journaled"] and store.solo_retry_recorded
		if not recovered: _solo_retry_recorded = result["retry_recorded"]
		if recovered: recovery_notice = "单人结算未保存 · "+str(result["message"])
		return result
	var identifier: String = job["run"]
	var before: Dictionary = job["before"]
	var terms: Dictionary = job["terms"]
	_record_quest_receipt(identifier,before["quests"])
	if store is Store and store.read_only:
		recovery_notice = store.last_error
		result["save_warning"] = store.last_error
	var receipt: Dictionary = _solo_receipt(identifier,terms,before,profile)
	var fragments: String = "  碎片 +%d" % receipt["materials"] if receipt["materials"] > 0 else ""
	last_receipt = "%s · 金币 +%d  经验 +%d%s" % [terms["name"],receipt["gold"],receipt["xp"],fragments]
	result["receipt"] = receipt
	if recovered:
		_recovered_solo_entry.clear()
		recovery_notice = store.last_error if store.read_only else "上次单人结算已保存 · "+last_receipt
	else:
		_clear_solo_run()
	return result


func _clear_solo_run() -> void:
	active_run_id = ""
	_active_plan.clear()
	_solo_reward_request.clear()
	_solo_retry_recorded = false


func _solo_receipt(id: String,terms: Dictionary,before: Dictionary,after: Dictionary) -> Dictionary:
	var level_before: int = int(rules.level_info(before)["level"])
	var level_after: int = int(rules.level_info(after)["level"])
	var elite_before: bool = rules.dungeons.unlocked("veteran",level_before,int(before["clears"]))
	var elite_after: bool = rules.dungeons.unlocked("veteran",level_after,int(after["clears"]))
	var items_added: Array[String] = []
	for item_id: String in after["owned"]:
		if not before["owned"].has(item_id): items_added.append(item_id)
	return {
		"id":id,"difficulty":terms["id"],
		"gold":int(after["gold"])-int(before["gold"]),
		"xp":int(after["xp"])-int(before["xp"]),
		"materials":int(after["materials"])-int(before["materials"]),
		"level_before":level_before,"level_after":level_after,
		"elite_unlocked":not elite_before and elite_after,"items":items_added
	}


func party_service() -> Node:
	if not is_instance_valid(_party_node):
		_party_node = load("res://game/network/party_service.gd").new(self)
		add_child(_party_node)
	return _party_node


func party_entry_issue() -> String:
	if not configuration_ready():
		var reason: String = configuration_error()
		return reason if not reason.is_empty() else "出征配置暂不可用。"
	if has_recovered_solo_settlement(): return SOLO_RECOVERY_ACTION
	if local_scene_pending(): return "场景正在切换，请稍候。"
	if _save_thread != null or _quit_requested: return "正在保存结算或关闭游戏，请稍候。"
	if store.read_only:
		return store.last_error if not store.last_error.is_empty() else "本机档案不可写，请返回入口检查档案。"
	if rules.at_revision_limit(profile): return Rules.REVISION_LIMIT_MESSAGE
	if not active_run_id.is_empty(): return "当前出征尚未结算，请先完成结算。"
	return ""


func party_layout_issue(mode: String,players: int) -> String:
	if mode not in ["normal","veteran"]: return "组队难度无效，请重新选择。"
	if players < 2 or players > PartyWire.MAX_PLAYERS: return "队伍人数超出出征范围。"
	# Plans only change when the catalog is replaced.
	var catalog: RefCounted = rules.dungeons
	if catalog != _party_layout_catalog:
		_party_layout_catalog = catalog
		_party_layout_issues.clear()
	var key: String = mode+":"+str(players)
	if not _party_layout_issues.has(key):
		_party_layout_issues[key] = PartyRun.layout_issue(catalog.plan(mode),players)
	return _party_layout_issues[key]


func begin_party(value: Dictionary,self_id: int) -> Dictionary:
	return _bind_party(value,self_id,false)


func _bind_party(value: Dictionary,self_id: int,recovering: bool) -> Dictionary:
	var issue: String = party_entry_issue()
	if not issue.is_empty(): return {"ok":false,"message":issue}
	var characters := Characters.new(rules)
	var contract := PartyRun.new(characters,rules.dungeons,value)
	if not contract.available() or not contract.stats.has(self_id): return {"ok":false,"message":"组队出征配置不完整"}
	if not recovering:
		var layout_issue: String = party_layout_issue(contract.manifest["mode"],contract.manifest["members"].size())
		if not layout_issue.is_empty(): return {"ok":false,"message":layout_issue}
	var own: Dictionary = characters.describe(profile)
	var found: bool = false
	for member in contract.manifest["members"]:
		if int(member["peer"]) == self_id: found = member["character"] == own
	if not found or not preview_dungeon(value["mode"])["unlocked"]: return {"ok":false,"message":"角色或难度与出征记录不一致，请重新组队"}
	_party_run = contract; _party_self = self_id; _party_retry_recorded_run = ""
	active_run_id = "party-"+value["run"]
	run_stats = rules.stats(profile)
	last_receipt = ""
	_quest_receipt.clear()
	return {"ok":true,"message":""}


func cancel_party(identifier: String) -> bool:
	if party_save_pending() or not _party_reward_frame.is_empty(): return false
	if _party_run == null or _party_run.manifest["run"] != identifier: return false
	_party_run = null; _party_self = 0; active_run_id = ""; _party_retry_recorded_run = ""
	return true


func bank_party(identifier: String,frame: Dictionary) -> Dictionary:
	if OS.get_thread_caller_id() != OS.get_main_thread_id(): return {"ok":false,"message":"当前无法开始结算保存"}
	if _quit_requested: return {"ok":false,"message":"正在关闭游戏"}
	if _party_run == null or identifier != _party_run.manifest["run"]: return {"ok":false,"message":"这一局已经结算或不属于本次出征"}
	if not _party_run.valid_frame(frame,_party_self) or frame["phase"] not in [2,3]: return {"ok":false,"message":"组队结算与出征记录不一致"}
	if not _party_reward_frame.is_empty() and frame != _party_reward_frame:
		return {"ok":false,"message":"本局组队结算已固定，请使用原结果重试"}
	if _save_thread != null: return {"ok":false,"pending":party_save_pending(),"message":"正在保存结算，请稍候……"}
	if _party_reward_frame.is_empty(): _party_reward_frame = frame.duplicate(true)
	var mode: String = _party_run.manifest["mode"]
	var result: Dictionary = rules.settle(profile,active_run_id,int(frame["kills"]),int(frame["gold"]),frame["phase"] == 2,mode)
	if not result["ok"]: last_message = result["message"]; return result
	var job := {"run":identifier,"mode":mode,"gold":int(frame["gold"]),"xp":int(profile["xp"]),"materials":int(profile["materials"]),
		"data":result["data"],"message":result["message"]}
	# Only the RefCounted store and a private value copy enter the worker.
	var data: Dictionary = result["data"].duplicate(true)
	var callback: Callable
	if store is Store:
		var intent: Dictionary=store.party_intent(profile,_party_run.manifest,_party_self,frame)
		callback=store.save_party_profile.bind(data,int(profile["revision"]),intent)
	else:
		callback=store.save_profile.bind(data,int(profile["revision"]))
	if not _save_threads_available():
		return _finish_party_save(job,bool(callback.call()),false)
	var started: Dictionary = _start_save(job,callback)
	if started.get("pending",false): _party_retry_recorded_run = ""
	return started


func _save_threads_available() -> bool:
	return OS.has_feature("threads")


func _start_save(job: Dictionary,write: Callable) -> Dictionary:
	var thread := Thread.new()
	if thread.start(write) != OK:
		last_message = "无法启动结算保存，请重试"
		return {"ok":false,"message":last_message}
	_save_job = job
	_save_thread = thread
	set_process(true)
	last_message = "正在保存结算，请稍候……"
	return {"ok":false,"pending":true,"message":last_message}


func _finish_save(notify: bool) -> void:
	var saved: bool = bool(_save_thread.wait_to_finish())
	var job: Dictionary = _save_job
	_save_thread = null
	_save_job = {}
	set_process(local_scene_pending())
	var was_waiting: bool = _quit_waiting_for_save
	_quit_waiting_for_save = false
	var exit_code: int = _quit_exit_code
	_quit_exit_code = 0
	var close_now: bool = notify and was_waiting and (saved or exit_code != 0)
	var close_canceled: bool = notify and was_waiting and not close_now
	if close_canceled: _quit_requested = false
	var publish_to_scene: bool = notify and not _quit_requested
	if bool(job.get("solo",false)):
		var result: Dictionary
		if saved:
			result = _publish_profile(job["data"],job["message"])
		else:
			last_message = _save_failure_message(close_canceled)
			result = {"ok":false,"message":last_message}
		result = _finish_solo_result(job,result)
		if publish_to_scene: solo_save_finished.emit(job["run"],result)
	else:
		var result := _finish_party_save(job,saved,close_canceled)
		if publish_to_scene: party_save_finished.emit(job["run"],result)
	if close_now: _drain_and_quit(exit_code)


func _save_failure_message(close_canceled: bool) -> String:
	var error: String = store.last_error if not store.last_error.is_empty() else "结算保存失败，请重试"
	return "退出已取消 · "+error if close_canceled else error


func _finish_party_save(job: Dictionary,saved: bool,close_canceled: bool) -> Dictionary:
	var identifier: String = job["run"]
	# Called after the store returns, or after joining an asynchronous write.
	_party_retry_recorded_run = identifier if not saved and store is Store and store.party_retry_recorded else ""
	if saved:
		var quests_before: Dictionary = profile["quests"]
		_publish_profile(job["data"],job["message"])
		_record_quest_receipt(active_run_id,quests_before)
		last_receipt = "组队%s · 金币 +%d  经验 +%d  碎片 +%d" % [rules.dungeons.preview(job["mode"])["name"],job["gold"],
			int(profile["xp"])-int(job["xp"]),int(profile["materials"])-int(job["materials"])]
		_party_run = null; _party_self = 0; active_run_id = ""
		_party_reward_frame.clear()
		recovery_notice = store.last_error if store.read_only else ""
	else:
		last_message = _save_failure_message(close_canceled)
	return {"ok":saved,"message":last_message}


func defer_solo_settlement(source: Node) -> Error:
	if OS.get_thread_caller_id() != OS.get_main_thread_id(): return ERR_UNAUTHORIZED
	if not _current_scene_matches(source,DUNGEON_SCENE): return ERR_UNAVAILABLE
	if _save_thread != null or _quit_requested or local_scene_pending() or scene_loader_busy():
		last_message = "请等待当前保存或场景切换结束。"
		return ERR_BUSY
	if not (_solo_retry_recorded and store is Store and source.run_finished() and source.run_id == active_run_id):
		last_message = "尚未确认本局待办已写入，请在此重试保存。"
		return ERR_UNAVAILABLE
	var entry: Dictionary = store.recorded_solo_entry(active_run_id)
	if entry.is_empty():
		last_message = "本局待办已变化或无法读取，请留在这里核对后重试。"
		return ERR_INVALID_DATA
	# Publish only after the immediate scene change has been accepted.
	var status: Error = change_local_scene(MAIN_MENU_SCENE)
	if status == OK:
		_show_recovered_solo_entry(entry)
		_clear_solo_run()
		last_message = recovery_notice
	return status


func has_recovered_solo_settlement() -> bool:
	return not _recovered_solo_entry.is_empty()


func recovered_solo_preview() -> Dictionary:
	if not has_recovered_solo_settlement(): return {}
	var entry: Dictionary = _recovered_solo_entry
	var terms: Dictionary = rules.dungeons.preview(entry["difficulty"])
	if terms.is_empty(): return {}
	var base: Dictionary = entry["base"]
	var target: Dictionary = entry["target"]
	var view: Dictionary = _solo_receipt(entry["id"],terms,base,target)
	view["difficulty_name"] = terms["name"]
	view["kills"] = int(entry["result"]["kills"])
	view["cleared"] = bool(entry["result"]["cleared"])
	var item_names := PackedStringArray()
	for id: String in view["items"]:
		item_names.append(str(rules.catalog[id]["name"]))
	view["item_names"] = item_names
	var quests: Array[Dictionary] = []
	var increments: Dictionary = QuestGuide.committed_increments(base["quests"],target["quests"])
	for id: String in rules.quest_defs:
		if not increments.has(id): continue
		quests.append({
			"name":rules.quest_defs[id]["name"],"before":int(base["quests"][id]["progress"]),
			"after":int(target["quests"][id]["progress"]),"goal":int(rules.quest_defs[id]["goal"]),
			"claimable":int(target["quests"][id]["state"]) == 2
		})
	view["quests"] = quests
	return view


func retry_recovered_solo_settlement(reviewed_id: String,background: bool=false) -> Dictionary:
	if OS.get_thread_caller_id() != OS.get_main_thread_id() or not has_recovered_solo_settlement():
		return {"ok":false,"message":"没有可重试的单人结算"}
	if reviewed_id.is_empty() or _recovered_solo_entry["id"] != reviewed_id:
		return {"ok":false,"message":"待保存的出征已变化，请重新打开结算核对"}
	if _save_thread != null: return {"ok":false,"pending":solo_save_pending(),"message":"正在保存结算，请稍候……"}
	if local_scene_pending() or _quit_requested or not active_run_id.is_empty():
		return {"ok":false,"message":"正在处理场景或其他出征，请稍候"}
	var entry: Dictionary = _recovered_solo_entry
	var terms: Dictionary = rules.dungeons.preview(entry["difficulty"])
	# The writer revalidates frozen results and the exact journal bytes on every retry.
	var target: Dictionary = rules.normalized_profile(entry["target"])
	if target.is_empty(): return {"ok":false,"message":"待保存的角色档案无法读取，请重新核对结算"}
	var candidate := {"ok":true,"data":target,"message":"本局奖励已存入角色档案"}
	return _submit_solo(entry["id"],terms,candidate,entry,true,background)


func _restore_solo_settlement() -> bool:
	if not store is Store: return false
	var result: Dictionary = store.pending_solo(profile)
	match result["state"]:
		"blocked":
			recovery_notice = store.last_error if not store.last_error.is_empty() else "无法核对上次单人结算，原文件已保留"
		"pending":
			_show_recovered_solo_entry(result["entry"])
		"applied":
			recovery_notice = "上次单人结算已入账，已清理待办"
		_:
			return false
	last_message = recovery_notice
	return result["state"] != "applied"


func _show_recovered_solo_entry(entry: Dictionary) -> void:
	_recovered_solo_entry = entry
	var terms: Dictionary = rules.dungeons.preview(entry["difficulty"])
	var outcome: Dictionary = entry["result"]
	recovery_notice = "上次单人出征待保存 · %s · 金币 %d / 击败 %d" % [terms["name"],int(outcome["gold"]),int(outcome["kills"])]


func _restore_party_settlement() -> void:
	if not store is Store:return
	var result: Dictionary=store.pending_party(profile)
	if result["state"]=="blocked":recovery_notice=store.last_error;last_message=recovery_notice;return
	if result["state"] == "applied":
		recovery_notice = "上次组队结算已入账，已清理待办。"
		last_message = recovery_notice
		return
	if result["state"]!="pending":return
	var value: Dictionary=result["entry"]
	var wire=PartyWire.new(Characters.new(rules))
	# Reuse the validated protocol normalization for JSON character integers.
	value["manifest"]=wire.decode(JSON.stringify(value["manifest"]).to_utf8_buffer())
	preload("res://game/network/battle_frame.gd").normalize(value["frame"])
	var bound: Dictionary=_bind_party(value["manifest"],int(value["self"]),true)
	if not bound["ok"]:
		store.read_only=true;store.last_error="未完成结算无法绑定当前角色，原文件已保留";recovery_notice=store.last_error;return
	_party_reward_frame = value["frame"]
	_party_retry_recorded_run = str(value["manifest"]["run"])
	var service: Node=party_service()
	service.restore_pending_result(value["manifest"],value["frame"])
	recovery_notice="有上次未保存的结算，请进入组队冒险重试保存。";last_message=recovery_notice
