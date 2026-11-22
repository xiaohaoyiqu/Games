extends RefCounted

const Issue = preload("res://game/configuration/configuration_issue.gd")
const Reader = preload("res://game/configuration/bounded_json.gd")
const Skills = preload("res://game/combat/skill_catalog.gd")
const MAX_BYTES: int = 65536
const MAX_ROOMS: int = 8
const MAX_ENEMIES: int = 64
const PATHS := {"dungeon":"res://data/dungeons/echo_ruins.json","roster":"res://data/enemies/ruins_roster.json","difficulties":"res://data/dungeons/difficulties.json","skills":"res://data/skills/training_moves.json"}
var _plans: Dictionary = {}
var _moves: Dictionary = {}
var _diagnostics: Array[String] = []
var _issue: Dictionary = {}
var _message: String = ""
var last_read_bytes: int = 0


func _init(documents: Dictionary={}) -> void:
	var source: Dictionary = {} if documents.is_empty() else documents
	if documents.is_empty():
		for key in PATHS: source[key] = _read(PATHS[key])
	var problem: Dictionary = _validation_issue(source)
	if not problem.is_empty() or not _issue.is_empty():
		if _issue.is_empty(): _issue = problem
		_message = Issue.message(_issue); _diagnostics.append(_message)
		return
	_moves = source["skills"]["moves"].duplicate(true)
	for id: String in source["difficulties"]["modes"]:
		_compile(id,source)


func _read(path: String) -> Variant:
	var result: Dictionary = _read_document(path)
	last_read_bytes = result["bytes"]
	if not result["ok"] and _issue.is_empty():
		var kind: Variant = PATHS.find_key(path)
		_issue = Issue.make(kind if kind is String else "configuration",result["code"])
	return result["value"] if result["ok"] else null

func _read_document(path: String) -> Dictionary:
	return Reader.read(path)

func issue_snapshot() -> Dictionary: return _issue.duplicate(true)
func error_message() -> String: return _message


static func _number(value: Variant,low: float,high: float) -> bool:
	return typeof(value) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(value)) and float(value) >= low and float(value) <= high


static func _whole(value: Variant,low: int,high: int) -> bool:
	return _number(value,low,high) and float(value) == floor(float(value))


static func _text(value: Variant,limit: int) -> bool:
	return value is String and not value.is_empty() and value.length() <= limit


static func _spawn_issue(entry: Dictionary,path: String) -> Dictionary:
	if not _number(entry.get("x"),65,1215): return Issue.make("dungeon","value",path+".x")
	if not _number(entry.get("y"),350,640): return Issue.make("dungeon","value",path+".y")
	return {}


static func _formation_issue(value: Variant,enemies: Array,path: String) -> Dictionary:
	if not value is Dictionary or value.size() != 2: return Issue.make("dungeon","shape",path)
	if not _text(value.get("subtitle"),160): return Issue.make("dungeon","value",path+".subtitle")
	var positions: Variant = value.get("positions")
	if not positions is Array or positions.size() != enemies.size():
		return Issue.make("dungeon","shape",path+".positions")
	var occupied: Dictionary = {}
	var changed: bool = false
	for index in range(enemies.size()):
		var entry: Variant = positions[index]
		var location: String = path+".positions["+str(index)+"]"
		if not entry is Dictionary or entry.size() != 2: return Issue.make("dungeon","shape",location)
		var problem: Dictionary = _spawn_issue(entry,location)
		if not problem.is_empty(): return problem
		var point := Vector2(entry["x"],entry["y"])
		if occupied.has(point): return Issue.make("dungeon","value",location)
		occupied[point] = true
		var original: Dictionary = enemies[index]
		changed = changed or point != Vector2(original["x"],original["y"])
	return {} if changed else Issue.make("dungeon","value",path+".positions")


func _validate(source: Dictionary) -> bool:
	return _validation_issue(source).is_empty()

