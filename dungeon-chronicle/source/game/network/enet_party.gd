extends Node

## Explicitly started transport component. No autoload, socket or scene side effects.
## Battle/loading code must acknowledge run_preparing with mark_loaded(run_id).
signal room_changed(snapshot: Dictionary)
signal run_preparing(manifest: Dictionary)
signal run_started(manifest: Dictionary)
signal run_cancelled(run_id: String)
signal member_left(peer_id: int)
signal connection_closed(reason: String)
signal protocol_violation(peer_id: int)
signal battle_input(peer_id: int,message: Dictionary)
signal battle_action(peer_id: int,message: Dictionary)
signal battle_state(frame: Dictionary)
signal battle_result(frame: Dictionary,hit_review: Array)

const Wire = preload("res://game/network/party_protocol.gd")
const Room = preload("res://game/network/party_state.gd")
const Trace = preload("res://game/diagnostics/frame_trace.gd")
const MAX_POLL_PACKETS: int = 8
const MAX_POLL_BYTES: int = 32768
const HANDSHAKE_MS: int = 5000
const JOIN_MS: int = 10000
const LOAD_MS: int = 15000
const LOBBY_ROSTER_RETRY_MS: int = 250
const CHARACTER_RETRY_MS: int = 250
const ENTRY_RETRY_MS: int = 250
const IDLE_MS: int = 10000
# Local observation from get_connection_status(), never a wire rejection reason.
const LINK_DISCONNECTED: String = "link_disconnected"
const CONTROL_RATE: int = 8
const CONTROL_BURST: int = 12
const HOST_RATE: int = 64
const HOST_BURST: int = 128
const FAST_RATE: int = 40
const FAST_BURST: int = 60
const BANDWIDTH: int = 1048576
const NATIVE_CHANNELS: int = 3
var _wire: RefCounted
var _room: RefCounted
var _peer
var _hosting: bool = false
var _local_id: int = 0
var _build: String = ""
var _content: String = ""
var _name: String = ""
var _character: Dictionary = {}
var _character_refresh_pending: bool = false
var _next_character_retry: int = 0
var _view: Dictionary = {}
var _manifest: Dictionary = {}
var _pending: Dictionary = {}
var _seen: Dictionary = {}
var _buckets: Dictionary = {}
var _fast_buckets: Dictionary = {}
var _bad: Dictionary = {}
var _closing: Dictionary = {}
var _sequence: int = 0
# Movement and actions travel on independent channels but share send order.
var _battle_command_sequence: int = 0
var _transport_generation: int = 0
var _connected_callback: Callable
var _disconnected_callback: Callable
var _hello_sent: bool = false
var _next_hello_retry: int = 0
var _local_loaded: bool = false
var _started: bool = false
var _now: int = 0
var _join_deadline: int = 0
var _load_deadline: int = 0
var _next_ping: int = 0
var _lobby_roster_targets: Array[int] = []
var _next_lobby_roster_retry: int = 0
var _entry_message: Dictionary = {}
var _entry_targets: Dictionary = {}
var _next_entry_retry: int = 0
var _pending_loaded: Dictionary = {}
var _next_loaded_retry: int = 0


func _ready() -> void:
	set_process(_peer != null)


func configure(character_rules: RefCounted,build_id: String,content_id: String) -> bool:
	if _peer != null or not Wire.label(build_id,48) or not Wire.fingerprint(content_id): return false
	if not is_instance_valid(character_rules) or not character_rules.has_method("valid"): return false
	_wire = Wire.new(character_rules)
	_room = Room.new(character_rules,build_id,content_id)
	_build = build_id
	_content = content_id
	return true


static func _address(value: String) -> bool:
	if value.is_empty() or value.length() > 253: return false
	for character in value:
		if not character in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_:": return false
	return true


static func platform_issue(platform: String) -> String:
	if platform == "Web":
		return "浏览器版可进行单人冒险和训练。局域网组队请使用桌面版。"
	return ""


func _can_open(port: int,name: String,character: Dictionary) -> bool:
	return _wire != null and _peer == null and port >= 1024 and port <= 65535 and Wire.label(name) and _wire.characters.valid(character)


func host(port: int,name: String,character: Dictionary,bind_address: String="*") -> Error:
	if not platform_issue(OS.get_name()).is_empty(): return ERR_UNAVAILABLE
	if not _can_open(port,name,character) or (bind_address != "*" and not bind_address.is_valid_ip_address()): return ERR_INVALID_PARAMETER
	var peer := ENetMultiplayerPeer.new()
	peer.set_bind_ip(bind_address)
	var error := peer.create_server(port,Wire.MAX_PLAYERS-1,1,BANDWIDTH,BANDWIDTH)
	if error != OK: peer.close(); return error
	_limit_native_host(peer.host)
	_attach(peer,true,name,character)
	return OK


