extends RefCounted

# Shared local-file bounds and JSON syntax check, followed by Godot's decoder.
# Fixed paths are chosen by callers; this is not a general-purpose JSON parser.
const MAX_BYTES: int = 65536
const MAX_DEPTH: int = 8
const CONTAINER_OBJECT: int = 123
const CONTAINER_ARRAY: int = 91
const OBJECT_KEY: int = 0
const OBJECT_COLON: int = 1
const OBJECT_VALUE: int = 2
const OBJECT_END: int = 3
const ARRAY_VALUE: int = 4
const ARRAY_END: int = 5
const OBJECT_KEY_AFTER_COMMA: int = 6
const ARRAY_VALUE_AFTER_COMMA: int = 7

static func read(path: String) -> Dictionary:
	var last_read_bytes: int = 0
	var file := FileAccess.open(path,FileAccess.READ)
	if file == null: return {"ok":false,"bytes":last_read_bytes,"code":"open"}
	var length: int = file.get_length()
	if length<1 or length>MAX_BYTES: file.close(); return {"ok":false,"bytes":last_read_bytes,"code":"size"}
	var bytes: PackedByteArray = file.get_buffer(length)
	var same_length: bool = file.get_length()==length
	file.close(); last_read_bytes = bytes.size()
	if not same_length: return {"ok":false,"bytes":last_read_bytes,"code":"changed-size"}
	if bytes.size()!=length: return {"ok":false,"bytes":last_read_bytes,"code":"short-read"}
	return decode(bytes)

# Decode one already bounded snapshot; callers can hash and validate the same bytes.
static func decode(bytes: PackedByteArray) -> Dictionary:
	var last_read_bytes: int=bytes.size()
	if bytes.is_empty() or bytes.size()>MAX_BYTES:return {"ok":false,"bytes":last_read_bytes,"code":"size"}
	if not _utf8(bytes): return {"ok":false,"bytes":last_read_bytes,"code":"utf8"}
	if not _bounded_depth(bytes): return {"ok":false,"bytes":last_read_bytes,"code":"depth"}
	var scan_code: String = _inspect_json(bytes)
	if scan_code=="duplicate": return {"ok":false,"bytes":last_read_bytes,"code":"duplicate"}
	if not scan_code.is_empty(): return {"ok":false,"bytes":last_read_bytes,"code":"json"}
	var parser := JSON.new()
	var text: String = bytes.slice(3).get_string_from_utf8() if bytes.size()>=3 and bytes[0]==239 and bytes[1]==187 and bytes[2]==191 else bytes.get_string_from_utf8()
	if parser.parse(text)!=OK: return {"ok":false,"bytes":last_read_bytes,"code":"json"}
	return {"ok":true,"bytes":last_read_bytes,"value":parser.data}

static func _utf8(bytes: PackedByteArray) -> bool:
	var i: int = 0
	while i<bytes.size():
		var first: int = bytes[i]
		if first<128: i+=1; continue
		var count: int = 0; var low: int = 128; var high: int = 191
		if first>=194 and first<=223: count=2
		elif first>=224 and first<=239:
			count=3
			if first==224: low=160
			elif first==237: high=159
		elif first>=240 and first<=244:
			count=4
			if first==240: low=144
			elif first==244: high=143
		else: return false
		if i+count>bytes.size() or bytes[i+1]<low or bytes[i+1]>high: return false
		for offset in range(2,count):
			if bytes[i+offset]<128 or bytes[i+offset]>191: return false
		i+=count
	return true

static func _bounded_depth(bytes: PackedByteArray) -> bool:
	var containers: PackedByteArray = PackedByteArray()
	var quoted: bool = false
	var escaped: bool = false
	for byte: int in bytes:
		if quoted:
			if escaped:
				escaped = false
			elif byte == 92:
				escaped = true
			elif byte == 34:
				quoted = false
			continue
		if byte == 34:
			quoted = true
		elif byte == 123 or byte == 91:
			if containers.size() >= MAX_DEPTH: return false
			containers.append(byte)
		elif byte == 125 or byte == 93:
			if containers.is_empty(): return false
			var opening: int = containers[containers.size()-1]
			if (byte == 125 and opening != 123) or (byte == 93 and opening != 91): return false
			containers.remove_at(containers.size()-1)
	return containers.is_empty() and not quoted and not escaped