func _validation_issue(source: Dictionary) -> Dictionary:
	for key: String in PATHS:
		if not source.get(key) is Dictionary: return Issue.make(key,"shape")
		if not _whole(source[key].get("schema_version"),1,1): return Issue.make(key,"shape","schema_version")
	var skill_problem: Dictionary = Skills.document_issue(source["skills"],Skills.DUNGEON_REQUIRED)
	if not skill_problem.is_empty(): return skill_problem
	var skills: Dictionary = source["skills"]["moves"]
	var roster: Variant = source["roster"].get("enemies")
	if not roster is Dictionary or roster.is_empty() or roster.size()>32: return Issue.make("roster","shape","enemies")
	for id in roster:
		var enemy: Variant = roster[id]
		if not _text(id,80): return Issue.make("roster","value","enemies")
		var path: String = "enemies."+id
		if not enemy is Dictionary: return Issue.make("roster","shape",path)
		if not _text(enemy.get("name"),80): return Issue.make("roster","value",path+".name")
		if not _whole(enemy.get("hp"),1,10000): return Issue.make("roster","value",path+".hp")
		if not _whole(enemy.get("gold"),0,10000): return Issue.make("roster","value",path+".gold")
		if not _number(enemy.get("speed"),0.0,5.0): return Issue.make("roster","value",path+".speed")
		if not _number(enemy.get("incoming"),0.05,2.0): return Issue.make("roster","value",path+".incoming")
		if not _number(enemy.get("launch_resistance"),0.0,2.0): return Issue.make("roster","value",path+".launch_resistance")
		if not enemy.get("move") is String: return Issue.make("roster","value",path+".move")
		if not skills.has(enemy["move"]): return Issue.make("roster","reference",path+".move")
	var dungeon: Dictionary = source["dungeon"]
	if not _text(dungeon.get("name"),80): return Issue.make("dungeon","value","name")
	if not _whole(dungeon.get("player_hp"),1,10000): return Issue.make("dungeon","value","player_hp")
	if not _whole(dungeon.get("room_heal"),0,1000): return Issue.make("dungeon","value","room_heal")
	var rooms: Variant = dungeon.get("rooms")
	if not rooms is Array or rooms.is_empty() or rooms.size()>MAX_ROOMS: return Issue.make("dungeon","shape","rooms")
	var total: int = 0; var bosses: int = 0
	for index in range(rooms.size()):
		var room: Variant = rooms[index]; var path: String = "rooms["+str(index)+"]"
		if not room is Dictionary: return Issue.make("dungeon","shape",path)
		if not _text(room.get("name"),80): return Issue.make("dungeon","value",path+".name")
		if not _text(room.get("subtitle"),160): return Issue.make("dungeon","value",path+".subtitle")
		var enemies: Variant = room.get("enemies")
		if not enemies is Array or enemies.is_empty() or enemies.size()>16: return Issue.make("dungeon","shape",path+".enemies")
		total += enemies.size()
		if total>MAX_ENEMIES: return Issue.make("dungeon","value","rooms")
		for n in range(enemies.size()):
			var entry: Variant = enemies[n]; var location: String = path+".enemies["+str(n)+"]"
			if not entry is Dictionary: return Issue.make("dungeon","shape",location)
			if not entry.get("kind") is String: return Issue.make("dungeon","value",location+".kind")
			if not roster.has(entry["kind"]): return Issue.make("dungeon","reference",location+".kind")
			var spawn_problem: Dictionary = _spawn_issue(entry,location)
			if not spawn_problem.is_empty(): return spawn_problem
			if entry["kind"]=="warden":
				bosses += 1
				if index!=rooms.size()-1: return Issue.make("dungeon","value",location+".kind")
		if room.has("veteran_formation"):
			var formation_problem: Dictionary = _formation_issue(room["veteran_formation"],enemies,path+".veteran_formation")
			if not formation_problem.is_empty(): return formation_problem
	if bosses!=1: return Issue.make("dungeon","value","rooms")
	var modes: Variant = source["difficulties"].get("modes")
	if not modes is Dictionary or not modes.has("normal") or not modes.has("veteran") or modes.size()>8: return Issue.make("difficulties","shape","modes")
	for id in modes:
		var mode: Variant = modes[id]
		if not _text(id,40): return Issue.make("difficulties","value","modes")
		var path: String = "modes."+id
		if not mode is Dictionary: return Issue.make("difficulties","shape",path)
		if not _text(mode.get("name"),24): return Issue.make("difficulties","value",path+".name")
		if not _whole(mode.get("unlock_clears"),0,1000): return Issue.make("difficulties","value",path+".unlock_clears")
		if not _whole(mode.get("unlock_level"),1,10): return Issue.make("difficulties","value",path+".unlock_level")
		for key: String in ["enemy_hp_percent","enemy_damage_percent","gold_percent","xp_percent"]:
			if not _whole(mode.get(key),25,500): return Issue.make("difficulties","value",path+"."+key)
		if not _whole(mode.get("materials"),0,100): return Issue.make("difficulties","value",path+".materials")
	return {}