func join(address: String,port: int,name: String,character: Dictionary) -> Error:
	if not platform_issue(OS.get_name()).is_empty(): return ERR_UNAVAILABLE
	if not _can_open(port,name,character) or not _address(address): return ERR_INVALID_PARAMETER
	var peer := ENetMultiplayerPeer.new()
	var error := peer.create_client(address,port,1,BANDWIDTH,BANDWIDTH)
	if error != OK: peer.close(); return error
	_limit_native_host(peer.host)
	_attach(peer,false,name,character)
	return OK


static func _limit_native_host(connection) -> void:
	# 4.5.1-stable create_server source passes max_channels into the receive
	# bandwidth argument. Reapply both limits through ENetConnection explicitly.
	connection.channel_limit(NATIVE_CHANNELS)
	connection.bandwidth_limit(BANDWIDTH,BANDWIDTH)


func _attach(peer,hosting: bool,name: String,character: Dictionary) -> void:
	_transport_generation += 1
	_peer = peer
	_hosting = hosting
	_local_id = int(peer.get_unique_id())
	_name = name
	_character = character.duplicate(true)
	_now = Time.get_ticks_msec()
	_join_deadline = _now+JOIN_MS
	_next_hello_retry = _now
	_next_ping = _now+1000
	_connected_callback = _on_connected.bind(peer,_transport_generation)
	_disconnected_callback = _on_disconnected.bind(peer,_transport_generation)
	peer.peer_connected.connect(_connected_callback)
	peer.peer_disconnected.connect(_disconnected_callback)
	set_process(true)
	if hosting:
		_room.open(Crypto.new().generate_random_bytes(16).hex_encode(),name,character)
		_publish_host()


func close(reason: String="left") -> void:
	var had_peer: bool = _peer != null
	var previous = _peer
	_peer = null
	_transport_generation += 1
	if previous != null:
		if previous.peer_connected.is_connected(_connected_callback): previous.peer_connected.disconnect(_connected_callback)
		if previous.peer_disconnected.is_connected(_disconnected_callback): previous.peer_disconnected.disconnect(_disconnected_callback)
		_connected_callback = Callable()
		_disconnected_callback = Callable()
		previous.close()
	if _room != null: _room.reset()
	_hosting = false
	_local_id = 0
	_view.clear(); _manifest.clear(); _pending.clear(); _seen.clear(); _buckets.clear(); _bad.clear()
	_closing.clear()
	_lobby_roster_targets.clear()
	_next_lobby_roster_retry = 0
	_clear_pending_entry()
	_clear_pending_loaded()
	_fast_buckets.clear()
	_character.clear()
	_clear_character_refresh()
	_hello_sent = false
	_local_loaded = false
	_started = false
	_sequence = 0
	_battle_command_sequence = 0
	_join_deadline = 0
	_load_deadline = 0
	set_process(false)
	if had_peer: connection_closed.emit(reason if reason == LINK_DISCONNECTED or reason in Wire.REASONS else "left")


func _exit_tree() -> void:
	close()


func snapshot() -> Dictionary:
	return _view.duplicate(true)


func manifest() -> Dictionary:
	return _manifest.duplicate(true)


func _send(target: int,message: Dictionary,fast: bool=false) -> bool:
	if _peer == null or target <= 0 or _closing.has(target) or _peer.get_connection_status() != MultiplayerPeer.CONNECTION_CONNECTED: return false
	var source = _peer
	var transport_generation: int = _transport_generation
	var bytes: PackedByteArray = _wire.encode(message)
	return _send_packet(target,bytes,fast,source,transport_generation)


func _send_packet(target: int,bytes: PackedByteArray,fast: bool,source,transport_generation: int,scope: Dictionary={},final_rejection: bool=false) -> bool:
	# Never broadcast to unauthenticated/pending connections, or reuse a packet
	# after this transport was closed/replaced by a reentrant callback.
	if source == null or source != _peer or transport_generation != _transport_generation or target <= 0: return false
	if source.get_connection_status() != MultiplayerPeer.CONNECTION_CONNECTED or (_closing.has(target) and not final_rejection): return false
	if bytes.is_empty() or bytes.size() > Wire.MAX_BYTES: return false
	if not scope.is_empty() and not _can_send_scoped(target,scope): return false
	source.transfer_channel = 0
	source.transfer_mode = MultiplayerPeer.TRANSFER_MODE_UNRELIABLE if fast else MultiplayerPeer.TRANSFER_MODE_RELIABLE
	source.set_target_peer(target)
	if source != _peer or transport_generation != _transport_generation: return false
	if source.get_connection_status() != MultiplayerPeer.CONNECTION_CONNECTED or (_closing.has(target) and not final_rejection): return false
	if not scope.is_empty() and not _can_send_scoped(target,scope): return false
	# Godot's ENet implementation flushes locally within put_packet.
	var measured: int = Trace.begin()
	var sent: bool = source.put_packet(bytes) == OK
	Trace.end("native_send",measured)
	return sent


