extends RefCounted

const VERSION: int = 1
const Frame = preload("res://game/network/battle_frame.gd")
const HitReview = preload("res://game/network/party_hit_review.gd")
const Trace = preload("res://game/diagnostics/frame_trace.gd")
const MAX_BYTES: int = 8192
const MAX_DEPTH: int = 12
const MAX_PLAYERS: int = 4
const MAX_SEQUENCE: int = 2147483647
const PHASES: Array[String] = ["lobby","loading","running"]
const REASONS: Array[String] = ["version","content","full","busy","locked","packet","timeout","left","host_left","cancelled"]
var characters: RefCounted


func _init(character_rules: RefCounted) -> void:
	characters = character_rules


static func whole(value: Variant,minimum: int=0,maximum: int=MAX_SEQUENCE) -> bool:
	return typeof(value) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(value)) and float(value) == floor(float(value)) and value >= minimum and value <= maximum


static func token(value: Variant,allow_empty: bool=false) -> bool:
	if not value is String or value.length() > 64 or (value.is_empty() and not allow_empty): return false
	for character in value:
		if not character in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_": return false
	return true


static func label(value: Variant,maximum: int=12) -> bool:
	if not value is String or value.is_empty() or value.length() > maximum: return false
	if value.strip_edges().is_empty(): return false
	for index in range(value.length()):
		var code: int = value.unicode_at(index)
		if code < 32 or code == 127 or code in [0x202a,0x202b,0x202c,0x202d,0x202e,0x2066,0x2067,0x2068,0x2069]: return false
	return true


static func fingerprint(value: Variant) -> bool:
	if not value is String or value.length() != 64: return false
	for character in value:
		if not character in "0123456789abcdef": return false
	return true


static func fields(value: Dictionary,expected: Array) -> bool:
	if value.size() != expected.size(): return false
	for key in expected:
		if not value.has(key): return false
	return true


func members(value: Variant) -> bool:
	if not value is Array or value.is_empty() or value.size() > MAX_PLAYERS: return false
	var found: Dictionary = {}
	for member in value:
		if not member is Dictionary or not fields(member,["peer","name","character","ready","loaded"]): return false
		if not whole(member["peer"],1) or found.has(int(member["peer"])): return false
		if not label(member["name"]) or not characters.valid(member["character"]): return false
		if not member["ready"] is bool or not member["loaded"] is bool: return false
		found[int(member["peer"])] = true
	return found.has(1)


func valid(value: Variant) -> bool:
	if not value is Dictionary or not whole(value.get("v"),VERSION,VERSION) or not value.get("kind") is String or not token(value.get("epoch"),true): return false
	match value["kind"]:
		"hello":
			return fields(value,["v","kind","epoch","build","content","name","character"]) and value["epoch"].is_empty() and label(value["build"],48) and fingerprint(value["content"]) and label(value["name"]) and characters.valid(value["character"])
		"roster":
			if not fields(value,["v","kind","epoch","build","content","revision","generation","mode","phase","run","self","members"]): return false
			if not token(value["epoch"]) or not label(value["build"],48) or not fingerprint(value["content"]): return false
			if not whole(value["revision"],1) or not whole(value["generation"],1) or not whole(value["self"],1): return false
			if value["mode"] not in ["normal","veteran"] or value["phase"] not in PHASES or not members(value["members"]): return false
			if not token(value["run"],value["phase"] == "lobby"): return false
			if value["phase"] == "lobby" and not value["run"].is_empty(): return false
			for member in value["members"]:
				if value["phase"] == "lobby" and member["loaded"]: return false
				if value["phase"] != "lobby" and not member["ready"]: return false
				if value["phase"] == "running" and not member["loaded"]: return false
				if value["mode"] == "veteran" and not member["character"]["veteran"]: return false
			for member in value["members"]:
				if int(member["peer"]) == int(value["self"]): return true
			return false
		"ready":
			return fields(value,["v","kind","epoch","generation","seq","ready"]) and token(value["epoch"]) and whole(value["generation"],1) and whole(value["seq"],1) and value["ready"] is bool
		"character":
			return fields(value,["v","kind","epoch","generation","seq","character"]) and token(value["epoch"]) and whole(value["generation"],1) and whole(value["seq"],1) and characters.valid(value["character"])
		"prepare":
			if not fields(value,["v","kind","epoch","generation","run","mode","members"]): return false
			if not token(value["epoch"]) or not token(value["run"]) or not whole(value["generation"],1) or value["mode"] not in ["normal","veteran"] or not members(value["members"]): return false
			if value["members"].size() < 2: return false
			for member in value["members"]:
				if not member["ready"] or member["loaded"]: return false
				if value["mode"] == "veteran" and not member["character"]["veteran"]: return false
			return true
		"loaded":
			return fields(value,["v","kind","epoch","generation","run","seq"]) and token(value["epoch"]) and token(value["run"]) and whole(value["generation"],1) and whole(value["seq"],1)
		"go","cancel":
			return fields(value,["v","kind","epoch","generation","run"]) and token(value["epoch"]) and token(value["run"]) and whole(value["generation"],1)
		"rejected":
			return fields(value,["v","kind","epoch","reason"]) and value["reason"] in REASONS
		"ping","pong":
			return fields(value,["v","kind","epoch","seq"]) and token(value["epoch"]) and whole(value["seq"],1)
		"input":
			if not fields(value,["v","kind","epoch","generation","run","room","seq","walk"]): return false
			return _battle_context(value) and whole(value["room"],0,3) and whole(value["seq"],1) and value["walk"] is Array and value["walk"].size() == 2 and Frame.number(value["walk"][0],-1,1) and Frame.number(value["walk"][1],-1,1)
		"action":
			return fields(value,["v","kind","epoch","generation","run","room","seq","tick","action"]) and _battle_context(value) and whole(value["room"],0,3) and whole(value["seq"],1) and whole(value["tick"],0) and value["action"] in ["attack","jump","dash","skill","interact","cancel"]
		"state","result":
			var keys: Array = ["v","kind","epoch","generation","run","frame"]
			if value["kind"] == "result" and value.has("hit_review"): keys.append("hit_review")
			if not fields(value,keys) or not _battle_context(value) or not Frame.valid(value["frame"]): return false
			if value["kind"] == "state": return true
			return int(value["frame"]["phase"]) in [2,3] and (not value.has("hit_review") or HitReview.valid(value["hit_review"]))
	return false


