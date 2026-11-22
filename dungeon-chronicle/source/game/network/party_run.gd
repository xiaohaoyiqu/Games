extends RefCounted

## Cached run contract. Character bounds do not prove ownership of an account.
const Wire = preload("res://game/network/party_protocol.gd")
const Frame = preload("res://game/network/battle_frame.gd")
const Catalog = preload("res://game/levels/dungeon_catalog.gd")
const Skills = preload("res://game/combat/skill_catalog.gd")
const HERO_MOVES: Array[String] = ["","slash_1","slash_2","slash_3","rising_slash"]
var manifest: Dictionary = {}
var plan: Dictionary = {}
var stats: Dictionary = {}
var _catalog: RefCounted
var _room_checks: Array[Dictionary] = []


func _init(characters: RefCounted,catalog: RefCounted,value: Dictionary) -> void:
	_catalog = catalog
	if not Wire.new(characters).valid(value) or value.get("kind") != "prepare": return
	var compiled: Dictionary = catalog.plan(value["mode"])
	if compiled.is_empty(): return
	manifest = value.duplicate(true)
	plan = compiled
	# Keep scaling fixed to the original party size after departures.
	var percent: int = _enemy_hp_percent(manifest["members"].size())
	for enemy in plan["roster"].values(): enemy["hp"] = catalog.scaled(int(enemy["hp"]),percent)
	for member in manifest["members"]: stats[int(member["peer"])] = characters.stats(member["character"])
	_build_room_checks()


static func _enemy_hp_percent(players: int) -> int:
	return 100+65*(players-1)


static func layout_issue(plan: Dictionary,players: int) -> String:
	if plan.is_empty(): return "该难度的副本配置暂不可用。"
	var rooms: Array = plan["dungeon"]["rooms"]
	if rooms.size() != Frame.ROOM_COUNT:
		return "组队需要 %d 间房，当前副本有 %d 间。请恢复兼容的副本配置。" % [Frame.ROOM_COUNT,rooms.size()]
	if int(plan["dungeon"]["room_heal"]) > Frame.MAX_DROP_HEAL:
		return "房间药剂恢复量超过组队上限 %d。请调整副本配置。" % Frame.MAX_DROP_HEAL
	var preview: Dictionary = plan["preview"]
	if int(preview["enemies"]) > Frame.MAX_KILLS:
		return "当前副本有 %d 个敌人，组队全程最多支持 %d 个。请调整副本配置。" % [preview["enemies"],Frame.MAX_KILLS]
	if int(preview["max_gold"]) > Frame.MAX_GOLD:
		return "该难度全程金币为 %d，超过组队上限 %d。请切换难度或调整配置。" % [preview["max_gold"],Frame.MAX_GOLD]
	var roster: Dictionary = plan["roster"]
	var enemy_capacity: int = Frame.MAX_ACTORS-players
	var hp_percent: int = _enemy_hp_percent(players)
	for index in range(rooms.size()):
		var enemies: Array = rooms[index]["enemies"]
		if enemies.size() > enemy_capacity:
			return "第 %d 间房有 %d 个敌人，%d 人出征最多支持 %d 个。请调整队伍人数或副本配置。" % [index+1,enemies.size(),players,enemy_capacity]
		for entry: Dictionary in enemies:
			var kind: String = entry["kind"]
			var profile: Dictionary = roster[kind]
			if Frame.KINDS.find(kind) <= 0 or profile["move"] not in Frame.MOVES:
				return "第 %d 间房的敌人种类或招式不受组队支持。请调整副本配置。" % [index+1]
			if Catalog.scaled(int(profile["hp"]),hp_percent) > Frame.MAX_ACTOR_HP:
				return "第 %d 间房的敌人超出组队生命值上限。请调整难度、队伍人数或副本配置。" % [index+1]
	return ""


func _build_room_checks() -> void:
	var roster: Dictionary = plan["roster"]
	var prior_kills: int = 0
	var prior_gold: int = 0
	for room: Dictionary in plan["dungeon"]["rooms"]:
		var enemies: Array[Dictionary] = []
		_room_checks.append({"prior_kills":prior_kills,"prior_gold":prior_gold,"enemies":enemies})
		# Earlier rooms are fully cleared. Only the current room's deaths vary per frame.
		for entry: Dictionary in room["enemies"]:
			var kind: String = entry["kind"]
			var profile: Dictionary = roster[kind]
			var moves: Array[String] = [""]
			if kind == "warden": moves.append_array(Skills.WARDEN_ATTACKS)
			else: moves.append(profile["move"])
			var gold: int = int(profile["gold"])
			enemies.append({"kind":Frame.KINDS.find(kind),"hp":int(profile["hp"]),"moves":moves,"gold":gold})
			prior_kills += 1
			prior_gold += gold


func available() -> bool: return not plan.is_empty()


func valid_frame(value: Dictionary,self_id: int=0) -> bool:
	if not available() or not Frame.valid(value): return false
	var room_index: int = int(value["room"])
	if room_index >= _room_checks.size(): return false
	var budget: int = _catalog.gold_budget(manifest["mode"],int(value["kills"]))
	if budget < 0 or int(value["gold"]) > budget: return false
	var room: Dictionary = _room_checks[room_index]
	var entries: Array = room["enemies"]
	var hero_ids: Dictionary = {}
	var enemies: Dictionary = {}
	var living_heroes: int = 0
	var living_enemies: int = 0
	var expected_kills: int = room["prior_kills"]
	var expected_gold: int = room["prior_gold"]
	for actor in value["actors"]:
		var id: int = int(actor[0])
		if id > 0:
			if not stats.has(id) or int(actor[6]) != int(stats[id]["hp"]): return false
			if actor[8] not in HERO_MOVES: return false
			hero_ids[id] = true
			if actor[5] > 0: living_heroes += 1
		else:
			var index: int = -id-1-room_index*8
			if index < 0 or index >= entries.size(): return false
			var enemy: Dictionary = entries[index]
			if int(actor[1]) != enemy["kind"] or int(actor[6]) != enemy["hp"]: return false
			if actor[8] not in enemy["moves"]: return false
			enemies[id] = true
			if actor[5] > 0: living_enemies += 1
			else:
				expected_kills += 1
				expected_gold += int(enemy["gold"])
	if not hero_ids.has(1) or (self_id > 0 and not hero_ids.has(self_id)) or enemies.size() != entries.size(): return false
	if int(value["kills"]) != expected_kills or int(value["gold"]) > expected_gold: return false
	var phase: int = int(value["phase"])
	if phase in [1,2] and (living_enemies > 0 or living_heroes == 0): return false
	if phase == 1 and room_index == Frame.ROOM_COUNT-1: return false
	if phase == 3 and not value["retreat"] and living_heroes > 0: return false
	if phase == 2:
		var preview: Dictionary = plan["preview"]
		if room_index != Frame.ROOM_COUNT-1 or value["kills"] != preview["enemies"] or value["gold"] != preview["max_gold"]: return false
	return true
