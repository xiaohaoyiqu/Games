extends Node2D

const Rules = preload("res://game/combat/combat_rules.gd")
const WALL_MARGIN: float = 3.0

signal impacted(source: Node2D,victim: Node2D,damage: int)
var source: Node2D
var target: Node2D
var targets: Array[Node2D] = []
var travel := Vector2.ZERO:
	set(value):
		if travel == value: return
		travel = value
		queue_redraw()
var move: Dictionary = {}
var obstacles: Array[Rect2] = []
var flight_height: float = 48.0:
	set(value):
		if flight_height == value: return
		flight_height = value
		queue_redraw()
var lifetime: float = 3.0
var _launch_step_seconds: float = 0.0
var expired: bool = false
var blocking_obstacle: int = -1


func aim(source_actor: Node2D,aim_point: Vector2,attack: Dictionary,blockers: Array[Rect2]) -> void:
	source = source_actor
	# Capture the attack clock during the release signal, before recovery or a new action.
	_launch_step_seconds = maxf(0.0,source_actor.attack_time-float(attack["windup"])) if attack.has("windup") else 0.0
	var launch_x := clampf(source_actor.position.x + source_actor.facing*30,Rules.ARROW_AREA.position.x,Rules.ARROW_AREA.end.x)
	var launch := Vector2(launch_x,source_actor.position.y)
	# Clip the horizontal bow offset to the first wall. Starting exactly on its
	# face lets the normal flight step report the same contact to every caller.
	for blocker in blockers:
		var wall := blocker.grow(WALL_MARGIN)
		if Rules.segment_box_times(source_actor.position,launch,wall).x >= 0:
			launch.x = clampf(source_actor.position.x,wall.position.x,wall.end.x)
	position = launch
	var direction := aim_point-position
	if direction == Vector2.ZERO: direction = Vector2(source_actor.facing,0)
	travel = direction.normalized()*350.0
	move = attack
	obstacles = blockers


func advance(delta: float,launched_this_step: bool = false) -> void:
	if expired or not is_finite(delta) or delta < 0:
		return
	if launched_this_step:
		delta = minf(delta,_launch_step_seconds)
	blocking_obstacle = -1
	var previous := position
	if lifetime <= 0 or previous != previous.clamp(Rules.ARROW_AREA.position,Rules.ARROW_AREA.end):
		expired = true
		return
	var end := previous+travel*minf(delta,lifetime)
	lifetime = maxf(0,lifetime-delta)
	var arena := Rules.segment_box_times(previous,end,Rules.ARROW_AREA)
	var stop_time: float = arena.y
	var wall_time: float = INF
	var wall_index: int = -1
	for index in range(obstacles.size()):
		var contact := Rules.segment_box_times(previous,end,obstacles[index].grow(WALL_MARGIN))
		if contact.x >= 0 and contact.x < wall_time:
			wall_time = contact.x
			wall_index = index
	stop_time = minf(stop_time,wall_time)
	var candidates: Array[Node2D] = targets
	if candidates.is_empty() and is_instance_valid(target): candidates = [target]
	var first: Node2D
	var first_time: float = INF
	for candidate in candidates:
		if not is_instance_valid(candidate) or not candidate.is_targetable() or candidate.height > flight_height+8 or candidate.height+76 < flight_height-8: continue
		var contact := Rules.segment_box_times(previous,end,Rect2(candidate.position-Vector2(18,19),Vector2(36,38)))
		# Walls win equal-time contacts; the first target along the swept path wins.
		if contact.x >= 0 and contact.x <= stop_time and contact.x < wall_time and contact.x < first_time:
			first = candidate; first_time = contact.x
	if is_instance_valid(first):
		# take_hit synchronously notifies the victim's listeners.
		expired = true
		position = previous.lerp(end,first_time)
		var damage: int = first.take_hit(int(move["damage"]),signf(travel.x),move)
		if damage > 0:
			impacted.emit(source,first,damage)
			return
	expired = lifetime <= 0 or stop_time < 1.0 or wall_time <= 1.0
	position = previous.lerp(end,stop_time)
	if wall_time <= arena.y and wall_time <= 1.0:
		blocking_obstacle = wall_index


func _draw() -> void:
	var dir := travel.normalized()
	draw_line(Vector2(-8,0),Vector2(6,0),Color(0.02,0.025,0.04,0.4),2)
	var tip := Vector2(0,-flight_height)
	draw_line(tip-dir*22,tip+dir*9,Color("cdbb91"),3)
	draw_line(tip+dir*9,tip+dir*15,Color("e8e9d3"),4)
	draw_line(tip-dir*20,tip-dir*30,Color(0.77,0.6,0.4,0.35),2)