func _can_send_scoped(target: int,message: Dictionary) -> bool:
	var kind: String = message["kind"]
	if kind == "loaded": return target == 1 and _owns_pending_loaded(message)
	if kind == "ping": return _hosting and message["epoch"] == _room.epoch and _room.contains(target)
	if kind == "cancel": return _hosting and _room.phase == "lobby" and _room.epoch == message["epoch"] and _room.contains(target)
	if kind in ["prepare","go"]:
		if not _entry_in_progress() or not _matches_entry(message,_entry_phase(kind)) or not _room.contains(target): return false
		var pending: Dictionary = _entry_targets.get(target,{})
		return pending.get("roster_queued",false)
	if kind != "roster": return _can_send_battle(target,message)
	if message["phase"] != "lobby":
		if not _matches_entry(message,message["phase"]) or message["self"] != target or not _room.contains(target): return false
		var pending: Dictionary = _entry_targets.get(target,{})
		if pending.is_empty(): return message["revision"] == _room.revision
		return not pending["roster_queued"] and message["revision"] == pending["roster"]["revision"]
	if not _hosting or _room.phase != "lobby" or message["phase"] != "lobby": return false
	if message["epoch"] != _room.epoch or message["generation"] != _room.generation: return false
	return message["revision"] == _room.revision and message["self"] == target and _room.contains(target)


func _can_send_battle(target: int,message: Dictionary) -> bool:
	if not _hosting or not _battle_matches(message): return false
	if message["epoch"] != _view.get("epoch") or message["epoch"] != _room.epoch: return false
	if message["run"] != _room.run_id or int(message["generation"]) != _room.generation: return false
	return _room.phase == "running" and _room.contains(target) and not _closing.has(target)


func _publish_host() -> void:
	_view = _room.snapshot(1)
	_lobby_roster_targets.clear()
	_next_lobby_roster_retry = 0
	if _room.phase == "lobby":
		for peer: int in _room.peer_ids():
			if peer != 1: _lobby_roster_targets.append(peer)
		_send_pending_lobby_rosters(true)
		return
	var source = _peer
	var generation: int = _transport_generation
	var published: Dictionary = _view
	room_changed.emit(snapshot())
	for peer in _room.peer_ids():
		if source != _peer or generation != _transport_generation or published != _view: return
		if peer != 1: _send_current_roster(peer)


func _send_current_roster(peer: int) -> void:
	if not _entry_targets.has(peer): _send(peer,_room.snapshot(peer))


func lobby_roster_pending() -> bool:
	return _hosting and _room.phase == "lobby" and not _lobby_roster_targets.is_empty()


func _owns_lobby_roster(source,transport_generation: int,host_view: Dictionary) -> bool:
	if source != _peer or transport_generation != _transport_generation or host_view.is_empty(): return false
	return _can_send_scoped(1,host_view)


func _send_pending_lobby_rosters(notify: bool=false) -> void:
	var source = _peer
	var transport_generation: int = _transport_generation
	var host_view: Dictionary = _view
	if source == null or not _owns_lobby_roster(source,transport_generation,host_view): return
	_next_lobby_roster_retry = _now+LOBBY_ROSTER_RETRY_MS
	var previous_count: int = _lobby_roster_targets.size()
	# All recipients of an attempt use this revision. A nested publication
	# owns the new target list as soon as it changes the revision.
	for peer: int in _lobby_roster_targets.duplicate():
		var roster: Dictionary = _room.snapshot(peer)
		var sent: bool = not roster.is_empty() and _send_packet(peer,_wire.encode(roster),false,source,transport_generation,roster)
		if not _owns_lobby_roster(source,transport_generation,host_view): return
		if sent or not _room.contains(peer): _lobby_roster_targets.erase(peer)
	if notify or previous_count != _lobby_roster_targets.size(): room_changed.emit(snapshot())


func _entry_in_progress() -> bool:
	return not _started and _room.phase in ["loading","running"]


func _entry_phase(kind: String) -> String:
	return "running" if kind == "go" else "loading"


func _matches_entry(message: Dictionary,phase: String) -> bool:
	return _hosting and _room.phase == phase and message.get("epoch") == _room.epoch and message.get("generation") == _room.generation and message.get("run") == _room.run_id