static func _inspect_json(bytes: PackedByteArray) -> String:
	var types: PackedByteArray = PackedByteArray()
	var states: PackedByteArray = PackedByteArray()
	var keys: Array[Dictionary] = []
	var root_started: bool = false
	var duplicate_found: bool = false
	var i: int = 3 if bytes.size()>=3 and bytes[0]==239 and bytes[1]==187 and bytes[2]==191 else 0
	while i<bytes.size():
		var byte: int = bytes[i]
		if byte==32 or byte==9 or byte==10 or byte==13: i+=1; continue
		if byte==34:
			var token: Dictionary = _decode_string(bytes,i)
			if not token["ok"]: return "json"
			if not types.is_empty() and types[types.size()-1]==CONTAINER_OBJECT and (states[states.size()-1]==OBJECT_KEY or states[states.size()-1]==OBJECT_KEY_AFTER_COMMA):
				# Compare decoded bytes so literal and escaped-equivalent keys match.
				var key: String = token["value"].hex_encode()
				var current: Dictionary = keys.back()
				if current.has(key): duplicate_found = true
				else: current[key] = true
				states[states.size()-1] = OBJECT_COLON
			elif _can_start_value(types,states,root_started):
				_mark_container_value(types,states)
				if types.is_empty(): root_started = true
			else:
				return "json"
			i = token["end"]
			continue
		if byte==123 or byte==91:
			if not _can_start_value(types,states,root_started): return "json"
			_mark_container_value(types,states)
			if types.is_empty(): root_started = true
			types.append(byte)
			states.append(OBJECT_KEY if byte==CONTAINER_OBJECT else ARRAY_VALUE)
			keys.append({})
			i+=1
			continue
		if byte==125 or byte==93:
			if types.is_empty(): return "json"
			var top: int = types.size()-1
			var valid_close: bool = (byte==125 and types[top]==CONTAINER_OBJECT and states[top] in [OBJECT_KEY,OBJECT_END]) or (byte==93 and types[top]==CONTAINER_ARRAY and states[top] in [ARRAY_VALUE,ARRAY_END])
			if not valid_close: return "json"
			types.remove_at(top)
			states.remove_at(top)
			keys.remove_at(top)
			i+=1
			continue
		if byte==58:
			if types.is_empty(): return "json"
			var top: int = types.size()-1
			if types[top]!=CONTAINER_OBJECT or states[top]!=OBJECT_COLON: return "json"
			states[top] = OBJECT_VALUE
			i+=1
			continue
		if byte==44:
			if types.is_empty(): return "json"
			var top: int = types.size()-1
			if types[top]==CONTAINER_OBJECT and states[top]==OBJECT_END: states[top] = OBJECT_KEY_AFTER_COMMA
			elif types[top]==CONTAINER_ARRAY and states[top]==ARRAY_END: states[top] = ARRAY_VALUE_AFTER_COMMA
			else: return "json"
			i+=1
			continue
		if not _can_start_value(types,states,root_started): return "json"
		if byte==116 and _literal_at(bytes,i,"true"): i+=4
		elif byte==102 and _literal_at(bytes,i,"false"): i+=5
		elif byte==110 and _literal_at(bytes,i,"null"): i+=4
		elif byte==45 or (byte>=48 and byte<=57):
			var number_end: int = _number_end(bytes,i)
			if number_end<0: return "json"
			i = number_end
		else: return "json"
		_mark_container_value(types,states)
		if types.is_empty(): root_started = true
	if not types.is_empty() or not root_started: return "json"
	return "duplicate" if duplicate_found else ""


static func _can_start_value(types: PackedByteArray,states: PackedByteArray,root_started: bool) -> bool:
	if types.is_empty(): return not root_started
	var top: int = types.size()-1
	if types[top]==CONTAINER_OBJECT: return states[top]==OBJECT_VALUE
	return states[top]==ARRAY_VALUE or states[top]==ARRAY_VALUE_AFTER_COMMA


static func _literal_at(bytes: PackedByteArray,start: int,literal: String) -> bool:
	for offset in range(literal.length()):
		if start+offset>=bytes.size() or bytes[start+offset]!=literal.unicode_at(offset): return false
	return true


