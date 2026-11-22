extends RefCounted

const DungeonCatalog = preload("res://game/levels/dungeon_catalog.gd")
var dungeons = DungeonCatalog.new()

const Definitions = preload("res://game/equipment/progression_catalog.gd")
const SCHEMA_VERSION: int = 2
const MAX_COUNTER: int = 100000000
const REVISION_LIMIT_MESSAGE: String = "角色档案修订号已达上限，无法保存新的角色进度。"
const ELITE_QUEST_ID: String = "veteran_echo"
const ELITE_ARMOR_ID: String = "echo_plate"
var definitions: RefCounted
var catalog: Dictionary = {}
var quest_defs: Dictionary = {}
var _configuration_message: String = ""

func _init(documents: Variant=null) -> void:
	definitions = Definitions.new(documents)
	catalog = definitions.items_snapshot()
	quest_defs = definitions.quests_snapshot()
	if not definitions.available(): _configuration_message = definitions.error_message()+"已阻止角色修改；请修复游戏文件后重新启动。"

func available() -> bool: return definitions.available()
func configuration_error() -> String:
	return _configuration_message



# Stable shelf ordering, independent of item strength. Return a fresh ID list.
func shop_ids() -> Array[String]:
	var ids: Array[String]=[]
	if not available():return ids
	for id: String in catalog:
		if int(catalog[id]["price"])>0:ids.append(id)
	ids.sort_custom(func(a: String,b: String) -> bool:
		var a_price: int=int(catalog[a]["price"]);var b_price: int=int(catalog[b]["price"])
		return a<b if a_price==b_price else a_price<b_price)
	return ids


func fresh() -> Dictionary:
	var quests: Dictionary = {}
	for id: String in Definitions.QUEST_TYPES:
		quests[id] = {"state":0,"progress":0}
	return {"schema_version":SCHEMA_VERSION,"revision":0,"gold":0,"xp":0,"materials":0,"clears":0,
		"owned":["rust_sword","cloth_vest","plain_charm"],
		"equipped":{"weapon":"rust_sword","armor":"cloth_vest","charm":"plain_charm"},
		"upgrades":{},"quests":quests,"settled_runs":[]}


func whole(value: Variant,minimum: int,maximum: int) -> bool:
	return typeof(value) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(value)) and float(value) == floor(float(value)) and value >= minimum and value <= maximum


func at_revision_limit(profile: Dictionary) -> bool:
	return whole(profile.get("revision"),MAX_COUNTER,MAX_COUNTER)


func valid(data: Variant) -> bool:
	if not available(): return false
	if not data is Dictionary or not whole(data.get("schema_version"),1,SCHEMA_VERSION):
		return false
	# Both supported schemas have the same eleven top-level fields.
	if data.size() != 11: return false
	for key in ["revision","gold","xp","materials","clears"]:
		if not whole(data.get(key),0,MAX_COUNTER): return false
	if not data.get("owned") is Array or data["owned"].size() > catalog.size(): return false
	var found: Dictionary = {}
	for id in data["owned"]:
		if not id is String or not catalog.has(id) or found.has(id): return false
		found[id] = true
	if not data.get("equipped") is Dictionary or data["equipped"].size() != 3: return false
	for slot in ["weapon","armor","charm"]:
		var id = data["equipped"].get(slot)
		if not id is String or not found.has(id) or catalog[id]["slot"] != slot: return false
	if not data.get("upgrades") is Dictionary: return false
	for id in data["upgrades"]:
		if not found.has(id) or not whole(data["upgrades"][id],0,3): return false
	var quest_ids: Array = Definitions.LEGACY_QUEST_IDS if int(data["schema_version"]) == 1 else quest_defs.keys()
	if not data.get("quests") is Dictionary or data["quests"].size() != quest_ids.size(): return false
	for id: String in quest_ids:
		var state = data["quests"].get(id)
		if not state is Dictionary or state.size() != 2 or not whole(state.get("state"),0,3) or not whole(state.get("progress"),0,int(quest_defs[id]["goal"])): return false
		if int(state["state"]) == 0 and state["progress"] != 0: return false
		if int(state["state"]) == 1 and state["progress"] >= quest_defs[id]["goal"]: return false
		if int(state["state"]) >= 2 and state["progress"] != quest_defs[id]["goal"]: return false
	if not data.get("settled_runs") is Array or data["settled_runs"].size() > 256: return false
	var settled: Dictionary = {}
	for id in data["settled_runs"]:
		if not id is String or id.is_empty() or id.length() > 120 or settled.has(id): return false
		settled[id] = true
	return true