func _owns_entry(source,transport_generation: int,message: Dictionary) -> bool:
	return source != null and source == _peer and transport_generation == _transport_generation and _entry_in_progress() and _matches_entry(message,_entry_phase(message.get("kind","")))


func _clear_pending_entry() -> void:
	_entry_message = {}
	_entry_targets.clear()
	_next_entry_retry = 0


func _stage_entry(message: Dictionary) -> void:
	_clear_pending_entry()
	_entry_message = message
	_next_entry_retry = _now+ENTRY_RETRY_MS
	for peer in _room.peer_ids():
		if peer != 1: _entry_targets[peer] = {"roster":_room.snapshot(peer),"roster_queued":false}


func _send_pending_entry() -> void:
	var source = _peer
	var transport_generation: int = _transport_generation
	var message: Dictionary = _entry_message
	if not _owns_entry(source,transport_generation,message): return
	if _now > _load_deadline: cancel_loading(); return
	_next_entry_retry = _now+ENTRY_RETRY_MS
	var control_bytes: PackedByteArray = _wire.encode(message)
	if not _owns_entry(source,transport_generation,message): return
	# Keep each peer's prerequisite roster ahead of its control packet.
	for peer: int in _entry_targets.keys():
		var pending: Dictionary = _entry_targets.get(peer,{})
		if pending.is_empty(): continue
		var roster: Dictionary = pending["roster"]
		if not pending["roster_queued"]:
			var sent: bool = _send_packet(peer,_wire.encode(roster),false,source,transport_generation,roster)
			if not _owns_entry(source,transport_generation,message): return
			if not sent: continue
			pending["roster_queued"] = true
		var control_sent: bool = _send_packet(peer,control_bytes,false,source,transport_generation,message)
		if not _owns_entry(source,transport_generation,message): return
		if not control_sent: continue
		_entry_targets.erase(peer)
		if roster["revision"] != _room.revision:
			var latest: Dictionary = _room.snapshot(peer)
			_send_packet(peer,_wire.encode(latest),false,source,transport_generation,latest)
			if not _owns_entry(source,transport_generation,message): return
	if not _entry_targets.is_empty(): return
	_clear_pending_entry()
	if message["kind"] == "go":
		_started = true
		_load_deadline = 0
		run_started.emit(manifest())


func _envelope(kind: String) -> Dictionary:
	return {"v":Wire.VERSION,"kind":kind,"epoch":_view.get("epoch","")}


func _on_connected(id: int,source,transport_generation: int) -> void:
	if source != _peer or transport_generation != _transport_generation or not _hosting: return
	_pending[id] = _now+HANDSHAKE_MS


func _cancel_message() -> Dictionary:
	return {"v":Wire.VERSION,"kind":"cancel","epoch":_room.epoch,"generation":_room.generation,"run":_room.run_id}


func _on_disconnected(id: int,source,transport_generation: int) -> void:
	if source != _peer or transport_generation != _transport_generation: return
	_pending.erase(id); _seen.erase(id); _buckets.erase(id); _bad.erase(id)
	_closing.erase(id)
	_fast_buckets.erase(id)
	if not _hosting:
		if id == 1: close("host_left")
		return
	var was_entering: bool = _entry_in_progress()
	var cancelled := _cancel_message() if was_entering else {}
	if not _room.remove(id): return
	if was_entering:
		# During go publication the room is already running, but gameplay is held.
		if _room.phase == "running": _room.return_to_lobby()
		if not _announce_cancel(cancelled): return
	_publish_host()
	if source == _peer and transport_generation == _transport_generation and not _room.contains(id):
		member_left.emit(id)


func choose_mode(mode: String) -> bool:
	if not _hosting or _peer == null or not _room.choose_mode(mode): return false
	_publish_host()
	return true


func set_ready(value: bool) -> bool:
	if _peer == null or _view.is_empty() or _view["phase"] != "lobby": return false
	if value and character_refresh_pending(): return false
	_sequence += 1
	if _hosting:
		if not _room.ready(1,_room.generation,_sequence,value): return false
		_publish_host()
		return true
	var message := _envelope("ready")
	message.merge({"generation":_view["generation"],"seq":_sequence,"ready":value})
	return _send(1,message)


func begin_dungeon() -> bool:
	if not _hosting or _peer == null or lobby_roster_pending(): return false
	var prepared: Dictionary = _room.begin(Crypto.new().generate_random_bytes(16).hex_encode())
	if prepared.is_empty(): return false
	_manifest = prepared
	_battle_command_sequence = 0
	_local_loaded = false
	_started = false
	_load_deadline = _now+LOAD_MS
	_peer.refuse_new_connections = true
	_stage_entry(prepared)
	var source = _peer
	var transport_generation: int = _transport_generation
	_publish_host()
	if _owns_entry(source,transport_generation,prepared):
		_send_pending_entry()
		if _owns_entry(source,transport_generation,prepared): run_preparing.emit(manifest())
	return true


