class_name CombatRules
extends RefCounted

enum HitBlocker { NONE, TARGET, FACING, REACH, DEPTH, HEIGHT }
const DEFAULT_REAR: float=12.0
const ARROW_AREA := Rect2(40,340,1200,310)

## Ground-plane depth and vertical height are independent combat dimensions.
static func can_hit(attacker: Node2D, target: Node2D, move: Dictionary) -> bool:
	return hit_blocker(attacker,target,move) == HitBlocker.NONE

## First failing contact condition at this instant; it is not a whole-attack verdict.
## Shared with actual contact resolution: observers must not recreate the geometry.
static func hit_blocker(attacker: Node2D, target: Node2D, move: Dictionary) -> int:
	if target == attacker or not target.is_targetable():
		return HitBlocker.TARGET
	var offset := target.position - attacker.position
	var forward: float = offset.x * attacker.facing
	if bool(move.get("omnidirectional",false)):
		forward = absf(offset.x)
	if forward < -float(move.get("rear",DEFAULT_REAR)):
		return HitBlocker.FACING
	if forward > float(move["reach"]):
		return HitBlocker.REACH
	if absf(offset.y) > float(move["depth"]):
		return HitBlocker.DEPTH
	var attack_low: float = attacker.height + float(move.get("height_min",0.0))
	var attack_high: float = attacker.height + float(move.get("height_max",84.0))
	if not (target.height <= attack_high and target.height + 76.0 >= attack_low):
		return HitBlocker.HEIGHT
	return HitBlocker.NONE


## Ground-footpoint projection only; height/targetability are separate checks.
## Valid move extents are finite and in [0,10000]. Rect2 edges are inclusive for
## contact even though Rect2.has_point itself excludes its right/bottom edge.
static func ground_contact_bounds(origin: Vector2,facing: float,reach: float,depth: float,rear: float=DEFAULT_REAR,omnidirectional: bool=false) -> Rect2:
	if not origin.is_finite() or facing not in [-1.0,1.0]:return Rect2()
	for extent: float in [reach,depth,rear]:
		if not is_finite(extent) or extent<0.0 or extent>10000.0:return Rect2()
	if omnidirectional:return Rect2(origin+Vector2(-reach,-depth),Vector2(reach*2,depth*2))
	return Rect2(origin+Vector2(-rear if facing>0 else -reach,-depth),Vector2(reach+rear,depth*2))


## Compatibility helper for placement/recovery, not movement along a path.
static func resolve_ground(point: Vector2, bounds: Rect2, obstacles: Array[Rect2]) -> Vector2:
	var world = preload("res://game/combat/ground_motion.gd").new()
	world.configure(bounds,obstacles)
	return world.move(point,Vector2.ZERO)


## Inclusive entry/exit fractions on a finite segment; (-1,-1) means no contact.
static func segment_box_times(start: Vector2,end: Vector2,box: Rect2) -> Vector2:
	var entry: float = 0.0
	var exit: float = 1.0
	var movement := end-start
	for axis in range(2):
		if is_zero_approx(movement[axis]):
			if start[axis] < box.position[axis] or start[axis] > box.end[axis]: return Vector2(-1,-1)
			continue
		var a: float = (box.position[axis]-start[axis])/movement[axis]
		var b: float = (box.end[axis]-start[axis])/movement[axis]
		entry = maxf(entry,minf(a,b))
		exit = minf(exit,maxf(a,b))
		if entry > exit: return Vector2(-1,-1)
	return Vector2(entry,exit)
