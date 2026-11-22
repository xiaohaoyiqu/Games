extends RefCounted

# PartyService owns this request and MUST take(true) or dispose before release.
# No PREDELETE callbacks: the owner explicitly joins during its _exit_tree.
enum State { EMPTY, LOADING, READY, FAILED, TAKEN }
const PATHS: Array[String] = ["res://game/main/main_menu.tscn","res://game/main/party_lobby.tscn","res://game/main/party_dungeon.tscn"]
var _thread: Thread
var _result: PackedScene
var _state: int = State.EMPTY
var _started: int = 0
var _joined: int = 0
var _measure: bool = false
var _start_usec: int = 0
var _join_usec: int = 0

func _init(measure: bool=false) -> void: _measure=measure
func start(path: String) -> Error:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id(): return ERR_UNAUTHORIZED
	if path not in PATHS: return ERR_INVALID_PARAMETER
	if _state!=State.EMPTY: return ERR_BUSY
	var began: int=Time.get_ticks_usec() if _measure else 0
	var callback := _load_callable(path)
	if _threads_available():
		_thread = Thread.new()
		var status := _launch(callback)
		if status != OK:
			_thread = null
			if _measure: _start_usec = Time.get_ticks_usec() - began
			return status
		_started = 1
		_state = State.LOADING
	else:
		_store_result(callback.call())
	if _measure: _start_usec = Time.get_ticks_usec() - began
	return OK
func _threads_available() -> bool: return OS.has_feature("threads")
func _load_callable(path: String) -> Callable: return ResourceLoader.load.bind(path,"PackedScene")
func _launch(callback: Callable) -> Error: return _thread.start(callback)
func poll() -> int:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id(): return _state
	if _state==State.LOADING and not _thread.is_alive(): _join()
	return _state
func _join() -> void:
	var began: int=Time.get_ticks_usec() if _measure else 0
	var value: Variant=_thread.wait_to_finish()
	_thread=null; _joined=1
	if _measure: _join_usec=Time.get_ticks_usec()-began
	_store_result(value)
func _store_result(value: Variant) -> void:
	_result=value if value is PackedScene else null
	_state=State.READY if _result!=null else State.FAILED
func take(wait: bool=false) -> PackedScene:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id(): return null
	if _state==State.LOADING:
		if not wait and _thread.is_alive(): return null
		_join()
	if _state not in [State.READY,State.FAILED]: return null
	var result: PackedScene=_result; _result=null; _state=State.TAKEN
	return result
func dispose() -> void:
	if OS.get_thread_caller_id()!=OS.get_main_thread_id(): return
	if _state==State.LOADING: _join()
	_result=null; _state=State.TAKEN
func started() -> bool: return _started==1
func joined() -> bool: return _joined==1
func diagnostics() -> Dictionary:
	# Cached primitives only; never polls the worker or reads files.
	return {"state":_state,"started":_started,"joined":_joined,"owns_thread":_thread!=null,"owns_result":_result!=null,"start_usec":_start_usec,"join_usec":_join_usec}
