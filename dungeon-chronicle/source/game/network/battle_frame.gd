extends RefCounted

## Compact, bounded visual state. Never constructs nodes or decodes objects.
## Actor: id,kind,x,y,height,hp,max_hp,facing,move,time,windup,active,recovery,
##        stun,dash,invulnerability,cooldown,sequence,vx,vy,vz,enraged,flash,clock.
const KINDS: Array[String] = ["hero","raider","archer","bulwark","warden"]
const MOVES: Array[String] = ["","slash_1","slash_2","slash_3","rising_slash","raider_cut","archer_shot","bulwark_crush","warden_slam","warden_sweep"]
const Review = preload("res://game/combat/battle_review.gd")
const Combat = preload("res://game/combat/combat_rules.gd")
const MoveRules = preload("res://game/combat/move_rules.gd")
const Equipment = preload("res://game/equipment/progression_catalog.gd")
# One item in each of the weapon, armor and charm slots can scale the skill cooldown.
const ITEM_FACTOR: float = Equipment.MAX_ITEM_COOLDOWN_MULTIPLIER
const MAX_HERO_COOLDOWN: float = MoveRules.MAX_FIELD_VALUE * ITEM_FACTOR * ITEM_FACTOR * ITEM_FACTOR
const MAX_SEQUENCE: int = 2147483647
const ROOM_COUNT: int = 4
const MAX_ACTORS: int = 8
const MAX_KILLS: int = 11
const MAX_GOLD: int = 10000
const MAX_DROP_HEAL: int = 100
const MAX_ACTOR_HP: int = 10000


static func number(value: Variant,minimum: float,maximum: float) -> bool:
	return typeof(value) in [TYPE_INT,TYPE_FLOAT] and is_finite(float(value)) and value >= minimum and value <= maximum


static func whole(value: Variant,minimum: int,maximum: int=MAX_SEQUENCE) -> bool:
	return number(value,minimum,maximum) and float(value) == floor(float(value))


static func fields(value: Dictionary,keys: Array) -> bool:
	if value.size() != keys.size(): return false
	for key in keys:
		if not value.has(key): return false
	return true


static func valid(frame: Variant) -> bool:
	if not frame is Dictionary or not fields(frame,["tick","room","phase","kills","gold","elapsed","retreat","actors","arrows","loot"]): return false
	if not whole(frame["tick"],0) or not whole(frame["room"],0,ROOM_COUNT-1) or not whole(frame["phase"],0,3): return false
	if not whole(frame["kills"],0,MAX_KILLS) or not whole(frame["gold"],0,MAX_GOLD) or not number(frame["elapsed"],0,Review.MAX_ELAPSED_SECONDS): return false
	if not frame["retreat"] is bool or (frame["retreat"] and frame["phase"] != 3): return false
	if not frame["actors"] is Array or frame["actors"].is_empty() or frame["actors"].size() > MAX_ACTORS: return false
	var identifiers: Dictionary = {}
	for actor in frame["actors"]:
		if not actor is Array or actor.size() != 24 or not whole(actor[0],-64) or actor[0] == 0 or identifiers.has(int(actor[0])): return false
		identifiers[int(actor[0])] = true
		if not whole(actor[1],0,4) or (actor[1] == 0) != (actor[0] > 0): return false
		if not number(actor[2],40,1240) or not number(actor[3],340,650) or not number(actor[4],0,2000): return false
		if not whole(actor[6],1,MAX_ACTOR_HP) or not whole(actor[5],0,int(actor[6])) or not whole(actor[7],-1,1) or actor[7] == 0: return false
		if not actor[8] is String or actor[8] not in MOVES: return false
		for index in [9,10,11,12]:
			if not number(actor[index],0,MoveRules.MAX_DURATION): return false
		# Compare the phase sum at the millisecond precision of the attack clock.
		if snappedf(float(actor[10])+float(actor[11])+float(actor[12]),0.001) > MoveRules.MAX_DURATION: return false
		if not number(actor[16],0,MAX_HERO_COOLDOWN if actor[0] > 0 else MoveRules.MAX_FIELD_VALUE): return false
		for index in [13,14,15,22]:
			if not number(actor[index],0,10): return false
		if not whole(actor[17],0) or not actor[21] is bool or not number(actor[23],0,Review.MAX_ELAPSED_SECONDS): return false
		for index in [18,19,20]:
			if not number(actor[index],-5000,5000): return false
	if not frame["arrows"] is Array or frame["arrows"].size() > 24: return false
	identifiers.clear()
	for arrow in frame["arrows"]:
		if not arrow is Array or arrow.size() != 6 or not whole(arrow[0],1) or identifiers.has(int(arrow[0])): return false
		identifiers[int(arrow[0])] = true
		if not number(arrow[1],Combat.ARROW_AREA.position.x,Combat.ARROW_AREA.end.x): return false
		if not number(arrow[2],Combat.ARROW_AREA.position.y,Combat.ARROW_AREA.end.y) or not number(arrow[5],0,2000): return false
		if not number(arrow[3],-1000,1000) or not number(arrow[4],-1000,1000): return false
	if not frame["loot"] is Array or frame["loot"].size() > 16: return false
	identifiers.clear()
	for drop in frame["loot"]:
		if not drop is Array or drop.size() != 5 or not whole(drop[0],1) or identifiers.has(int(drop[0])): return false
		identifiers[int(drop[0])] = true
		if not number(drop[1],40,1240) or not number(drop[2],340,650) or not whole(drop[3],0,10000) or not whole(drop[4],0,MAX_DROP_HEAL): return false
	return true


