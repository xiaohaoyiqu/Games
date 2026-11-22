extends RefCounted

## Validate a copied move before it can mutate an actor or reach presentation.
## This is a data boundary, not an executable mod/script loading interface.
const MAX_FIELD_VALUE: float = 10000.0
const MAX_DURATION: float = 60.0

static func valid(value: Variant) -> bool:
	return invalid_field(value).is_empty()

static func invalid_field(value: Variant) -> String:
	if not value is Dictionary or value.size() > 32: return "move"
	for key in ["windup","active","recovery","reach","depth","damage","knockback","launch","height_max","cooldown"]:
		var number: Variant = value.get(key)
		if typeof(number) not in [TYPE_INT,TYPE_FLOAT] or not is_finite(float(number)): return key
		if float(number) < 0.0 or float(number) > MAX_FIELD_VALUE: return key
	if float(value["active"]) <= 0.0: return "active"
	if float(value["windup"])+float(value["active"])+float(value["recovery"]) > MAX_DURATION: return "timing"
	if float(value["damage"]) < 1.0 or float(value["damage"]) != floor(float(value["damage"])): return "damage"
	for key in ["projectile","omnidirectional"]:
		if value.has(key) and not value[key] is bool: return key
	for key in ["height_min","rear"]:
		if not value.has(key): continue
		if typeof(value[key]) not in [TYPE_INT,TYPE_FLOAT] or not is_finite(float(value[key])): return key
		if float(value[key]) < 0.0 or float(value[key]) > MAX_FIELD_VALUE: return key
	if float(value.get("height_min",0.0)) > float(value["height_max"]): return "height_min"
	return ""
