extends RefCounted

const MoveRules=preload("res://game/combat/move_rules.gd")
const Contact=preload("res://game/combat/combat_rules.gd")

## Read-only current windup. Never guesses a projectile target or future path.
static func describe(origin: Vector2,facing: float,height: float,move_id: String,clock: float,move: Dictionary) -> Dictionary:
	if not origin.is_finite() or facing not in [-1.0,1.0] or not is_finite(height) or height<0.0 or height>2000.0 or move_id.is_empty() or move_id.length()>48 or not is_finite(clock) or clock<0.0 or not MoveRules.valid(move):
		return {"visible":false}
	var windup: float=float(move["windup"])
	if windup<=0.0 or clock>=windup:return {"visible":false}
	var result: Dictionary={"visible":true,"progress":clampf(clock/windup,0.0,1.0),"origin":origin,"facing":facing,"marker":origin+Vector2(0,-height-154)}
	if bool(move.get("projectile",false)):
		result["kind"]="shot";result["label"]="射击准备"
		result["has_ground_bounds"]=false
	else:
		result["kind"]="melee";result["label"]="范围重击" if bool(move.get("omnidirectional",false)) else "近战准备"
		result["has_ground_bounds"]=true;result["omnidirectional"]=bool(move.get("omnidirectional",false))
		result["bounds"]=Contact.ground_contact_bounds(origin,facing,float(move["reach"]),float(move["depth"]),float(move.get("rear",Contact.DEFAULT_REAR)),result["omnidirectional"])
	return result