static func scaled(amount: int,percent: int) -> int:
	# Bounded integer inputs; half values round up consistently across platforms.
	return (amount*percent+50)/100


func _compile(id: String,source: Dictionary) -> void:
	var mode: Dictionary = source["difficulties"]["modes"][id].duplicate(true)
	var roster: Dictionary = source["roster"]["enemies"].duplicate(true)
	for enemy in roster.values():
		enemy["hp"] = maxi(1,scaled(int(enemy["hp"]),int(mode["enemy_hp_percent"])))
		enemy["gold"] = scaled(int(enemy["gold"]),int(mode["gold_percent"]))
		enemy["damage_multiplier"] = float(mode["enemy_damage_percent"])/100.0
	var dungeon: Dictionary = source["dungeon"].duplicate(true)
	var formation_rooms: int = 0
	var values: Array[int] = []
	for room: Dictionary in dungeon["rooms"]:
		var formation: Dictionary = room.get("veteran_formation",{})
		var enemies: Array = room["enemies"]
		if id == "veteran" and not formation.is_empty():
			formation_rooms += 1
			room["subtitle"] = formation["subtitle"]
			for index in range(enemies.size()):
				var point: Dictionary = formation["positions"][index]
				enemies[index]["x"] = point["x"]
				enemies[index]["y"] = point["y"]
		room.erase("veteran_formation")
		for enemy: Dictionary in enemies: values.append(int(roster[enemy["kind"]]["gold"]))
	values.sort()
	values.reverse()
	var prefix: Array[int] = [0]
	for amount in values: prefix.append(prefix.back()+amount)
	var preview := mode.duplicate(true)
	preview["id"] = id
	preview["dungeon_name"] = dungeon["name"]
	preview["rooms"] = dungeon["rooms"].size()
	preview["formation_rooms"] = formation_rooms
	preview["enemies"] = values.size()
	preview["max_gold"] = prefix.back()
	preview["clear_xp"] = scaled(values.size()*5+45,int(mode["xp_percent"]))
	_plans[id] = {"preview":preview,"gold_prefix":prefix,"dungeon":dungeon,"roster":roster}


func available() -> bool:
	return not _plans.is_empty()


func diagnostics() -> Array[String]:
	return _diagnostics.duplicate()


func ids() -> Array:
	return _plans.keys()


func preview(id: String) -> Dictionary:
	return _plans[id]["preview"].duplicate(true) if _plans.has(id) else {}


func unlocked(id: String,level: int,clears: int) -> bool:
	if not _plans.has(id): return false
	var mode: Dictionary = _plans[id]["preview"]
	return level >= int(mode["unlock_level"]) and clears >= int(mode["unlock_clears"])


func plan(id: String) -> Dictionary:
	if not _plans.has(id): return {}
	var result: Dictionary = _plans[id].duplicate(true)
	result["moves"] = _moves.duplicate(true)
	return result


func moves_snapshot() -> Dictionary:
	return _moves.duplicate(true)


func gold_budget(id: String,kills: int) -> int:
	if not _plans.has(id) or kills < 0: return -1
	var prefix: Array = _plans[id]["gold_prefix"]
	return int(prefix[kills]) if kills < prefix.size() else -1


func experience(id: String,kills: int,cleared: bool) -> int:
	if gold_budget(id,kills) < 0: return -1
	return scaled(kills*5+(45 if cleared else 0),int(_plans[id]["preview"]["xp_percent"]))
