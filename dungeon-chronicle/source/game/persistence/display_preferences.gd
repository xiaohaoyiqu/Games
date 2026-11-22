extends RefCounted

signal changed
const Reader=preload("res://game/configuration/bounded_json.gd")
const PreferenceStatus = preload("res://game/persistence/preference_status.gd")
const FILENAME: String="display-preferences.json"
const MAX_BYTES: int=512
var revision: int=0
var last_issue: String=""
var _folder: String
var _shake: bool=true
var _dirty: bool=false
var _blocked: bool=false
var _expected: Dictionary={}
var _status: String=""
var _sequence: int=0

func _init(folder: String) -> void:
	_folder=folder
	reload()
func screen_shake() -> bool:return _shake
func dirty() -> bool:return _dirty
func can_save() -> bool:return _dirty and not _blocked
func status_text() -> String:return _status
func _notify() -> void:revision+=1;changed.emit()
func _choice() -> String:return "开启" if _shake else "关闭"
func _digest(bytes: PackedByteArray) -> String:
	var context:=HashingContext.new();context.start(HashingContext.HASH_SHA256);context.update(bytes)
	return context.finish().hex_encode()
func _snapshot(path: String) -> Dictionary:
	if DirAccess.dir_exists_absolute(path): return {"kind":"blocked","issue":"path"}
	var file:=FileAccess.open(path,FileAccess.READ)
	if file==null:
		return PreferenceStatus.failed_open(path,FileAccess.get_open_error())
	var length: int=file.get_length()
	if length<1 or length>MAX_BYTES:file.close();return {"kind":"blocked","issue":"size"}
	var bytes: PackedByteArray=file.get_buffer(length)
	var complete: bool=bytes.size()==length and file.get_length()==length
	file.close()
	if not complete:return {"kind":"blocked","issue":"read"}
	var hash: String=_digest(bytes)
	var decoded: Dictionary=Reader.decode(bytes)
	if not decoded["ok"]:return {"kind":"blocked","hash":hash,"issue":"json"}
	var value: Variant=decoded["value"]
	if value is Dictionary:
		var schema: Variant=value.get("schema_version")
		if typeof(schema) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(schema)) and float(schema)>1 and floor(float(schema))==float(schema):
			return {"kind":"blocked","hash":hash,"issue":"future"}
	if not value is Dictionary or value.size()!=2 or typeof(value.get("schema_version")) not in [TYPE_INT,TYPE_FLOAT] or value["schema_version"]!=1 or not value.get("screen_shake") is bool:
		return {"kind":"blocked","hash":hash,"issue":"schema"}
	return {"kind":"valid","hash":hash,"value":bool(value["screen_shake"])}

# Explicit reload adopts a valid saved choice. Bad files never overwrite the live choice.
func reload() -> bool:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id():return false
	_expected=_snapshot(_folder.path_join(FILENAME));_blocked=_expected["kind"]=="blocked"
	last_issue=str(_expected.get("issue",""))
	if _blocked:
		_dirty=true
		_status="本次已"+_choice()+"屏幕震动"+"。"+PreferenceStatus.blocked_message(last_issue)
	else:
		_shake=bool(_expected["value"]) if _expected["kind"]=="valid" else true
		_dirty=false;_status=("已读取保存的设置：" if _expected["kind"]=="valid" else "使用默认设置：")+"屏幕震动"+_choice()+"。"
	_notify()
	return not _blocked

func set_shake(value: bool) -> bool:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id():return false
	if value==_shake:return false
	_shake=value
	if _blocked:
		_fail(last_issue,true)
		return true
	_dirty=true;_status="本次已"+_choice()+"屏幕震动，尚未保存。";last_issue=""
	_notify()
	save()
	return true

func _fail(issue: String,blocked: bool=false) -> bool:
	last_issue=issue;_blocked=_blocked or blocked;_dirty=true
	if _blocked:
		_status = "本次已"+_choice()+"屏幕震动" + "。" + PreferenceStatus.blocked_message(last_issue)
	else:
		_status = "本次已"+_choice()+"屏幕震动" + "，未保存。保存未完成，可重试保存。"
	_notify();return false

func _replace(temporary: String,destination: String) -> Error:
	return DirAccess.rename_absolute(temporary,destination)

# Only a file with this operation's exact bytes may be discarded.
func _discard(temporary: String,hash: String) -> void:
	var observed: Dictionary=_snapshot(temporary)
	if observed.get("hash","")==hash:DirAccess.remove_absolute(temporary)

func save() -> bool:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id():return false
	if not _dirty:return true
	if _blocked:return _fail(last_issue,true)
	var path: String=_folder.path_join(FILENAME)
	var issue := PreferenceStatus.change_issue(_snapshot(path),_expected)
	if not issue.is_empty(): return _fail(issue,true)
	if _shake==_expected.get("value",true):
		_dirty=false;last_issue=""
		_status=("使用默认设置：屏幕震动" if _expected["kind"]=="missing" else "与保存的设置一致：屏幕震动")+_choice()+"。"
		_notify();return true
	if DirAccess.make_dir_recursive_absolute(_folder)!=OK:return _fail("prepare")
	_sequence+=1
	var temporary: String=path+".tmp-%d-%d-%d" % [OS.get_process_id(),Time.get_ticks_usec(),_sequence]
	if FileAccess.file_exists(temporary) or DirAccess.dir_exists_absolute(temporary):return _fail("temporary")
	var bytes: PackedByteArray=(JSON.stringify({"schema_version":1,"screen_shake":_shake})+"\n").to_utf8_buffer()
	var hash: String=_digest(bytes)
	var file:=FileAccess.open(temporary,FileAccess.WRITE)
	if file==null:return _fail("write")
	var written := file.store_buffer(bytes)
	file.flush()
	var write_error: Error=file.get_error();file.close()
	if not written or write_error!=OK:_discard(temporary,hash);return _fail("write")
	var prepared: Dictionary=_snapshot(temporary)
	if prepared.get("hash","")!=hash or prepared.get("kind")!="valid":_discard(temporary,hash);return _fail("temporary")
	issue = PreferenceStatus.change_issue(_snapshot(path),_expected)
	if not issue.is_empty():
		_discard(temporary,hash)
		return _fail(issue,true)
	var replaced: Error=_replace(temporary,path)
	if replaced!=OK:
		_discard(temporary,hash)
		issue = PreferenceStatus.change_issue(_snapshot(path),_expected)
		return _fail("replace") if issue.is_empty() else _fail(issue,true)
	var committed: Dictionary=_snapshot(path)
	if committed.get("kind")!="valid" or committed.get("hash","")!=hash:return _fail("unconfirmed",true)
	_expected=committed;_dirty=false;_blocked=false;last_issue=""
	_status="已保存：屏幕震动"+_choice()+"。"
	_notify();return true
