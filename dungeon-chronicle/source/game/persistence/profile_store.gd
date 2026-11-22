extends RefCounted
const Trace = preload("res://game/diagnostics/frame_trace.gd")

const Journal = preload("res://game/persistence/party_settlement_journal.gd")
const SoloJournal = preload("res://game/persistence/solo_settlement_journal.gd")
var journal: RefCounted
var solo_journal: RefCounted
var _solo_retry_entry: Dictionary = {}
var _solo_retry_hash: String = ""
var solo_retry_recorded: bool = false
var _retry_entry: Dictionary={}
var _retry_hash: String=""
# Completed operation metadata; Session reads only after its writer has joined.
var party_retry_recorded: bool = false

const Rules = preload("res://game/equipment/profile_rules.gd")
const MAX_BYTES: int = 262144
var folder: String
var rules = Rules.new()
var read_only: bool = false
var recovered: bool = false
var last_error: String = ""
var last_read_bytes: int = 0
var _loaded: bool = false
var _observed: Dictionary = {}
var _backup_observed: Dictionary = {}
var _revision: int = 0


func _init(directory: String="") -> void:
	folder = directory if not directory.is_empty() else ProjectSettings.globalize_path("user://")
	journal = Journal.new(folder)
	solo_journal = SoloJournal.new(folder,rules)


func _digest(bytes: PackedByteArray) -> String:
	var context := HashingContext.new()
	context.start(HashingContext.HASH_SHA256)
	context.update(bytes)
	return context.finish().hex_encode()


func _snapshot(path: String,parse_profile: bool = true) -> Dictionary:
	last_read_bytes = 0
	if DirAccess.dir_exists_absolute(path):
		return {"state":"blocked","hash":"","value":null}
	var file := FileAccess.open(path,FileAccess.READ)
	if file == null:
		var error := FileAccess.get_open_error()
		var missing := Journal.missing_after_open_error(path,error)
		return {"state":"missing" if missing else "blocked","hash":"","value":null}
	var length := file.get_length()
	if length > MAX_BYTES:
		file.close()
		return {"state":"blocked","hash":"","value":null}
	var bytes := file.get_buffer(length)
	var complete := bytes.size() == length and file.get_length() == length
	file.close()
	last_read_bytes = bytes.size()
	if not complete: return {"state":"blocked","hash":"","value":null}
	var value: Variant = null
	if parse_profile:
		var parser := JSON.new()
		if parser.parse(bytes.get_string_from_utf8()) == OK: value = parser.data
	return {"state":"read","hash":_digest(bytes),"value":value}


func _same(a: Dictionary,b: Dictionary) -> bool:
	return a.get("state") == b.get("state") and a.get("hash") == b.get("hash")


func load_profile() -> Dictionary:
	var measured: int = Trace.begin()
	var result: Dictionary = _load_profile()
	Trace.end("profile_load",measured)
	return result


func _configuration_available() -> bool:
	if rules.available(): return true
	read_only = true; last_error = rules.configuration_error(); return false


func _load_profile() -> Dictionary:
	if not _configuration_available():
		_loaded = true; recovered = false; last_read_bytes = 0
		return rules.fresh()
	var path := folder.path_join("profile.json")
	_observed = _snapshot(path)
	_loaded = true
	read_only = false
	recovered = false
	last_error = ""
	var value: Variant = _observed["value"]
	var accepted: Dictionary = rules.normalized_profile(value)
	if not accepted.is_empty():
		_revision = int(accepted["revision"])
		return accepted
	var version: Variant = value.get("schema_version") if value is Dictionary else null
	if typeof(version) in [TYPE_INT,TYPE_FLOAT] and is_finite(version) and version > Rules.SCHEMA_VERSION and floor(version) == version:
		read_only = true
		last_error = "这个角色档案来自更新版本，已保留原文件"
		return rules.fresh()
	_backup_observed = _snapshot(path+".bak")
	var backup: Dictionary = rules.normalized_profile(_backup_observed["value"])
	if not backup.is_empty():
		recovered = true
		_revision = int(backup["revision"])
		read_only = _observed["state"] == "blocked"
		last_error = "已从上一份备份读取角色档案" if not read_only else "主档案过大或不可读取；备份仅供读取，原文件已保留"
		return backup
	_revision = 0
	if _observed["state"] != "missing" or _backup_observed["state"] != "missing":
		read_only = true
		last_error = "角色档案读取失败，原文件已保留；请先使用训练房"
	return rules.fresh()


func _unchanged(path: String) -> bool:
	if not _same(_observed,_snapshot(path,false)): return false
	return not recovered or _same(_backup_observed,_snapshot(path+".bak",false))