static func normalize(frame: Dictionary) -> void:
	for key in ["tick","room","phase","kills","gold"]: frame[key] = int(frame[key])
	for actor in frame["actors"]:
		for index in [0,1,5,6,7,17]: actor[index] = int(actor[index])
	for arrow in frame["arrows"]: arrow[0] = int(arrow[0])
	for drop in frame["loot"]:
		for index in [0,3,4]: drop[index] = int(drop[index])


static func pack(id: int,kind: int,fighter: Node2D) -> Array:
	return [id,kind,snappedf(fighter.position.x,0.01),snappedf(fighter.position.y,0.01),snappedf(fighter.height,0.01),fighter.hp,fighter.max_hp,int(fighter.facing),fighter.attack_name,
		snappedf(fighter.attack_time,0.001) if not fighter.attack_name.is_empty() else 0.0,
		fighter.current_move.get("windup",0.0),fighter.current_move.get("active",0.0),fighter.current_move.get("recovery",0.0),
		snappedf(fighter.stun_time,0.001),snappedf(fighter.dash_time,0.001),snappedf(fighter.invulnerable,0.001),snappedf(fighter.skill_cooldown,0.001),fighter.action_sequence,
		snappedf(fighter.ground_velocity.x,0.01),snappedf(fighter.ground_velocity.y,0.01),snappedf(fighter.vertical_speed,0.01),fighter.enraged,snappedf(fighter.hit_flash,0.001),snappedf(fighter.anim_time,0.001)]


# A continuing replica uses the same move catalog for the lifetime of its run.
static func apply(actor: Array,fighter: Node2D,moves: Dictionary,apply_pose: bool = true,continuing_replica: bool = false) -> void:
	# Caller validates the complete frame before any mutation. No advance/take_hit.
	if apply_pose:
		fighter.position = Vector2(actor[2],actor[3])
		fighter.height = actor[4]
	fighter.hp = actor[5]; fighter.max_hp = actor[6]; fighter.facing = actor[7]
	_sync_move(actor,fighter,moves,continuing_replica)
	fighter.attack_name = actor[8]; fighter.attack_time = actor[9]
	fighter.stun_time = actor[13]; fighter.dash_time = actor[14]; fighter.invulnerable = actor[15]
	fighter.skill_cooldown = actor[16]; fighter.action_sequence = actor[17]
	fighter.ground_velocity = Vector2(actor[18],actor[19]); fighter.vertical_speed = actor[20]
	fighter.enraged = actor[21]; fighter.hit_flash = actor[22]; fighter.anim_time = actor[23]
	fighter._publish_presentation(); fighter.queue_redraw()


static func _sync_move(actor: Array,fighter: Node2D,moves: Dictionary,continuing_replica: bool) -> void:
	var move_name: String = actor[8]
	var previous: Dictionary = fighter.current_move
	if move_name.is_empty():
		if not continuing_replica or not previous.is_empty(): fighter.current_move = {}
		return
	if (continuing_replica and fighter.attack_name == move_name and fighter.action_sequence == actor[17]
			and previous.get("windup") == actor[10] and previous.get("active") == actor[11]
			and previous.get("recovery") == actor[12]):
		return
	var move: Dictionary = moves[move_name].duplicate(true)
	move["windup"] = actor[10]
	move["active"] = actor[11]
	move["recovery"] = actor[12]
	fighter.current_move = move
