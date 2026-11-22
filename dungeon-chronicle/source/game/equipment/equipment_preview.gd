extends RefCounted

# Pure equipment projections. Uses actual profile stats and transaction eligibility.
const KEYS: Array[String]=["hp","damage","incoming","speed","cooldown"]
var rules: RefCounted
func _init(profile_rules: RefCounted) -> void:rules=profile_rules
func describe(profile: Dictionary,id: String,operation: String="equip") -> Dictionary:
	if not rules.valid(profile) or not rules.catalog.has(id) or operation not in ["equip","upgrade"]:return {"ok":false,"reason":"装备预览不可用"}
	var item: Dictionary=rules.catalog[id];var slot: String=item["slot"]
	var owned: bool=profile["owned"].has(id);var worn: bool=profile["equipped"][slot]==id
	var rank: int=int(profile["upgrades"].get(id,0));var next: Dictionary=profile.duplicate()
	var target_rank: int=rank;var reason: String="";var cost: Dictionary={"gold":0,"materials":0}
	if operation=="equip":
		if not worn:
			next["equipped"] = profile["equipped"].duplicate()
			next["equipped"][slot] = id
		if not owned:
			next["owned"] = profile["owned"].duplicate()
			next["owned"].append(id)
			reason = "预览不购买装备；获得后才能装备。"
	else:
		if not owned:reason="尚未获得这件装备，不能强化。"
		elif rank>=3:reason="已达到当前强化上限 +3。"
		else:
			target_rank=rank+1;cost=rules.upgrade_cost(rank)
			next["upgrades"] = profile["upgrades"].duplicate()
			next["upgrades"][id] = target_rank
			if not worn:reason="所选未穿戴；强化不会自动更换装备。"
	var current: Dictionary=rules.stats(profile);var after: Dictionary=rules.stats(next)
	var changes: Array=[]
	for key in KEYS:
		var delta: float=float(after[key])-float(current[key]);var sign: int=0 if is_zero_approx(delta) else (1 if delta>0 else -1)
		var benefit: int=-sign if key in ["incoming","cooldown"] else sign
		changes.append({"key":key,"before":current[key],"after":after[key],"delta":delta,"benefit":benefit})
	var issue: String = rules.equipment_change_issue(profile,operation,id)
	var eligible := issue.is_empty()
	if not eligible and owned:reason=issue+("；强化不会自动更换装备。" if operation=="upgrade" and not worn and rank<3 else "")
	return {"ok":true,"item":id,"slot":slot,"owned":owned,"worn":worn,"rank":rank,"target_rank":target_rank,"operation":operation,"before":current,"after":after,"changes":changes,"cost":cost,"eligible":eligible,"reason":reason,"price":int(item["price"])}
