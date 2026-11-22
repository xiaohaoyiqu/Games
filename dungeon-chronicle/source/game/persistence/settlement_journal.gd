extends RefCounted

# Common storage for one bounded settlement intent. Subclasses validate its contents.
const Trace = preload("res://game/diagnostics/frame_trace.gd")
const LocalFileAccess = preload("res://game/persistence/local_file_access.gd")
var rules: RefCounted
var folder: String
var last_error: String = ""
var last_read_bytes: int = 0
var _filename: String
var _size_limit: int
var _prepared_hash: String = ""
var _prepared_value: Dictionary = {}


func _init(directory: String, filename: String, size_limit: int) -> void:
	folder = directory
	_filename = filename
	_size_limit = size_limit


func path() -> String:
	return folder.path_join(_filename)


static func missing_after_open_error(file_path: String,error: Error) -> bool:
	return LocalFileAccess.classify_failed_open(file_path,error) == ERR_FILE_NOT_FOUND


# A metadata miss can also mean that access was denied. Confirm absence before allowing a write.
func probe() -> Error:
	var file_path := path()
	if FileAccess.file_exists(file_path): return OK
	if DirAccess.dir_exists_absolute(file_path): return ERR_FILE_CANT_OPEN
	var file := FileAccess.open(file_path,FileAccess.READ)
	if file == null:
		return ERR_FILE_NOT_FOUND if missing_after_open_error(file_path,FileAccess.get_open_error()) else ERR_FILE_CANT_OPEN
	file.close()
	return OK


func exists() -> bool:
	return probe() != ERR_FILE_NOT_FOUND


func candidate(value: Variant) -> Dictionary:
	var measured: int = Trace.begin()
	var result: Dictionary = _candidate(value)
	Trace.end("journal_validate",measured)
	return result


func _candidate(_value: Variant) -> Dictionary:
	return {}


func digest(bytes: PackedByteArray) -> String:
	var h:=HashingContext.new();h.start(HashingContext.HASH_SHA256);h.update(bytes);return h.finish().hex_encode()
func snapshot() -> Dictionary:
	var measured: int = Trace.begin()
	var result: Dictionary = _snapshot()
	Trace.end("journal_read",measured)
	return result


func _snapshot() -> Dictionary:
	last_read_bytes=0
	var file_path := path()
	if DirAccess.dir_exists_absolute(file_path):return {"state":"blocked"}
	var f:=FileAccess.open(file_path,FileAccess.READ)
	if f==null:
		var missing := missing_after_open_error(file_path,FileAccess.get_open_error())
		return {"state":"missing" if missing else "blocked"}
	var length: int=f.get_length()
	if length>_size_limit:f.close();return {"state":"blocked"}
	var bytes: PackedByteArray=f.get_buffer(length)
	var complete := bytes.size() == length and f.get_length() == length
	f.close()
	last_read_bytes=bytes.size()
	if not complete:return {"state":"blocked"}
	var parser:=JSON.new()
	if parser.parse(bytes.get_string_from_utf8())!=OK:return {"state":"invalid","hash":digest(bytes)}
	return {"state":"read","hash":digest(bytes),"value":parser.data}
func prepare(value: Dictionary,known_hash: String="") -> bool:
	var measured: int = Trace.begin()
	var result: bool = _prepare(value,known_hash)
	Trace.end("journal_prepare",measured)
	return result


func _prepare(value: Dictionary,known_hash: String="") -> bool:
	last_error=""
	if candidate(value).is_empty():last_error="待保存结算校验失败，未写入角色";return false
	var old: Dictionary=snapshot()
	var bytes: PackedByteArray=JSON.stringify(value,"  ",true,true).to_utf8_buffer()
	if bytes.size()>_size_limit:last_error="待保存结算超过大小限制";return false
	var expected: String=digest(bytes)
	if old["state"]=="read" and (known_hash.is_empty() or old["hash"]==known_hash):
		# Live values can round differently on parse. A resumed value can serialize differently.
		if old["hash"]==expected or (not known_hash.is_empty() and same_value(old["value"],value)):
			_prepared_hash=old["hash"];_prepared_value=value.duplicate(true);return true
	if old["state"]!="missing" or not known_hash.is_empty():last_error="未完成结算记录已有变化或不可读取，原文件已保留";return false
	if DirAccess.make_dir_recursive_absolute(folder)!=OK:last_error="无法创建待保存结算目录";return false
	var temporary: String=path()+".tmp-"+str(OS.get_process_id())+"-"+Crypto.new().generate_random_bytes(12).hex_encode()
	var f:=FileAccess.open(temporary,FileAccess.WRITE)
	if f==null:last_error="无法写入待保存结算记录，尚未提交角色";return false
	var written := f.store_buffer(bytes)
	f.flush()
	var error := f.get_error()
	f.close()
	if (not written or error != OK or snapshot()["state"] != "missing"
		or not staged_bytes_match(temporary,bytes) or DirAccess.rename_absolute(temporary,path()) != OK):
		if FileAccess.file_exists(temporary):DirAccess.remove_absolute(temporary)
		last_error="无法完成待保存结算记录，尚未提交角色";return false
	_prepared_hash=expected;_prepared_value=value.duplicate(true);return true