func refresh_character(character: Dictionary) -> bool:
	if _peer == null or _view.get("phase") != "lobby" or not _wire.characters.valid(character): return false
	var changed: bool = _character != character
	_character = character.duplicate(true)
	if _hosting:
		_sequence += 1
		if not _room.update_character(1,_room.generation,_sequence,character): return false
		_publish_host(); return true
	if _roster_matches_character():
		_clear_character_refresh()
		return true
	_character_refresh_pending = true
	if changed: _next_character_retry = 0
	return _send_pending_character()


func character_refresh_pending() -> bool:
	return _character_refresh_pending


func _clear_character_refresh() -> void:
	_character_refresh_pending = false
	_next_character_retry = 0


func _roster_matches_character() -> bool:
	for member: Dictionary in _view["members"]:
		if int(member["peer"]) == _local_id: return member["character"] == _character
	return false


func _send_pending_character() -> bool:
	if not _character_refresh_pending or _view.get("phase") != "lobby" or _now < _next_character_retry: return false
	# Rebuild against the latest roster generation. Enqueue success does not confirm application.
	_next_character_retry = _now+CHARACTER_RETRY_MS
	_sequence += 1
	var message := _envelope("character")
	message.merge({"generation":_view["generation"],"seq":_sequence,"character":_character})
	return _send(1,message)


func mark_loaded(run_id: String) -> bool:
	if _peer == null or _manifest.is_empty() or run_id != _manifest["run"] or _local_loaded or not _pending_loaded.is_empty(): return false
	_sequence += 1
	if _hosting:
		if not _room.loaded(1,_room.generation,run_id,_sequence): return false
		_local_loaded = true
		_after_loaded()
		return true
	var message := _envelope("loaded")
	message.merge({"generation":_manifest["generation"],"run":run_id,"seq":_sequence})
	if _now > _load_deadline or not _matches_loading_view(message): return false
	_pending_loaded = message
	return _send_pending_loaded()


func loading_confirmation_pending(run_id: String) -> bool:
	return not _pending_loaded.is_empty() and _pending_loaded["run"] == run_id


func _matches_loading_view(message: Dictionary) -> bool:
	return _view.get("phase") == "loading" and message["epoch"] == _view.get("epoch") and message["generation"] == _view.get("generation") and message["run"] == _view.get("run") and message["run"] == _manifest.get("run")


func _owns_pending_loaded(message: Dictionary) -> bool:
	return not _hosting and not _local_loaded and not message.is_empty() and is_same(message,_pending_loaded) and _now <= _load_deadline and _matches_loading_view(message)


func _clear_pending_loaded() -> void:
	_pending_loaded = {}
	_next_loaded_retry = 0


func _reset_client_entry() -> void:
	_clear_pending_loaded()
	_manifest.clear()
	_local_loaded = false
	_started = false
	_load_deadline = 0


func _send_pending_loaded() -> bool:
	var source = _peer
	var generation: int = _transport_generation
	var message: Dictionary = _pending_loaded
	if source == null or not _owns_pending_loaded(message): return false
	_next_loaded_retry = _now+ENTRY_RETRY_MS
	# Scene readiness is reported once. Only an unqueued confirmation is retried.
	var queued: bool = _send_packet(1,_wire.encode(message),false,source,generation,message)
	if not queued or source != _peer or generation != _transport_generation or not _owns_pending_loaded(message): return false
	_local_loaded = true
	_clear_pending_loaded()
	return true


func _after_loaded() -> void:
	if _now > _load_deadline: cancel_loading(); return
	if _room.phase == "loading": _publish_host(); return
	var message := _envelope("go")
	message.merge({"generation":_room.generation,"run":_room.run_id})
	_stage_entry(message)
	var source = _peer
	var transport_generation: int = _transport_generation
	_publish_host()
	if _owns_entry(source,transport_generation,message): _send_pending_entry()


func _announce_cancel(message: Dictionary) -> bool:
	var source = _peer
	var generation: int = _transport_generation
	_clear_pending_entry()
	_manifest.clear()
	_local_loaded = false
	_started = false
	_load_deadline = 0
	source.refuse_new_connections = false
	var bytes: PackedByteArray = _wire.encode(message)
	for peer: int in _room.peer_ids():
		if peer == 1 or not _room.contains(peer): continue
		_send_packet(peer,bytes,false,source,generation,message)
		if generation != _transport_generation or _room.phase != "lobby": return false
	run_cancelled.emit(message["run"])
	return generation == _transport_generation and _room.phase == "lobby"


