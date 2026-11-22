extends RefCounted

## Host-owned room model. Peer IDs come from the transport, never from message data.
const Wire = preload("res://game/network/party_protocol.gd")
enum BeginBlock { NONE, PHASE, SIZE, UNLOCK, UNREADY }
var epoch: String = ""
var build: String
var content: String
var mode: String = "normal"
var phase: String = "closed"
var generation: int = 0
var revision: int = 0
var run_id: String = ""
var _characters: RefCounted
var _members: Dictionary = {}
var _sequences: Dictionary = {}
var _manifest: Dictionary = {}


func _init(character_rules: RefCounted,build_id: String,content_id: String) -> void:
	_characters = character_rules
	build = build_id
	content = content_id


func open(room_epoch: String,name: String,character: Dictionary) -> bool:
	if not Wire.token(room_epoch) or not Wire.label(build,48) or not Wire.fingerprint(content) or not Wire.label(name) or not _characters.valid(character): return false
	reset()
	epoch = room_epoch
	phase = "lobby"
	generation = 1
	revision = 1
	_members[1] = _member(1,name,character)
	return true


func reset() -> void:
	epoch = ""
	phase = "closed"
	mode = "normal"
	generation = 0
	revision = 0
	run_id = ""
	_members.clear()
	_sequences.clear()
	_manifest.clear()


func _member(peer: int,name: String,character: Dictionary) -> Dictionary:
	return {"peer":peer,"name":name,"character":character.duplicate(true),"ready":false,"loaded":false}


func contains(peer: int) -> bool:
	return _members.has(peer)


func peer_ids() -> Array:
	var result := _members.keys()
	result.sort()
	return result


func _structure_changed() -> void:
	generation += 1
	revision += 1
	for member in _members.values():
		member["ready"] = false
		member["loaded"] = false


func add(peer: int,name: String,character: Dictionary) -> bool:
	if phase != "lobby" or not Wire.whole(peer,2) or contains(peer) or _members.size() >= Wire.MAX_PLAYERS: return false
	if not Wire.label(name) or not _characters.valid(character): return false
	if mode == "veteran" and not character["veteran"]: return false
	_members[peer] = _member(peer,name,character)
	_structure_changed()
	return true


func remove(peer: int) -> bool:
	if peer == 1 or not contains(peer): return false
	_members.erase(peer)
	_sequences.erase(peer)
	if phase == "loading": cancel_loading()
	elif phase == "lobby": _structure_changed()
	else: revision += 1
	return true


func choose_mode(value: String) -> bool:
	if phase != "lobby" or value not in ["normal","veteran"]: return false
	if value == "veteran":
		for member in _members.values():
			if not member["character"]["veteran"]: return false
	if value == mode: return true
	mode = value
	_structure_changed()
	return true


func _new_sequence(peer: int,sequence: int) -> bool:
	return Wire.whole(sequence,1) and sequence > int(_sequences.get(peer,0))


func ready(peer: int,expected_generation: int,sequence: int,value: bool) -> bool:
	if phase != "lobby" or not contains(peer) or expected_generation != generation or not _new_sequence(peer,sequence): return false
	_sequences[peer] = sequence
	_members[peer]["ready"] = value
	revision += 1
	return true


static func begin_block(phase_id: String,mode_id: String,members: Array) -> BeginBlock:
	if phase_id != "lobby": return BeginBlock.PHASE
	if members.size() < 2: return BeginBlock.SIZE
	var all_ready: bool = true
	for member: Dictionary in members:
		if mode_id == "veteran" and not member["character"]["veteran"]: return BeginBlock.UNLOCK
		all_ready = all_ready and member["ready"]
	return BeginBlock.NONE if all_ready else BeginBlock.UNREADY


func can_begin() -> bool:
	return begin_block(phase,mode,_members.values()) == BeginBlock.NONE


func update_character(peer: int,expected_generation: int,sequence: int,character: Dictionary) -> bool:
	if phase != "lobby" or not contains(peer) or expected_generation != generation or not _new_sequence(peer,sequence) or not _characters.valid(character): return false
	if mode == "veteran" and not character["veteran"]: return false
	_sequences[peer] = sequence
	if _members[peer]["character"] == character: return true
	_members[peer]["character"] = character.duplicate(true)
	_structure_changed()
	return true


func begin(identifier: String) -> Dictionary:
	if not can_begin() or not Wire.token(identifier): return {}
	run_id = identifier
	phase = "loading"
	revision += 1
	_manifest = {"v":Wire.VERSION,"kind":"prepare","epoch":epoch,"generation":generation,"run":run_id,"mode":mode,"members":_member_array()}
	return manifest()


func loaded(peer: int,expected_generation: int,identifier: String,sequence: int) -> bool:
	if phase != "loading" or not contains(peer) or expected_generation != generation or identifier != run_id or not _new_sequence(peer,sequence): return false
	_sequences[peer] = sequence
	_members[peer]["loaded"] = true
	revision += 1
	for member in _members.values():
		if not member["loaded"]: return true
	phase = "running"
	return true


func cancel_loading() -> bool:
	if phase != "loading": return false
	phase = "lobby"
	run_id = ""
	_manifest.clear()
	_structure_changed()
	return true


func return_to_lobby() -> bool:
	if phase != "running": return false
	phase = "lobby"
	run_id = ""
	_manifest.clear()
	_structure_changed()
	return true


func _member_array() -> Array:
	var result: Array = []
	for peer in peer_ids(): result.append(_members[peer].duplicate(true))
	return result


func snapshot(target: int) -> Dictionary:
	if not contains(target): return {}
	return {"v":Wire.VERSION,"kind":"roster","epoch":epoch,"build":build,"content":content,"revision":revision,"generation":generation,"mode":mode,"phase":phase,"run":run_id,"self":target,"members":_member_array()}


func manifest() -> Dictionary:
	return _manifest.duplicate(true)
