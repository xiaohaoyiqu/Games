extends "res://game/persistence/settlement_journal.gd"

const Storage = preload("res://game/persistence/settlement_journal.gd")
const Rules=preload("res://game/equipment/profile_rules.gd")
const Characters=preload("res://game/network/party_character.gd")
const Run=preload("res://game/network/party_run.gd")
const Content=preload("res://game/network/party_content.gd")
const MAX_BYTES: int=1048576
const NAME: String="party-settlement.pending.json"
var _fingerprint_thread: Thread
var stamp: String


func _init(directory: String) -> void:
	super(directory,NAME,MAX_BYTES)
	# Private file handles and hashing only; rules remain owned by this Journal.
	# Construction does not publish until the worker has joined.
	var status: Error = ERR_UNAVAILABLE
	if _threads_available():
		_fingerprint_thread = Thread.new()
		status = _start_fingerprint(_fingerprint_thread)
	rules=Rules.new()
	stamp=_fingerprint_thread.wait_to_finish() if status==OK else Content.fingerprint()
	_fingerprint_thread=null
func _threads_available() -> bool: return OS.has_feature("threads")
func _start_fingerprint(worker: Thread) -> Error:
	return worker.start(Content.fingerprint)
func _notification(what: int) -> void:
	# Also drain if construction is interrupted before its ordinary join.
	if what==NOTIFICATION_PREDELETE and _fingerprint_thread!=null and _fingerprint_thread.is_started():
		_fingerprint_thread.wait_to_finish()
func entry(base: Dictionary,manifest: Dictionary,self_id: int,frame: Dictionary) -> Dictionary:
	return {"format":"dungeon-party-settlement","version":1,"content":stamp,"base":base.duplicate(true),"manifest":manifest.duplicate(true),"self":self_id,"frame":frame.duplicate(true)}
func _candidate(value: Variant) -> Dictionary:
	if not value is Dictionary or value.size()!=7:return {}
	if value.get("format")!="dungeon-party-settlement" or not rules.whole(value.get("version"),1,1) or stamp.is_empty() or value.get("content")!=stamp:return {}
	if not rules.valid(value.get("base")) or not value.get("manifest") is Dictionary or not value.get("frame") is Dictionary or not rules.whole(value.get("self"),1,2147483647):return {}
	var characters:=Characters.new(rules)
	var contract:=Run.new(characters,rules.dungeons,value["manifest"])
	if not contract.available() or not contract.valid_frame(value["frame"],int(value["self"])) or int(value["frame"]["phase"]) not in [2,3]:return {}
	var own: Dictionary=characters.describe(value["base"]);var matched: bool=false
	for member in contract.manifest["members"]:
		if int(member["peer"])==int(value["self"]):matched=same(member["character"],own)
	if not matched:return {}
	var frame: Dictionary=value["frame"]
	var result: Dictionary=rules.settle(value["base"],"party-"+contract.manifest["run"],int(frame["kills"]),int(frame["gold"]),frame["phase"]==2,contract.manifest["mode"])
	return result["data"] if result["ok"] else {}


static func same(a: Variant,b: Variant,depth: int=0) -> bool:
	return Storage.same_value(a,b,depth)