static func _number_end(bytes: PackedByteArray,start: int) -> int:
	var i: int = start
	if bytes[i]==45:
		i+=1
		if i>=bytes.size(): return -1
	if bytes[i]==48:
		i+=1
	elif bytes[i]>=49 and bytes[i]<=57:
		while i<bytes.size() and bytes[i]>=48 and bytes[i]<=57: i+=1
	else: return -1
	if i<bytes.size() and bytes[i]==46:
		i+=1
		var fraction_start: int = i
		while i<bytes.size() and bytes[i]>=48 and bytes[i]<=57: i+=1
		if i==fraction_start: return -1
	if i<bytes.size() and (bytes[i]==69 or bytes[i]==101):
		i+=1
		if i<bytes.size() and (bytes[i]==43 or bytes[i]==45): i+=1
		var exponent_start: int = i
		while i<bytes.size() and bytes[i]>=48 and bytes[i]<=57: i+=1
		if i==exponent_start: return -1
	return i


static func _mark_container_value(types: PackedByteArray,states: PackedByteArray) -> void:
	if types.is_empty(): return
	var index: int = states.size()-1
	if types[types.size()-1]==CONTAINER_OBJECT and states[index]==OBJECT_VALUE: states[index] = OBJECT_END
	elif types[types.size()-1]==CONTAINER_ARRAY and states[index] in [ARRAY_VALUE,ARRAY_VALUE_AFTER_COMMA]: states[index] = ARRAY_END


static func _decode_string(bytes: PackedByteArray,start: int) -> Dictionary:
	var decoded: PackedByteArray = PackedByteArray()
	var i: int = start+1
	while i<bytes.size():
		var byte: int = bytes[i]
		if byte==34: return {"ok":true,"value":decoded,"end":i+1}
		if byte!=92:
			if byte<32: return {"ok":false,"value":PackedByteArray(),"end":bytes.size()}
			decoded.append(byte); i+=1; continue
		i+=1
		if i>=bytes.size(): return {"ok":false,"value":"","end":bytes.size()}
		var escaped: int = bytes[i]
		if escaped==34 or escaped==47 or escaped==92: decoded.append(escaped)
		elif escaped==98: decoded.append(8)
		elif escaped==102: decoded.append(12)
		elif escaped==110: decoded.append(10)
		elif escaped==114: decoded.append(13)
		elif escaped==116: decoded.append(9)
		elif escaped==117:
			if i+4>=bytes.size(): return {"ok":false,"value":"","end":bytes.size()}
			var codepoint: int = _hex4(bytes,i+1)
			if codepoint<0: return {"ok":false,"value":"","end":bytes.size()}
			i+=4
			if codepoint>=55296 and codepoint<=56319:
				if i+6>=bytes.size() or bytes[i+1]!=92 or bytes[i+2]!=117: return {"ok":false,"value":"","end":bytes.size()}
				var low: int = _hex4(bytes,i+3)
				if low<56320 or low>57343: return {"ok":false,"value":"","end":bytes.size()}
				codepoint = 65536+((codepoint-55296)<<10)+(low-56320)
				i+=6
			elif codepoint>=56320 and codepoint<=57343:
				return {"ok":false,"value":"","end":bytes.size()}
			if codepoint==0: return {"ok":false,"value":PackedByteArray(),"end":bytes.size()}
			_append_codepoint(decoded,codepoint)
		else:
			return {"ok":false,"value":"","end":bytes.size()}
		i+=1
	return {"ok":false,"value":"","end":bytes.size()}


static func _hex4(bytes: PackedByteArray,start: int) -> int:
	var value: int = 0
	for offset in range(4):
		var byte: int = bytes[start+offset]
		var digit: int = -1
		if byte>=48 and byte<=57: digit=byte-48
		elif byte>=65 and byte<=70: digit=byte-55
		elif byte>=97 and byte<=102: digit=byte-87
		if digit<0: return -1
		value=(value<<4)|digit
	return value


static func _append_codepoint(bytes: PackedByteArray,codepoint: int) -> void:
	if codepoint<=127: bytes.append(codepoint)
	elif codepoint<=2047:
		bytes.append(192|(codepoint>>6)); bytes.append(128|(codepoint&63))
	elif codepoint<=65535:
		bytes.append(224|(codepoint>>12)); bytes.append(128|((codepoint>>6)&63)); bytes.append(128|(codepoint&63))
	else:
		bytes.append(240|(codepoint>>18)); bytes.append(128|((codepoint>>12)&63)); bytes.append(128|((codepoint>>6)&63)); bytes.append(128|(codepoint&63))
