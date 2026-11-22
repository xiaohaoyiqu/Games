extends RefCounted

## Given snapshots only; no actors, profile mutation, files, DPS or equipment ranking.
const Values=preload("res://game/combat/combat_values.gd")
const MoveRules=preload("res://game/combat/move_rules.gd")
const ATTACKS: Array[String]=["slash_1","slash_2","slash_3","rising_slash"]
const LABELS: Array[String]=["普攻 I","普攻 II","普攻 III","破空斩"]
const INPUT_HITS: Array[int]=[1,8,50]

static func _stats_valid(value: Dictionary) -> bool:
	if value.size()!=5:return false
	for key: String in ["hp","damage","incoming","speed","cooldown"]:
		var number: Variant=value.get(key)
		if typeof(number) not in [TYPE_INT,TYPE_FLOAT] or not is_finite(float(number)):return false
		if float(number)<0.0 or float(number)>10000.0:return false
	return float(value["hp"])>=1.0 and float(value["hp"])==floor(float(value["hp"]))

static func describe(before: Dictionary,after: Dictionary,moves: Dictionary) -> Dictionary:
	if not _stats_valid(before) or not _stats_valid(after):return {"ok":false,"reason":"属性样例不可用。"}
	var result: Dictionary={"ok":true,"before":before.duplicate(true),"after":after.duplicate(true),
		"attacks":[],"incoming":[],"skill_available":false,"skill_cooldown_before":0.0,"skill_cooldown_after":0.0,
		"walk_before":Values.WALK_SPEED*float(before["speed"]),"walk_after":Values.WALK_SPEED*float(after["speed"]),
		"missing_moves":false}
	for index in range(ATTACKS.size()):
		var id: String=ATTACKS[index]
		if not MoveRules.valid(moves.get(id)):
			result["missing_moves"]=true
			result["attacks"].append({"id":id,"label":LABELS[index],"available":false})
			continue
		var move: Dictionary=moves[id]
		result["attacks"].append({"id":id,"label":LABELS[index],"available":true,
			"projectile":bool(move.get("projectile",false)),
			"before":Values.scaled_damage(float(move["damage"]),float(before["damage"])),
			"after":Values.scaled_damage(float(move["damage"]),float(after["damage"])),
			"windup":float(move["windup"]),"active":float(move["active"]),"recovery":float(move["recovery"])})
		if id=="rising_slash":
			result["skill_available"]=true
			result["skill_cooldown_before"]=float(move["cooldown"])*float(before["cooldown"])
			result["skill_cooldown_after"]=float(move["cooldown"])*float(after["cooldown"])
	for input: int in INPUT_HITS:
		result["incoming"].append({"input":input,
			"before":mini(int(before["hp"]),Values.scaled_damage(float(input),float(before["incoming"]))),
			"after":mini(int(after["hp"]),Values.scaled_damage(float(input),float(after["incoming"])))})
	return result