func cancel_loading() -> bool:
	if not _hosting or _peer == null or not _entry_in_progress(): return false
	var message := _cancel_message()
	if _room.phase == "loading": _room.cancel_loading()
	else: _room.return_to_lobby()
	if _announce_cancel(message): _publish_host()
	return true


func return_to_lobby() -> bool:
	# The battle coordinator must finish/retry persistent settlement before this.
	if not _hosting or _peer == null or not _started or not _room.return_to_lobby(): return false
	_manifest.clear(); _local_loaded = false; _started = false
	_load_deadline = 0
	_peer.refuse_new_connections = false
	_publish_host()
	return true


func _allow_control(peer: int) -> bool:
	# A host legitimately emits several rosters for four simultaneous ready/ACKs.
	return _take_packet_credit(_buckets,peer,CONTROL_RATE if _hosting else HOST_RATE,CONTROL_BURST if _hosting else HOST_BURST)


func _allow_fast(peer: int) -> bool:
	return _take_packet_credit(_fast_buckets,peer,FAST_RATE,FAST_BURST)


func _take_packet_credit(buckets: Dictionary,peer: int,rate: int,burst: int) -> bool:
	# One packet costs 1000 units. At rate packets/second, each ms earns rate units.
	var capacity := burst*1000
	if not buckets.has(peer):
		buckets[peer] = {"at":_now,"credit":capacity}
	var bucket: Dictionary = buckets[peer]
	var elapsed := _now-int(bucket["at"])
	var credit := mini(capacity,int(bucket["credit"])+elapsed*rate)
	bucket["at"] = _now
	var allowed := credit >= 1000
	bucket["credit"] = credit-1000 if allowed else credit
	return allowed


func _battle_message(kind: String) -> Dictionary:
	var message := _envelope(kind)
	message.merge({"generation":_manifest.get("generation",0),"run":_manifest.get("run","")})
	return message


func _battle_matches(message: Dictionary) -> bool:
	return _started and not _manifest.is_empty() and _view.get("phase") == "running" and message["run"] == _manifest["run"] and int(message["generation"]) == int(_manifest["generation"])


func submit_input(walk: Vector2,room_index: int=0) -> bool:
	if _peer == null or not _started or not walk.is_finite(): return false
	_battle_command_sequence += 1
	var message := _battle_message("input")
	var direction := walk.limit_length()
	message.merge({"seq":_battle_command_sequence,"room":room_index,"walk":[snappedf(direction.x,0.001),snappedf(direction.y,0.001)]})
	if _hosting: battle_input.emit(1,message); return true
	return _send(1,message,true)


func submit_action(action: String,tick: int,room_index: int=0) -> bool:
	if _peer == null or not _started or action not in ["attack","jump","dash","skill","interact","cancel"] or tick < 0: return false
	_battle_command_sequence += 1
	var message := _battle_message("action")
	message.merge({"seq":_battle_command_sequence,"room":room_index,"tick":tick,"action":action})
	if _hosting: battle_action.emit(1,message); return true
	return _send(1,message)


func publish_frame(frame: Dictionary,finished: bool=false,hit_review: Array=[],queued_peers: Array[int]=[]) -> bool:
	if not _hosting or _peer == null or not _started: return false
	var source = _peer
	var transport_generation: int = _transport_generation
	var message := _battle_message("result" if finished else "state")
	message["frame"] = frame
	if finished and not hit_review.is_empty(): message["hit_review"] = hit_review
	# encode validates the complete message and the byte limit. Shared frames
	# have no recipient-specific fields; rosters still use separate _send calls.
	var bytes: PackedByteArray = _wire.encode(message)
	if bytes.is_empty() and message.has("hit_review"):
		message.erase("hit_review")
		bytes = _wire.encode(message)
	if bytes.is_empty(): return false
	if source != _peer or transport_generation != _transport_generation or not _can_send_battle(1,message): return false
	var sent: bool = true
	# Only terminal retries carry peers queued for this frozen result.
	for peer: int in _room.peer_ids():
		if peer == 1 or (finished and queued_peers.has(peer)): continue
		var queued: bool = _send_packet(peer,bytes,not finished,source,transport_generation,message)
		if queued and finished: queued_peers.append(peer)
		sent = queued and sent
	return sent


func _violation(peer: int) -> void:
	var generation := _transport_generation
	var count := int(_bad.get(peer,0))+1
	_bad[peer] = count
	protocol_violation.emit(peer)
	if generation != _transport_generation or _bad.get(peer,0) != count: return
	if count >= 3:
		if _hosting: _reject(peer,"packet")
		else: close("packet")