static func _battle_context(value: Dictionary) -> bool:
	return token(value["epoch"]) and token(value["run"]) and whole(value["generation"],1)


## Validate UTF-8 and bound nesting before native JSON parsing. No object decoding.
static func safe_json_bytes(bytes: PackedByteArray) -> bool:
	if bytes.is_empty() or bytes.size() > MAX_BYTES: return false
	var quoted: bool = false
	var escaped: bool = false
	var depth: int = 0
	var index: int = 0
	while index < bytes.size():
		var value: int = bytes[index]
		if value >= 128:
			if not quoted or escaped: return false
			var count: int = 0
			if value >= 0xc2 and value <= 0xdf: count = 1
			elif value >= 0xe0 and value <= 0xef: count = 2
			elif value >= 0xf0 and value <= 0xf4: count = 3
			else: return false
			if index+count >= bytes.size(): return false
			for step in range(1,count+1):
				if bytes[index+step] < 0x80 or bytes[index+step] > 0xbf: return false
			var second: int = bytes[index+1]
			if (value == 0xe0 and second < 0xa0) or (value == 0xed and second > 0x9f) or (value == 0xf0 and second < 0x90) or (value == 0xf4 and second > 0x8f): return false
			index += count+1
			continue
		if quoted:
			if value < 32: return false
			if escaped:
				if value == 117:
					var word := _hex_word(bytes,index+1)
					if word < 0: return false
					if word >= 0xd800 and word <= 0xdbff:
						if index+10 >= bytes.size() or bytes[index+5] != 92 or bytes[index+6] != 117: return false
						var low := _hex_word(bytes,index+7)
						if low < 0xdc00 or low > 0xdfff: return false
						index += 10
					elif word >= 0xdc00 and word <= 0xdfff: return false
					else: index += 4
				elif value not in [34,92,47,98,102,110,114,116]: return false
				escaped = false
			elif value == 92: escaped = true
			elif value == 34: quoted = false
		else:
			if value == 34: quoted = true
			elif value in [123,91]:
				depth += 1
				if depth > MAX_DEPTH: return false
			elif value in [125,93]:
				depth -= 1
				if depth < 0: return false
			elif value < 32 and value not in [9,10,13]: return false
		index += 1
	return depth == 0 and not quoted and not escaped


static func _hex_word(bytes: PackedByteArray,start: int) -> int:
	if start+3 >= bytes.size(): return -1
	var result: int = 0
	for index in range(start,start+4):
		var value: int = bytes[index]
		if value >= 48 and value <= 57: value -= 48
		elif value >= 65 and value <= 70: value -= 55
		elif value >= 97 and value <= 102: value -= 87
		else: return -1
		result = result*16+value
	return result


func decode(bytes: PackedByteArray) -> Dictionary:
	var measured: int = Trace.begin()
	var result: Dictionary = _decode_packet(bytes)
	Trace.end("decode",measured)
	return result


func _decode_packet(bytes: PackedByteArray) -> Dictionary:
	if not safe_json_bytes(bytes): return {}
	var parser := JSON.new()
	if parser.parse(bytes.get_string_from_utf8()) != OK or not valid(parser.data): return {}
	# Godot JSON numbers are floats. Normalize only validated integer fields,
	# so local/remote snapshots have the same dictionary equality and types.
	var message: Dictionary = parser.data
	for key in ["v","revision","generation","seq","self","tick","room"]:
		if message.has(key): message[key] = int(message[key])
	if message.has("character"): _normalize_character(message["character"])
	for member in message.get("members",[]):
		member["peer"] = int(member["peer"])
		_normalize_character(member["character"])
	if message.has("frame"): Frame.normalize(message["frame"])
	if message.has("hit_review"): HitReview.normalize(message["hit_review"])
	return message


static func _normalize_character(character: Dictionary) -> void:
	character["level"] = int(character["level"])
	for slot in ["weapon","armor","charm"]:
		character["ranks"][slot] = int(character["ranks"][slot])


func encode(message: Dictionary) -> PackedByteArray:
	var measured: int = Trace.begin()
	var result: PackedByteArray = _encode_packet(message)
	Trace.end("encode",measured)
	return result


func _encode_packet(message: Dictionary) -> PackedByteArray:
	if not valid(message): return PackedByteArray()
	var bytes := JSON.stringify(message).to_utf8_buffer()
	return bytes if bytes.size() <= MAX_BYTES else PackedByteArray()
