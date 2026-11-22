extends RefCounted

signal changed
const Reader=preload("res://game/configuration/bounded_json.gd")
const PreferenceStatus = preload("res://game/persistence/preference_status.gd")
const FILENAME: String="audio-preferences.json"
const MAX_BYTES: int=512
const DEFAULT_VOLUME: int=35
var revision: int=0
var last_issue: String=""
var _folder: String
var _enabled: bool=true
var _volume: int=DEFAULT_VOLUME
var _dirty: bool=false
var _blocked: bool=false
var _expected: Dictionary={}
var _status: String=""
var _sequence: int=0

func _init(folder: String) -> void:
	_folder=folder
	reload()
func enabled() -> bool:return _enabled
func volume_percent() -> int:return _volume
func level() -> float:return float(_volume)/100.0
func dirty() -> bool:return _dirty
func can_save() -> bool:return _dirty and not _blocked
func status_text() -> String:return _status
func _notify() -> void:revision+=1;changed.emit()
func _choice() -> String:return ("开启" if _enabled else "关闭")+"，音量 %d%%" % _volume
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
	if not value is Dictionary or value.size()!=3 or typeof(value.get("schema_version")) not in [TYPE_INT,TYPE_FLOAT] or value["schema_version"]!=1 or not value.get("enabled") is bool or not _valid_volume(value.get("volume_percent")):
		return {"kind":"blocked","hash":hash,"issue":"schema"}
	return {"kind":"valid","hash":hash,"enabled":bool(value["enabled"]),"volume":int(value["volume_percent"])}

static func _valid_volume(value: Variant) -> bool:
	return typeof(value) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(value)) and float(value)>=0 and float(value)<=100 and floor(float(value))==float(value)

# Independent of character/display schemas. Changes are staged, never auto-saved.
# Explicit reload adopts both saved fields. Bad files keep the live pair intact.
func reload() -> bool:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id():return false
	_expected=_snapshot(_folder.path_join(FILENAME));_blocked=_expected["kind"]=="blocked"
	last_issue=str(_expected.get("issue",""))
	if _blocked:
		_dirty=true
		_status="本次音效"+_choice()+"。"+PreferenceStatus.blocked_message(last_issue)
	else:
		_enabled=bool(_expected["enabled"]) if _expected["kind"]=="valid" else true
		_volume=int(_expected.get("volume",DEFAULT_VOLUME))
		_dirty=false;_status=("已读取保存的设置：" if _expected["kind"]=="valid" else "使用默认设置：")+"音效"+_choice()+"。"
	_notify()
	return not _blocked

func set_enabled(value: bool) -> bool:return set_choice(value,_volume)
func set_volume(value: Variant) -> bool:return set_choice(_enabled,value)
func set_choice(active: bool,volume: Variant) -> bool:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id() or not _valid_volume(volume):return false
	var percent: int=int(volume)
	if active==_enabled and percent==_volume:return false
	_enabled=active;_volume=percent
	if _blocked:
		_fail(last_issue if not last_issue.is_empty() else "blocked",true);return true
	_dirty=_enabled!=_expected.get("enabled",true) or _volume!=_expected.get("volume",DEFAULT_VOLUME)
	last_issue=""
	if _dirty:
		_status="本次音效"+_choice()+"，尚未保存。点击保存音效后，下次启动沿用。"
	elif _expected["kind"]=="missing":
		_status="使用默认设置：音效"+_choice()+"。"
	else:
		_status="音效"+_choice()+"，与上次确认的设置一致。"
	_notify();return true

func _fail(issue: String,blocked: bool=false) -> bool:
	last_issue=issue;_blocked=_blocked or blocked;_dirty=true
	if _blocked:
		_status = "本次音效"+_choice() + "。" + PreferenceStatus.blocked_message(last_issue)
	else:
		_status = "本次音效"+_choice() + "，未保存。保存未完成，可点击保存音效重试；下次启动仍以文件为准。"
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
	if _blocked:return _fail(last_issue if not last_issue.is_empty() else "blocked",true)
	var path: String=_folder.path_join(FILENAME)
	var issue := PreferenceStatus.change_issue(_snapshot(path),_expected)
	if not issue.is_empty(): return _fail(issue,true)
	if DirAccess.make_dir_recursive_absolute(_folder)!=OK:return _fail("prepare")
	_sequence+=1
	var temporary: String=path+".tmp-%d-%d-%d" % [OS.get_process_id(),Time.get_ticks_usec(),_sequence]
	if FileAccess.file_exists(temporary) or DirAccess.dir_exists_absolute(temporary):return _fail("temporary")
	var bytes: PackedByteArray=(JSON.stringify({"schema_version":1,"enabled":_enabled,"volume_percent":_volume})+"\n").to_utf8_buffer()
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
	_status="已保存：音效"+_choice()+"；下次启动沿用。"
	_notify();return true