func prepared_hash() -> String:
	return _prepared_hash


func clear(value: Dictionary) -> bool:
	var measured: int = Trace.begin()
	var result: bool = _clear(value)
	Trace.end("journal_clear",measured)
	return result


func _clear(value: Dictionary) -> bool:
	# JSON float parsing can change one ULP even with full_precision serialization.
	# Check the exact file bytes, never a freshly parsed copy against live floats.
	if _prepared_hash.is_empty() or not same_value(_prepared_value,value):last_error="未完成结算清理请求不对应，原文件已保留";return false
	return _remove_hash(_prepared_hash)
func _remove_hash(expected: String) -> bool:
	var observed: Dictionary = snapshot()
	if observed["state"] != "missing":
		if observed["state"] != "read" or observed.get("hash") != expected:
			last_error = "未完成结算记录发生变化，原文件已保留"
			return false
		if DirAccess.remove_absolute(path()) != OK:
			last_error = "角色已保存，但未完成结算记录清理失败，请重新启动核对"
			return false
	_prepared_hash = ""
	_prepared_value.clear()
	last_error = ""
	return true
func inspect(profile: Dictionary) -> Dictionary:
	var measured: int = Trace.begin()
	var result: Dictionary = _inspect(profile)
	Trace.end("journal_inspect",measured)
	return result


func _inspect(profile: Dictionary) -> Dictionary:
	var observed: Dictionary=snapshot()
	if observed["state"]=="missing":return {"state":"missing"}
	var next: Dictionary=candidate(observed.get("value"))
	if next.is_empty():last_error="未完成结算记录无法校验，原文件已保留；请先使用训练房";return {"state":"blocked"}
	var value: Dictionary=observed["value"]
	if _matches_recorded_profile(profile,value["base"]):return {"state":"pending","entry":value.duplicate(true),"hash":observed["hash"]}
	next["revision"]=int(next["revision"])+1
	if same_value(profile,next):
		if _remove_hash(observed["hash"]):return {"state":"applied"}
		return {"state":"blocked"}
	last_error="角色档案与未完成结算记录不一致，未自动领奖或覆盖原文件";return {"state":"blocked"}


# Compare the in-memory profile with a validated journal field without editing its original bytes.
func _matches_recorded_profile(current: Dictionary,recorded: Dictionary) -> bool:
	if current.get("schema_version") == recorded.get("schema_version"):
		return same_value(current,recorded)
	var migrated: Dictionary = rules.normalized_profile(recorded)
	return not migrated.is_empty() and same_value(current,migrated)


static func staged_bytes_match(temporary: String,expected: PackedByteArray) -> bool:
	var file := FileAccess.open(temporary,FileAccess.READ)
	if file == null: return false
	var length := expected.size()
	var matches := (file.get_length() == length and file.get_buffer(length) == expected
		and file.get_length() == length)
	file.close()
	return matches


static func same_value(a: Variant,b: Variant,depth: int=0) -> bool:
	# Only validated bounded JSON values use this comparison; bool is never a number.
	if depth>32:return false
	if typeof(a) in [TYPE_INT,TYPE_FLOAT] and typeof(b) in [TYPE_INT,TYPE_FLOAT]:
		return is_finite(float(a)) and is_finite(float(b)) and a==b
	if typeof(a)!=typeof(b):return false
	if a is Dictionary:
		if a.size()!=b.size():return false
		for key in a:
			if not b.has(key) or not same_value(a[key],b[key],depth+1):return false
		return true
	if a is Array:
		if a.size()!=b.size():return false
		for index in range(a.size()):
			if not same_value(a[index],b[index],depth+1):return false
		return true
	return a==b if typeof(a) in [TYPE_NIL,TYPE_BOOL,TYPE_STRING] else false
