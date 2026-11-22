extends RefCounted

# Data only: no scripts, remote paths, actor ownership or profile writes.
const Issue = preload("res://game/configuration/configuration_issue.gd")
const Reader = preload("res://game/configuration/bounded_json.gd")
const MoveRules = preload("res://game/combat/move_rules.gd")
const PATH: String = "res://data/skills/training_moves.json"
const MAX_MOVES: int = 64
const TRAINING_REQUIRED: Array[String] = ["slash_1","slash_2","slash_3","rising_slash","dummy_strike"]
const WARDEN_ATTACKS: Array[String] = ["warden_slam","warden_sweep"]
const DUNGEON_REQUIRED: Array[String] = ["slash_1","slash_2","slash_3","rising_slash","warden_slam","warden_sweep"]
const FIELDS: Array[String] = ["label","windup","active","recovery","reach","depth","damage","knockback","launch","height_max","cooldown","projectile","omnidirectional","height_min","rear"]
var _moves: Dictionary = {}
var _error: String = ""
var _issue: Dictionary = {}
var _message: String = ""
var last_read_bytes: int = 0

func _init(document: Variant=null,required: Array[String]=TRAINING_REQUIRED) -> void:
	var source: Variant = document
	if document == null:
		var result: Dictionary = _read_document()
		last_read_bytes = result["bytes"]
		if not result["ok"]:
			_error = result["code"]; _set_issue(Issue.make("skills",_error)); return
		source = result["value"]
	var problem: Dictionary = document_issue(source,required)
	if not problem.is_empty(): _error = "shape"; _set_issue(problem); return
	_moves = source["moves"].duplicate(true)

func _read_document() -> Dictionary: return Reader.read(PATH)
func available() -> bool: return not _moves.is_empty()
func error_code() -> String: return _error
func issue_snapshot() -> Dictionary: return _issue.duplicate(true)
func error_message() -> String: return _message
func _set_issue(value: Dictionary) -> void:
	_issue = value.duplicate(true); _message = Issue.message(_issue)
func moves_snapshot() -> Dictionary: return _moves.duplicate(true)

func selected_moves_snapshot(ids: Array[String]) -> Dictionary:
	var selected: Dictionary = {}
	for id: String in ids:
		if _moves.has(id):
			selected[id] = _moves[id].duplicate(true)
	return selected

static func _identifier(value: Variant) -> bool:
	if not value is String or value.is_empty() or value.length()>80: return false
	for i in range(value.length()):
		var code: int = value.unicode_at(i)
		if not (code>=97 and code<=122) and not (i>0 and (code==95 or (code>=48 and code<=57))): return false
	return true

static func _label(value: Variant) -> bool:
	if not value is String or value.is_empty() or value.length()>96: return false
	for i in range(value.length()):
		var code: int = value.unicode_at(i)
		if code<32 or code==127 or code==65533: return false
	return true

static func valid_document(value: Variant,required: Array[String]=TRAINING_REQUIRED) -> bool:
	return document_issue(value,required).is_empty()

static func document_issue(value: Variant,required: Array[String]=TRAINING_REQUIRED) -> Dictionary:
	if not value is Dictionary or value.size()!=2: return Issue.make("skills","shape")
	var version: Variant = value.get("schema_version")
	if typeof(version) not in [TYPE_INT,TYPE_FLOAT] or not is_finite(float(version)) or version!=1: return Issue.make("skills","shape","schema_version")
	var moves: Variant = value.get("moves")
	if not moves is Dictionary or moves.is_empty() or moves.size()>MAX_MOVES: return Issue.make("skills","shape","moves")
	for id in moves:
		var move: Variant = moves[id]
		if not _identifier(id): return Issue.make("skills","value","moves")
		var path: String = "moves."+id
		var field: String = MoveRules.invalid_field(move)
		if field=="move": return Issue.make("skills","shape",path)
		if field=="timing": return Issue.make("skills","timing",path)
		if not field.is_empty(): return Issue.make("skills","value",path+"."+field)
		if not _label(move.get("label")): return Issue.make("skills","value",path+".label")
		for key in move:
			if key not in FIELDS: return Issue.make("skills","shape",path)
	for id: String in required:
		if not moves.has(id): return Issue.make("skills","required","moves."+id)
	return {}