func normalized_profile(data: Variant) -> Dictionary:
	if not valid(data): return {}
	var result: Dictionary = data.duplicate(true)
	if int(result["schema_version"]) == 1:
		result["schema_version"] = SCHEMA_VERSION
		result["quests"]["veteran_echo"] = {"state":0,"progress":0}
	return result


func level_info(profile: Dictionary) -> Dictionary:
	var remaining: int = int(profile["xp"])
	var level: int = 1
	while level < 10 and remaining >= 100+(level-1)*50:
		remaining -= 100+(level-1)*50
		level += 1
	return {"level":level,"progress":remaining,"next":100+(level-1)*50}


func stats(profile: Dictionary) -> Dictionary:
	if not available(): return {}
	var level: int = int(level_info(profile)["level"])
	var result := {"hp":160+(level-1)*8,"damage":1.0+(level-1)*0.03,"incoming":1.0,"speed":1.0,"cooldown":1.0}
	for slot: String in ["weapon","armor","charm"]:
		var id: String = profile["equipped"][slot]
		var item: Dictionary = catalog[id]
		var rank: int = int(profile["upgrades"].get(id,0))
		result["hp"] += int(item["hp"])+(rank*8 if slot == "armor" else 0)
		result["damage"] += float(item["damage"])+(rank*0.06 if slot == "weapon" else 0.0)
		result["incoming"] *= float(item["incoming"])
		result["speed"] += float(item["speed"])+(rank*0.02 if slot == "charm" else 0.0)
		result["cooldown"] *= float(item["cooldown"])
	return result


func rejected(message: String) -> Dictionary:
	return {"ok":false,"message":message}


func accepted(data: Dictionary,message: String) -> Dictionary:
	return {"ok":true,"data":data,"message":message}


# Versions before the elite armor paid only gold and XP for this completed quest.
func elite_armor_pending(profile: Dictionary,id: String) -> bool:
	if id != ELITE_QUEST_ID or quest_defs.get(id,{}).get("item") != ELITE_ARMOR_ID: return false
	var quest: Dictionary = profile["quests"].get(id,{})
	return int(quest.get("state",0)) == 3 and not profile["owned"].has(ELITE_ARMOR_ID)


func quest_reward_capacity_error(profile: Dictionary,quest: Dictionary) -> String:
	if int(profile["gold"])+int(quest["gold"]) > MAX_COUNTER: return "金币奖励将超出存档上限"
	if int(profile["xp"])+int(quest["xp"]) > MAX_COUNTER: return "经验奖励将超出存档上限"
	return ""


func purchase_blockers(profile: Dictionary, items: Array[String]) -> Dictionary:
	var profile_error := ""
	if not available(): profile_error = configuration_error()
	elif not valid(profile): profile_error = "角色档案无效，未修改"
	elif at_revision_limit(profile): profile_error = REVISION_LIMIT_MESSAGE
	var reasons: Dictionary = {}
	for id: String in items:
		reasons[id] = profile_error if not profile_error.is_empty() else _purchase_blocker(profile,id)
	return reasons


func _purchase_blocker(profile: Dictionary,id: String) -> String:
	if not catalog.has(id) or int(catalog[id]["price"]) <= 0: return "这件装备不能购买"
	if profile["owned"].has(id): return "已拥有这件装备"
	if int(profile["gold"]) < int(catalog[id]["price"]): return "金币不足"
	return ""


# Both callers have validated the profile before checking equipment eligibility.
func equipment_change_issue(profile: Dictionary, operation: String, id: String) -> String:
	if at_revision_limit(profile): return REVISION_LIMIT_MESSAGE
	if not profile["owned"].has(id): return "尚未获得这件装备"
	match operation:
		"equip":
			if profile["equipped"][catalog[id]["slot"]] == id: return "这件装备已经穿戴。"
		"upgrade":
			var rank: int = int(profile["upgrades"].get(id,0))
			if rank >= 3: return "已达到当前强化上限 +3"
			var cost := upgrade_cost(rank)
			if int(profile["gold"]) < cost["gold"] or int(profile["materials"]) < cost["materials"]:
				return "金币或遗迹碎片不足"
		_:
			return "未知操作"
	return ""


