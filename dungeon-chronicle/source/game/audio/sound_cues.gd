extends Node
signal changed()
signal cue_started(id: String,owner: int)
const Bank=preload("res://game/audio/cue_bank.gd")
const VOICES: int=6
const PLAYBACK_LIMIT: int=64
const DRAIN_LIMIT_MS: int=1500
var _enabled: bool=true
var _level: float=0.35
var _focused: bool=true
var _owner: int=0
var _last: Dictionary={}
var _streams: Dictionary={}
var _players: Array[AudioStreamPlayer]=[]
var _priorities: Array[int]=[]
var _started: Array[int]=[]
# Weak observations include stopped voices still owned by the audio backend.
var _playbacks: Array[WeakRef]=[]
enum DrainState {OPEN,WAITING,STOPPED,COMPLETE}
var _drain_state: DrainState = DrainState.OPEN

func _enter_tree() -> void:
	get_tree().scene_changed.connect(_stop_previous_scene)

func _ready() -> void:
	process_mode=Node.PROCESS_MODE_ALWAYS
	for id: String in Bank.IDS:
		var stream:=AudioStreamWAV.new()
		stream.format=AudioStreamWAV.FORMAT_16_BITS;stream.mix_rate=Bank.RATE;stream.stereo=false
		stream.loop_mode=AudioStreamWAV.LOOP_DISABLED;stream.data=Bank.pcm(id);_streams[id]=stream
	for index in range(VOICES):
		var player:=AudioStreamPlayer.new();player.name="CueVoice"+str(index)
		player.max_polyphony=1;player.autoplay=false;add_child(player)
		_players.append(player);_priorities.append(-1);_started.append(0)
func enabled() -> bool:return _enabled
func level() -> float:return _level
func set_enabled(value: bool) -> bool:
	if not _main_thread():return false
	if value==_enabled:return true
	_enabled=value
	if not value:_silence()
	changed.emit();return true
func set_level(value: float) -> bool:
	if not _main_thread() or not is_finite(value) or value<0 or value>1:return false
	if value==_level:return true
	_level=value
	for player in _players:player.volume_linear=value
	if value==0:_silence()
	changed.emit();return true
func _main_thread() -> bool:return OS.get_thread_caller_id()==OS.get_main_thread_id()
func _now() -> int:return Time.get_ticks_msec()
func _voice_playing(index: int) -> bool:return _players[index].playing
func _start_voice(index: int,id: String) -> void:
	var player: AudioStreamPlayer=_players[index]
	player.stop();player.stream=_streams[id];player.volume_linear=_level;player.play()
	if player.has_stream_playback():_playbacks.append(weakref(player.get_stream_playback()))
func _select_voice(priority: int) -> int:
	for index in range(_players.size()):
		if not _voice_playing(index):return index
	var best: int=-1
	for index in range(_players.size()):
		if _priorities[index]>=priority:continue
		if best<0 or _priorities[index]<_priorities[best] or (_priorities[index]==_priorities[best] and _started[index]<_started[best]):best=index
	return best
# True means a playback request was started, not that a device produced an audible sound.
func play_scene(id: String,source: Node) -> bool:
	if not _main_thread() or not is_inside_tree() or is_queued_for_deletion() or _players.size()!=VOICES:return false
	if _drain_state!=DrainState.OPEN or not _enabled or _level<=0 or not _focused or id not in Bank.IDS:return false
	if not is_instance_valid(source) or not source.is_inside_tree() or source.is_queued_for_deletion():return false
	if source.get_tree()!=get_tree() or get_tree().current_scene!=source:return false
	if source is CanvasItem and not source.is_visible_in_tree():return false
	var source_id := source.get_instance_id()
	if _owner!=0 and _owner!=source_id:_silence()
	var now: int=_now()
	if _last.has(id) and now-int(_last[id])<int(Bank.GAP_MS[id]):return false
	var priority: int=int(Bank.PRIORITY[id]);var voice: int=_select_voice(priority)
	if voice<0:return false
	# Keep at most PLAYBACK_LIMIT weak handles. Only the capacity boundary and shutdown need a scan.
	if _playbacks.size()>=PLAYBACK_LIMIT and _prune_playbacks()>=PLAYBACK_LIMIT:return false
	if _owner==0:
		_owner=source_id
		source.tree_exited.connect(stop_scene.bind(source))
	_start_voice(voice,id);_last[id]=now;_priorities[voice]=priority;_started[voice]=now
	cue_started.emit(id,_owner);return true
func stop_scene(source: Node) -> void:
	if _main_thread() and is_instance_valid(source) and _owner==source.get_instance_id():_silence()
func _stop_previous_scene() -> void:
	if _owner==0: return
	var current: Node = get_tree().current_scene
	if current == null or current.get_instance_id() != _owner: _silence()

func _silence() -> void:
	if _owner!=0:
		var source := instance_from_id(_owner) as Node
		if is_instance_valid(source):
			var on_exit := stop_scene.bind(source)
			if source.tree_exited.is_connected(on_exit):source.tree_exited.disconnect(on_exit)
	for player in _players:player.stop()
	_last.clear();_owner=0;_priorities.fill(-1);_started.fill(0)
func _prune_playbacks() -> int:
	for index in range(_playbacks.size()-1,-1,-1):
		if _playbacks[index].get_ref()==null:_playbacks.remove_at(index)
	return _playbacks.size()
# stop() schedules backend retirement; playing==false is not release evidence.
# Observe only our weak playback references. No held RefCounted crosses an await.
func drain() -> bool:
	if not _main_thread() or not is_inside_tree() or is_queued_for_deletion(): return false
	if _drain_state == DrainState.COMPLETE: return true
	if _drain_state == DrainState.WAITING: return false
	if _drain_state == DrainState.OPEN:
		_silence()
		for player: AudioStreamPlayer in _players: player.stream = null
	_drain_state = DrainState.WAITING
	var tree: SceneTree = get_tree()
	var deadline: int = Time.get_ticks_msec()+DRAIN_LIMIT_MS
	var quiet_frames: int = 0
	while quiet_frames < 2:
		if _prune_playbacks() > 0:
			if Time.get_ticks_msec() >= deadline:
				_drain_state = DrainState.STOPPED
				return false
		else:
			# Allow both AudioServer.update phases after playback retirement.
			quiet_frames += 1
		await tree.process_frame
		if not is_inside_tree() or is_queued_for_deletion() or get_tree() != tree:
			_drain_state = DrainState.STOPPED
			return false
	_drain_state = DrainState.COMPLETE
	return true
func _notification(what: int) -> void:
	if what==NOTIFICATION_APPLICATION_FOCUS_OUT:_focused=false;_silence()
	elif what==NOTIFICATION_APPLICATION_FOCUS_IN:_focused=true
func _exit_tree() -> void:
	get_tree().scene_changed.disconnect(_stop_previous_scene)
	_silence()