func _reject(peer: int,reason: String) -> void:
	if _closing.has(peer): return
	var source = _peer
	var generation: int = _transport_generation
	_closing[peer] = true
	_pending.erase(peer)
	# Exactly this final notice may enter a closing peer. Ordinary packets may not.
	var bytes: PackedByteArray = _wire.encode({"v":Wire.VERSION,"kind":"rejected","epoch":"","reason":reason})
	_send_packet(peer,bytes,false,source,generation,{},true)
	# A callback may have replaced the connection while sending the notice.
	if source != null and source == _peer and generation == _transport_generation and _closing.has(peer):
		source.disconnect_peer(peer)


func _receive_host(peer: int,message: Dictionary) -> void:
	if message["kind"] == "hello":
		if not _pending.has(peer) or _room.contains(peer): _violation(peer); return
		if message["build"] != _build: _reject(peer,"version"); return
		if message["content"] != _content: _reject(peer,"content"); return
		if _room.phase != "lobby": _reject(peer,"busy"); return
		if _room.mode == "veteran" and not message["character"]["veteran"]: _reject(peer,"locked"); return
		if not _room.add(peer,message["name"],message["character"]): _reject(peer,"full"); return
		_pending.erase(peer)
		_seen[peer] = _now
		_publish_host()
		return
	if not _room.contains(peer) or message["epoch"] != _room.epoch: _violation(peer); return
	_seen[peer] = _now
	match message["kind"]:
		"ready":
			if _room.ready(peer,int(message["generation"]),int(message["seq"]),message["ready"]): _publish_host()
			else: _send_current_roster(peer)
		"loaded":
			if _entry_targets.has(peer): return
			if _room.loaded(peer,int(message["generation"]),message["run"],int(message["seq"])): _after_loaded()
		"character":
			if _room.update_character(peer,int(message["generation"]),int(message["seq"]),message["character"]): _publish_host()
			else: _send_current_roster(peer)
		"input","action":
			if not _battle_matches(message): return
			if message["kind"] == "input": battle_input.emit(peer,message)
			else: battle_action.emit(peer,message)
		"ping": _send(peer,{"v":Wire.VERSION,"kind":"pong","epoch":_room.epoch,"seq":message["seq"]})
		"pong": pass
		_: _violation(peer)


func _receive_client(peer: int,message: Dictionary) -> void:
	if peer != 1: _violation(peer); return
	if message["kind"] == "rejected": close(message["reason"]); return
	if message["kind"] == "roster":
		if not _hello_sent or message["build"] != _build or message["content"] != _content or int(message["self"]) != _local_id: _violation(peer); return
		if not _view.is_empty() and message["epoch"] != _view["epoch"]: _violation(peer); return
		if not _view.is_empty() and int(message["revision"]) <= int(_view["revision"]): return
		_view = message.duplicate(true)
		if _view["phase"] == "lobby":
			_reset_client_entry()
			if _character_refresh_pending and _roster_matches_character(): _clear_character_refresh()
		else:
			_clear_character_refresh()
			if not _pending_loaded.is_empty() and not _matches_loading_view(_pending_loaded): _clear_pending_loaded()
		_seen[1] = _now
		room_changed.emit(snapshot())
		return
	if _view.is_empty() or message["epoch"] != _view["epoch"]: _violation(peer); return
	_seen[1] = _now
	match message["kind"]:
		"prepare":
			if _view["phase"] != "loading" or message["run"] != _view["run"] or int(message["generation"]) != int(_view["generation"]) or message["mode"] != _view["mode"] or message["members"] != _view["members"]: _violation(peer); return
			if not _manifest.is_empty(): return
			_manifest = message.duplicate(true)
			_battle_command_sequence = 0
			_local_loaded = false
			_load_deadline = _now+LOAD_MS+JOIN_MS
			run_preparing.emit(manifest())
		"go":
			if _view["phase"] != "running" or _manifest.is_empty() or message["run"] != _manifest["run"] or int(message["generation"]) != int(_manifest["generation"]) or not _local_loaded: _violation(peer); return
			if not _started: _started = true; run_started.emit(manifest())
		"cancel":
			if _manifest.is_empty() or message["run"] != _manifest["run"] or int(message["generation"]) != int(_manifest["generation"]): return
			_reset_client_entry()
			run_cancelled.emit(message["run"])
		"state","result":
			# Independent fast and reliable channels can arrive in either order.
			# A pre-go snapshot is harmless; a later snapshot will replace it.
			if not _battle_matches(message): return
			if message["kind"] == "state": battle_state.emit(message["frame"])
			else: battle_result.emit(message["frame"],message.get("hit_review",[]))
		"ping": _send(1,{"v":Wire.VERSION,"kind":"pong","epoch":_view["epoch"],"seq":message["seq"]})
		"pong": pass
		_: _violation(peer)


