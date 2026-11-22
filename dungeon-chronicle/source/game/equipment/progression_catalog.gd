extends RefCounted

# Fixed local gameplay definitions. No player path, native object loading or cache.
const Issue = preload("res://game/configuration/configuration_issue.gd")
const Reader = preload("res://game/configuration/bounded_json.gd")
const PATHS = {"items":"res://data/equipment/items.json","quests":"res://data/quests/town_quests.json"}
const MAX_BYTES: int = Reader.MAX_BYTES
const MAX_DEPTH: int = Reader.MAX_DEPTH
const MAX_ITEMS: int = 64
const MAX_ITEM_COOLDOWN_MULTIPLIER: float = 2.0
const LEGACY_QUEST_IDS: Array[String] = ["echo","patrol"]
const QUEST_TYPES := {"echo":"clear","patrol":"kills","veteran_echo":"veteran_clear"}
const REQUIRED = {"rust_sword":"weapon","cloth_vest":"armor","plain_charm":"charm","echo_blade":"weapon","ruin_charm":"charm"}
var _items: Dictionary = {}
var _quests: Dictionary = {}
var _ready: bool = false
var _error: String = ""
var _issue: Dictionary = {}
var _message: String = ""
var last_read_bytes: int = 0
var _reads: Dictionary = {}

func _init(documents: Variant=null) -> void:
	var source: Dictionary = {}
	if documents == null:
		for kind: String in PATHS:
			var result: Dictionary = _read_document(PATHS[kind])
			_reads[kind] = {"bytes":last_read_bytes,"ok":result["ok"]}
			if not result["ok"]: _fail(Issue.make(kind,result["code"]),kind+"-"+result["code"]); return
			source[kind] = result["value"]
	elif documents is Dictionary:
		source = documents
	else: _fail(Issue.make("configuration","shape"),"documents-type"); return
	if source.size()!=2 or not source.has("items") or not source.has("quests"): _fail(Issue.make("configuration","shape"),"documents-shape"); return
	var problem: Dictionary = _items_issue(source["items"])
	if not problem.is_empty(): _fail(problem,"items-shape"); return
	problem = _quests_issue(source["quests"],source["items"]["items"])
	if not problem.is_empty(): _fail(problem,"quests-shape"); return
	_items = source["items"]["items"].duplicate(true)
	_quests = source["quests"]["quests"].duplicate(true)
	_ready = true

func available() -> bool: return _ready
func error_code() -> String: return _error
func issue_snapshot() -> Dictionary: return _issue.duplicate(true)
func error_message() -> String: return _message
func _fail(problem: Dictionary,legacy: String) -> void:
	_error = legacy; _issue = problem.duplicate(true); _message = Issue.message(_issue)
func items_snapshot() -> Dictionary: return _items.duplicate(true)
func quests_snapshot() -> Dictionary: return _quests.duplicate(true)
func read_summary() -> Dictionary: return _reads.duplicate(true)

func _read_document(path: String) -> Dictionary:
	var result: Dictionary = Reader.read(path)
	last_read_bytes = result["bytes"]
	return result

static func _whole(value: Variant,low: int,high: int) -> bool:
	return typeof(value) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(value)) and float(value)==floor(float(value)) and value>=low and value<=high
static func _number(value: Variant,low: float,high: float) -> bool:
	return typeof(value) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(value)) and value>=low and value<=high
static func _text(value: Variant,limit: int) -> bool:
	if not value is String or value.is_empty() or value.length()>limit: return false
	for i in range(value.length()):
		var code: int = value.unicode_at(i)
		if code<32 or code==127 or code==65533: return false
	return true
static func _identifier(value: Variant) -> bool:
	if not value is String or value.is_empty() or value.length()>48: return false
	for i in range(value.length()):
		var code: int = value.unicode_at(i)
		if not (code>=97 and code<=122) and not (i>0 and (code==95 or (code>=48 and code<=57))): return false
	return true
static func _root(value: Variant,key: String) -> bool:
	return value is Dictionary and value.size()==2 and _whole(value.get("schema_version"),1,1) and value.get(key) is Dictionary
static func _valid_items(value: Variant) -> bool:
	return _items_issue(value).is_empty()
static func _valid_quests(value: Variant,items: Dictionary) -> bool:
	return _quests_issue(value,items).is_empty()

static func _items_issue(value: Variant) -> Dictionary:
	if not _root(value,"items"): return Issue.make("items","shape")
	if value["items"].size()<REQUIRED.size() or value["items"].size()>MAX_ITEMS: return Issue.make("items","shape","items")
	var items: Dictionary = value["items"]
	for id in items:
		var item: Variant = items[id]
		if not _identifier(id): return Issue.make("items","value","items")
		var path: String = "items."+id
		if not item is Dictionary or item.size()!=9: return Issue.make("items","shape",path)
		for key: String in ["name","source"]:
			if not _text(item.get(key),96): return Issue.make("items","value",path+"."+key)
		if item.get("slot") not in ["weapon","armor","charm"]: return Issue.make("items","value",path+".slot")
		if not _whole(item.get("hp"),0,500): return Issue.make("items","value",path+".hp")
		if not _whole(item.get("price"),0,1000000): return Issue.make("items","value",path+".price")
		if not _number(item.get("damage"),0,2): return Issue.make("items","value",path+".damage")
		if not _number(item.get("incoming"),0.25,2): return Issue.make("items","value",path+".incoming")
		if not _number(item.get("speed"),0,0.5): return Issue.make("items","value",path+".speed")
		if not _number(item.get("cooldown"),0.25,MAX_ITEM_COOLDOWN_MULTIPLIER): return Issue.make("items","value",path+".cooldown")
	for id: String in REQUIRED:
		if not items.has(id): return Issue.make("items","required","items."+id)
		if items[id]["slot"]!=REQUIRED[id]: return Issue.make("items","value","items."+id+".slot")
	return {}

static func _quests_issue(value: Variant,items: Dictionary) -> Dictionary:
	if not _root(value,"quests") or value["quests"].size()!=QUEST_TYPES.size(): return Issue.make("quests","shape","quests")
	for id: String in QUEST_TYPES:
		var quest: Variant = value["quests"].get(id)
		var path: String = "quests."+id
		if not quest is Dictionary or quest.size()!=7: return Issue.make("quests","shape",path)
		if not _text(quest.get("name"),96): return Issue.make("quests","value",path+".name")
		if not _text(quest.get("description"),320): return Issue.make("quests","value",path+".description")
		if quest.get("type") != QUEST_TYPES[id]: return Issue.make("quests","value",path+".type")
		if not _whole(quest.get("goal"),1,64) or (quest["type"]!="kills" and quest["goal"]!=1): return Issue.make("quests","value",path+".goal")
		if not _whole(quest.get("gold"),0,1000000): return Issue.make("quests","value",path+".gold")
		if not _whole(quest.get("xp"),0,1000000): return Issue.make("quests","value",path+".xp")
		var reward: Variant = quest.get("item")
		if not reward is String: return Issue.make("quests","value",path+".item")
		if not reward.is_empty() and not items.has(reward): return Issue.make("quests","reference",path+".item")
	return {}
