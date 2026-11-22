extends "res://game/persistence/settlement_journal.gd"

const NAME: String = "solo-settlement.pending.json"
const FORMAT: String = "dungeon-solo-settlement"
const MAX_BYTES: int = 1048576


func _init(directory: String, profile_rules: RefCounted) -> void:
	super(directory,NAME,MAX_BYTES)
	rules = profile_rules


func entry(base: Dictionary,target: Dictionary,id: String,difficulty: String,request: Dictionary) -> Dictionary:
	return {
		"format":FORMAT,"version":1,"id":id,"difficulty":difficulty,
		"base":base.duplicate(true),"target":target.duplicate(true),"result":request.duplicate(true)
	}


func _candidate(value: Variant) -> Dictionary:
	if not value is Dictionary or value.size() != 7: return {}
	if value.get("format") != FORMAT or not rules.whole(value.get("version"),1,1): return {}
	var id: Variant = value.get("id")
	var difficulty: Variant = value.get("difficulty")
	if not id is String or id.begins_with("party-") or not difficulty is String: return {}
	var original: Variant = value.get("result")
	if not original is Dictionary or original.size() != 3: return {}
	if not rules.whole(original.get("kills"),0,2147483647) or not rules.whole(original.get("gold"),0,2147483647) or not original.get("cleared") is bool: return {}
	var base: Variant = value.get("base")
	var target: Variant = value.get("target")
	if not rules.valid(base) or not rules.valid(target): return {}
	# Refuse replay if the current rules would award anything different.
	var replay: Dictionary = rules.settle(base,id,int(original["kills"]),int(original["gold"]),original["cleared"],difficulty)
	if int(target["schema_version"]) < int(base["schema_version"]): return {}
	if not replay["ok"] or not _matches_recorded_profile(replay["data"],target): return {}
	return replay["data"]