func _abort(temporary: String,message: String) -> bool:
	# Only this writer's randomly named files are removed on an aborted commit.
	for path in [temporary,temporary+".backup"]:
		if FileAccess.file_exists(path): DirAccess.remove_absolute(path)
	last_error = message
	return false


func save_profile(data: Dictionary,expected_revision: int) -> bool:
	var measured: int = Trace.begin()
	var result: bool = _save_profile(data,expected_revision)
	Trace.end("save",measured)
	return result


func _save_profile(data: Dictionary,expected_revision: int) -> bool:
	if not _configuration_available(): return false
	if not _loaded: load_profile()
	if read_only: return false
	if _revision == Rules.MAX_COUNTER and expected_revision == _revision:
		last_error = Rules.REVISION_LIMIT_MESSAGE
		return false
	var next: Dictionary = rules.normalized_profile(data)
	if next.is_empty() or not rules.whole(expected_revision,0,Rules.MAX_COUNTER-1):
		last_error = "角色档案校验失败，未保存"
		return false
	var path := folder.path_join("profile.json")
	if _revision != expected_revision or not _unchanged(path):
		last_error = "角色档案已在外部变化，本次未覆盖；请重新启动读取"
		return false
	if DirAccess.make_dir_recursive_absolute(folder) != OK:
		last_error = "无法创建角色存档目录"
		return false
	next["revision"] = expected_revision+1
	var bytes := JSON.stringify(next,"  ").to_utf8_buffer()
	if bytes.size() > MAX_BYTES:
		last_error = "角色档案超过大小限制，未保存"
		return false
	var nonce := Crypto.new().generate_random_bytes(12).hex_encode()
	var temporary := path+".tmp-"+str(OS.get_process_id())+"-"+nonce
	var file := FileAccess.open(temporary,FileAccess.WRITE)
	if file == null:
		last_error = "角色档案写入失败，当前操作未生效"
		return false
	var written := file.store_buffer(bytes)
	file.flush()
	var error: Error = file.get_error()
	file.close()
	if not written or error != OK: return _abort(temporary,"角色档案写入未完成，当前操作未生效")
	if not _unchanged(path): return _abort(temporary,"角色档案在保存期间发生变化，本次未覆盖")
	if not Journal.staged_bytes_match(temporary,bytes):
		return _abort(temporary,"角色临时档案内容未完整写入，旧档案已保留")
	if _observed["state"] == "read":
		var saved_path := path+".bak" if rules.valid(_observed["value"]) else path+".corrupt-"+nonce
		var backup_stage := temporary+".backup"
		if DirAccess.copy_absolute(path,backup_stage) != OK:
			return _abort(temporary,"无法备份旧档案，当前操作未生效")
		if not _same(_observed,_snapshot(backup_stage,false)):
			return _abort(temporary,"备份副本与原角色档案不一致，已有备份已保留")
		if not _unchanged(path):
			return _abort(temporary,"角色档案在备份期间发生变化，已有备份已保留")
		if DirAccess.rename_absolute(backup_stage,saved_path) != OK:
			return _abort(temporary,"无法完成旧档案备份，当前操作未生效")
	# Optimistic conflict detection. This is not a cross-process transaction lock.
	if not _unchanged(path): return _abort(temporary,"角色档案在替换前发生变化，本次未覆盖")
	if DirAccess.rename_absolute(temporary,path) != OK:
		return _abort(temporary,"无法替换角色档案，旧文件仍保留")
	_observed = {"state":"read","hash":_digest(bytes),"value":next}
	_revision = expected_revision+1
	last_error = ""
	recovered = false
	return true


func pending_solo(profile: Dictionary) -> Dictionary:
	solo_retry_recorded = false
	_solo_retry_entry.clear()
	_solo_retry_hash = ""
	var solo_status: Error = solo_journal.probe()
	if solo_status == ERR_FILE_NOT_FOUND: return {"state":"missing"}
	if solo_status != OK:
		read_only = true
		last_error = "无法核对单人结算待办路径，原文件已保留"
		return {"state":"blocked"}
	if journal.exists():
		read_only = true
		last_error = "发现单人和组队两份未完成结算，原文件均已保留"
		return {"state":"blocked"}
	if read_only or not _configuration_available(): return {"state":"blocked"}
	var result: Dictionary = solo_journal.inspect(profile)
	if result["state"] == "blocked":
		read_only = true
		last_error = solo_journal.last_error
	elif result["state"] == "pending":
		_solo_retry_entry = result["entry"].duplicate(true)
		_solo_retry_hash = result["hash"]
		solo_retry_recorded = true
	return result


func solo_intent(base: Dictionary,target: Dictionary,id: String,difficulty: String,request: Dictionary) -> Dictionary:
	if not _solo_retry_entry.is_empty(): return _solo_retry_entry.duplicate(true)
	return solo_journal.entry(base,target,id,difficulty,request)