func act(profile: Dictionary,command: String,id: String) -> Dictionary:
	if not available(): return rejected(configuration_error())
	var next: Dictionary = normalized_profile(profile)
	if next.is_empty(): return rejected("角色档案无效，未修改")
	if at_revision_limit(next): return rejected(REVISION_LIMIT_MESSAGE)
	match command:
		"accept":
			if not quest_defs.has(id) or int(next["quests"][id]["state"]) != 0: return rejected("这项委托已经接受")
			next["quests"][id]["state"] = 1
			return accepted(next,"已接受委托")
		"claim":
			if elite_armor_pending(next,id):
				next["owned"].append(ELITE_ARMOR_ID)
				return accepted(next,"回响重甲已补领并放入背包")
			if not quest_defs.has(id) or int(next["quests"][id]["state"]) != 2: return rejected("委托尚未完成或奖励已领取")
			var quest: Dictionary = quest_defs[id]
			var capacity_error := quest_reward_capacity_error(next,quest)
			if not capacity_error.is_empty(): return rejected(capacity_error+"，奖励尚未领取。")
			next["quests"][id]["state"] = 3
			next["gold"] = int(next["gold"])+int(quest["gold"])
			next["xp"] = int(next["xp"])+int(quest["xp"])
			if not str(quest["item"]).is_empty() and not next["owned"].has(quest["item"]): next["owned"].append(quest["item"])
			return accepted(next,"委托奖励已领取并保存")
		"equip":
			var issue := equipment_change_issue(next,command,id)
			if not issue.is_empty(): return rejected(issue)
			next["equipped"][catalog[id]["slot"]] = id
			return accepted(next,"装备已更换，下次出征生效")
		"buy":
			var blocker := _purchase_blocker(next,id)
			if not blocker.is_empty(): return rejected(blocker)
			next["gold"] = int(next["gold"])-int(catalog[id]["price"])
			next["owned"].append(id)
			return accepted(next,"装备已购入背包")
		"upgrade":
			var issue := equipment_change_issue(next,command,id)
			if not issue.is_empty(): return rejected(issue)
			var rank: int = int(next["upgrades"].get(id,0))
			var cost := upgrade_cost(rank)
			next["gold"] = int(next["gold"])-int(cost["gold"])
			next["materials"] = int(next["materials"])-int(cost["materials"])
			next["upgrades"][id] = rank+1
			return accepted(next,"强化成功，装备数值已保存")
	return rejected("未知操作")


func upgrade_cost(rank: int) -> Dictionary:
	return {"gold":80+rank*80,"materials":2+rank*2}


func quest_increment(id: String,kills: int,cleared: bool,difficulty: String) -> int:
	match quest_defs[id]["type"]:
		"kills": return kills
		"clear": return 1 if cleared else 0
		"veteran_clear": return 1 if cleared and difficulty == "veteran" else 0
	return 0


func settle(profile: Dictionary,id: String,kills: int,gold: int,cleared: bool,difficulty: String="normal") -> Dictionary:
	var next: Dictionary = normalized_profile(profile)
	if next.is_empty(): return rejected("角色档案无效，未结算")
	if at_revision_limit(next): return rejected(REVISION_LIMIT_MESSAGE)
	if id.is_empty() or id.length() > 120 or profile["settled_runs"].has(id): return rejected("这一局已经结算")
	var terms: Dictionary = dungeons.preview(difficulty)
	if terms.is_empty() or not dungeons.unlocked(difficulty,int(level_info(profile)["level"]),int(profile["clears"])): return rejected("该难度尚未解锁或不可用")
	var limit: int = dungeons.gold_budget(difficulty,kills)
	if limit < 0 or gold < 0 or gold > limit or (cleared and (kills != int(terms["enemies"]) or gold != int(terms["max_gold"]))): return rejected("战斗结算数据不完整")
	next["gold"] = int(next["gold"])+gold
	next["xp"] = int(next["xp"])+dungeons.experience(difficulty,kills,cleared)
	if cleared:
		next["clears"] = int(next["clears"])+1
		next["materials"] = int(next["materials"])+int(terms["materials"])
		if not next["owned"].has("ruin_charm"): next["owned"].append("ruin_charm")
	for key in quest_defs:
		var quest: Dictionary = next["quests"][key]
		if int(quest["state"]) != 1: continue
		var amount: int = quest_increment(key,kills,cleared,difficulty)
		quest["progress"] = mini(int(quest_defs[key]["goal"]),int(quest["progress"])+amount)
		if quest["progress"] >= quest_defs[key]["goal"]: quest["state"] = 2
	next["settled_runs"].append(id)
	if next["settled_runs"].size() > 256: next["settled_runs"].pop_front()
	if not valid(next): return rejected("角色成长超过当前可保存范围，未结算")
	return accepted(next,"本局奖励已存入角色档案")
