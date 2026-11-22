extends RefCounted

## Frozen local practice only. No Store, Session, actors, rewards or transactions.
const Preview=preload("res://game/equipment/equipment_preview.gd")
var revision: int=0
var _reason: String="装备试用不可用。"
var _info: Dictionary={}
var _stats: Dictionary={}
var _weapons: Dictionary={}
var _side: String="candidate"

func _init(rules: RefCounted,profile: Dictionary,id: String) -> void:
	if not rules.valid(profile) or not rules.catalog.has(id):return
	var owned: bool=profile["owned"].has(id)
	if not owned and int(rules.catalog[id]["price"])<=0:
		_reason="尚未获得的奖励装备不能试用。";return
	var view: Dictionary=Preview.new(rules).describe(profile,id,"equip")
	if not view.get("ok",false):return
	if view["worn"]:
		_reason="这件装备已经穿戴，与当前配装相同。";return
	var slot: String=view["slot"];var current_id: String=profile["equipped"][slot]
	_info={"source_revision":int(profile["revision"]),"level":int(rules.level_info(profile)["level"]),
		"slot":slot,"current_id":current_id,"current_name":str(rules.catalog[current_id]["name"]),
		"current_rank":int(profile["upgrades"].get(current_id,0)),"candidate_id":id,
		"candidate_name":str(rules.catalog[id]["name"]),"candidate_rank":int(view["rank"]),
		"owned":owned,"price":int(view["price"])}
	_stats={"current":view["before"].duplicate(true),"candidate":view["after"].duplicate(true)}
	_weapons={"current":str(profile["equipped"]["weapon"]),
		"candidate":id if slot=="weapon" else str(profile["equipped"]["weapon"])}
	_reason=""

func available() -> bool:return not _info.is_empty()
func reason() -> String:return _reason
func selected_side() -> String:return _side
func select_side(side: String) -> bool:
	if not available() or side not in ["current","candidate"] or side==_side:return false
	_side=side;revision+=1
	return true
func stats() -> Dictionary:
	return _stats[_side].duplicate(true) if available() else {}
func weapon_id() -> String:return str(_weapons[_side]) if available() else ""
func describe() -> Dictionary:
	if not available():return {"ok":false,"reason":_reason}
	var result: Dictionary=_info.duplicate(true)
	result["ok"]=true;result["side"]=_side;result["revision"]=revision;result["stats"]=stats()
	return result