func _process(_delta: float) -> void:
	pump(Time.get_ticks_msec())


func pump(now_msec: int) -> void:
	var measured: int = Trace.begin()
	_poll_packets(now_msec)
	Trace.end("poll",measured)


func _poll_packets(now_msec: int) -> void:
	if _peer == null: return
	_now = maxi(_now,now_msec)
	var source = _peer
	var generation: int = _transport_generation
	var measured: int = Trace.begin()
	source.poll()
	Trace.end("native_poll",measured)
	if _peer != source or generation != _transport_generation: return
	var status: int = source.get_connection_status()
	if status == MultiplayerPeer.CONNECTION_DISCONNECTED:
		close(LINK_DISCONNECTED)
		return
	if (not _hosting and not _hello_sent and status == MultiplayerPeer.CONNECTION_CONNECTED
		and _now >= _next_hello_retry and _now <= _join_deadline):
		_next_hello_retry = _now+ENTRY_RETRY_MS
		var queued: bool = _send(1,{"v":Wire.VERSION,"kind":"hello","epoch":"","build":_build,"content":_content,"name":_name,"character":_character})
		if _peer != source or generation != _transport_generation: return
		_hello_sent = queued
	var packets: int = 0
	var bytes: int = 0
	measured = Trace.begin()
	while generation == _transport_generation and source.get_available_packet_count() > 0 and packets < MAX_POLL_PACKETS and bytes < MAX_POLL_BYTES:
		var sender: int = int(source.get_packet_peer())
		var channel: int = int(source.get_packet_channel())
		var transfer: int = int(source.get_packet_mode())
		var packet: PackedByteArray = source.get_packet()
		packets += 1; bytes += packet.size()
		# Unrelated relayed clients cannot force a victim to close its host link.
		if not _hosting and sender != 1: continue
		if _hosting and not _pending.has(sender) and not _room.contains(sender): continue
		if _closing.has(sender): continue
		if channel != 0 or transfer not in [MultiplayerPeer.TRANSFER_MODE_RELIABLE,MultiplayerPeer.TRANSFER_MODE_UNRELIABLE]: _violation(sender); continue
		var fast: bool = transfer == MultiplayerPeer.TRANSFER_MODE_UNRELIABLE
		var message: Dictionary = _wire.decode(packet)
		if message.is_empty(): _violation(sender); continue
		if fast != (message["kind"] in ["input","state"]): _violation(sender); continue
		if not (_allow_fast(sender) if fast or message["kind"] == "action" else _allow_control(sender)): _violation(sender); continue
		if _hosting: _receive_host(sender,message)
		else: _receive_client(sender,message)
	Trace.end("packet_dispatch",measured)
	if generation != _transport_generation: return
	if not _hosting and _view.is_empty() and _now > _join_deadline: close("timeout"); return
	if not _hosting and not _manifest.is_empty() and not _started and _now > _load_deadline: close("timeout"); return
	if _hosting:
		for peer in _pending.keys():
			if _now > int(_pending.get(peer,_now)):
				_reject(peer,"timeout")
				if generation != _transport_generation: return
		if _entry_in_progress() and _now > _load_deadline: cancel_loading()
		if generation != _transport_generation: return
		if lobby_roster_pending() and _now >= _next_lobby_roster_retry: _send_pending_lobby_rosters()
		if generation != _transport_generation: return
		if not _entry_targets.is_empty() and _now >= _next_entry_retry: _send_pending_entry()
		if generation != _transport_generation: return
	for peer in _seen.keys():
		if _now-int(_seen.get(peer,_now)) > IDLE_MS:
			if not _hosting:
				close("timeout")
				return
			_reject(peer,"timeout")
			if generation != _transport_generation: return
	if not _hosting and not _pending_loaded.is_empty() and _now >= _next_loaded_retry:
		_send_pending_loaded()
		if generation != _transport_generation: return
	if _character_refresh_pending and _now >= _next_character_retry:
		_send_pending_character()
		if generation != _transport_generation: return
	if _now >= _next_ping:
		_next_ping = _now+1000
		_sequence += 1
		var ping := _envelope("ping")
		ping["seq"] = _sequence
		if _hosting:
			var members: Array = _room.peer_ids()
			if members.size() > 1:
				var packet: PackedByteArray = _wire.encode(ping)
				for peer: int in members:
					if peer != 1: _send_packet(peer,packet,false,source,generation,ping)
		elif not _view.is_empty(): _send(1,ping)
