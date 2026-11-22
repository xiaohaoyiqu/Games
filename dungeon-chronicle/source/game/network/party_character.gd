extends RefCounted

## Minimal character description. Currency, quests, paths and run history stay local.
var _rules: RefCounted


func _init(profile_rules: RefCounted) -> void:
	_rules = profile_rules


func describe(profile: Dictionary) -> Dictionary:
	if not _rules.valid(profile): return {}
	var level: int = int(_rules.level_info(profile)["level"])
	var result := {"level":level,"veteran":level >= 2 and int(profile["clears"]) >= 1,"ranks":{}}
	for slot in ["weapon","armor","charm"]:
		var id: String = profile["equipped"][slot]
		result[slot] = id
		result["ranks"][slot] = int(profile["upgrades"].get(id,0))
	return result


func valid(value: Variant) -> bool:
	if not _rules.available(): return false
	if not value is Dictionary or value.size() != 6: return false
	if not _rules.whole(value.get("level"),1,10) or not value.get("veteran") is bool: return false
	if value["veteran"] and int(value["level"]) < 2: return false
	if not value.get("ranks") is Dictionary or value["ranks"].size() != 3: return false
	for slot in ["weapon","armor","charm"]:
		var id: Variant = value.get(slot)
		if not id is String or not _rules.catalog.has(id) or _rules.catalog[id]["slot"] != slot: return false
		if not _rules.whole(value["ranks"].get(slot),0,3): return false
	return true


func stats(value: Dictionary) -> Dictionary:
	if not valid(value): return {}
	var profile: Dictionary = _rules.fresh()
	for level in range(1,int(value["level"])): profile["xp"] += 100+(level-1)*50
	for slot in ["weapon","armor","charm"]:
		var id: String = value[slot]
		if not profile["owned"].has(id): profile["owned"].append(id)
		profile["equipped"][slot] = id
		profile["upgrades"][id] = int(value["ranks"][slot])
	return _rules.stats(profile)