func has_matching_solo_journal(identifier: String) -> bool:
	if _solo_retry_hash.is_empty() or _solo_retry_entry.get("id") != identifier or journal.exists():
		return false
	var observed: Dictionary = solo_journal.snapshot()
	return observed["state"] == "read" and observed.get("hash") == _solo_retry_hash


func recorded_solo_entry(identifier: String) -> Dictionary:
	return _solo_retry_entry.duplicate(true) if has_matching_solo_journal(identifier) else {}


func save_solo_profile(data: Dictionary,expected_revision: int,value: Dictionary) -> bool:
	solo_retry_recorded = false
	if not _configuration_available() or read_only: return false
	if journal.exists():
		last_error = "已有未完成的组队结算，原文件已保留"
		return false
	var candidate: Dictionary = solo_journal.candidate(value)
	if candidate.is_empty() or not Journal.same(candidate,data) or int(value["base"]["revision"]) != expected_revision:
		last_error = "待保存单人结算与原角色不对应"
		return false
	if not solo_journal.prepare(value,_solo_retry_hash):
		last_error = solo_journal.last_error
		return false
	var saved: bool = false
	if journal.exists():
		last_error = "发现单人和组队两份未完成结算，原文件均已保留"
	else:
		saved = save_profile(data,expected_revision)
	if not saved:
		# Publish a binding only for a prepared intent whose profile write failed.
		_solo_retry_entry = value.duplicate(true)
		_solo_retry_hash = solo_journal.prepared_hash()
		solo_retry_recorded = true
		return false
	if not solo_journal.clear(value):
		read_only = true
		last_error = solo_journal.last_error
	_solo_retry_entry.clear()
	_solo_retry_hash = ""
	solo_retry_recorded = false
	return true


func pending_party(profile: Dictionary) -> Dictionary:
	party_retry_recorded = false
	if not _configuration_available(): return {"state":"blocked"}
	# Startup only, never called by render/process. A read-only profile stays protected.
	if solo_journal.exists() and journal.exists():
		read_only = true
		last_error = "无法单独核对组队结算，请检查单人和组队待办；原文件均已保留"
		return {"state":"blocked"}
	if read_only:
		# Read-only startup still distinguishes no journal from a blocked intent.
		return {"state":"missing"} if journal.snapshot()["state"]=="missing" else {"state":"blocked"}
	# inspect owns its one bounded snapshot; avoid parsing/hashing the same file twice.
	var result: Dictionary=journal.inspect(profile)
	if result["state"]=="blocked":read_only=true;last_error=journal.last_error
	if result["state"]=="pending":
		_retry_entry=result["entry"].duplicate(true);_retry_hash=result["hash"];party_retry_recorded=true
	return result

func has_matching_party_journal(identifier: String) -> bool:
	if identifier.is_empty() or _retry_hash.is_empty() or solo_journal.exists(): return false
	var observed: Dictionary = journal.snapshot()
	return (observed["state"] == "read" and observed["hash"] == _retry_hash
		and observed["value"]["manifest"]["run"] == identifier)


func save_party_profile(data: Dictionary,expected_revision: int,value: Dictionary) -> bool:
	var measured: int = Trace.begin()
	var result: bool = _save_party_profile(data,expected_revision,value)
	Trace.end("party_commit",measured)
	return result


func _save_party_profile(data: Dictionary,expected_revision: int,value: Dictionary) -> bool:
	party_retry_recorded = false
	if not _configuration_available(): return false
	if read_only:return false
	if solo_journal.exists():
		last_error = "已有未完成的单人结算，原文件已保留"
		return false
	var candidate: Dictionary=journal.candidate(value)
	if candidate.is_empty() or not Journal.same(candidate,data) or int(value["base"]["revision"])!=expected_revision:
		last_error="待保存结算与原角色不对应";return false
	if not journal.prepare(value,_retry_hash):last_error=journal.last_error;return false
	_retry_hash = journal.prepared_hash()
	party_retry_recorded = true
	if solo_journal.exists():
		last_error = "发现单人和组队两份未完成结算，原文件均已保留"
		return false
	if not save_profile(data,expected_revision):return false
	if not journal.clear(value):
		# The profile really committed. Publish it once, but block subsequent writes.
		read_only=true;last_error=journal.last_error
	_retry_entry.clear();_retry_hash="";party_retry_recorded=false
	return true


func party_intent(base: Dictionary,manifest: Dictionary,self_id: int,frame: Dictionary) -> Dictionary:
	# Recovered raw evidence is frozen; normalization for gameplay uses another copy.
	return _retry_entry.duplicate(true) if not _retry_entry.is_empty() else journal.entry(base,manifest,self_id,frame)
