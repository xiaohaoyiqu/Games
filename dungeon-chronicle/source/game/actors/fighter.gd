class_name DungeonFighter
extends Node2D

signal landed_hit(source: Node2D, victim: Node2D, damage: int)
signal attack_checked(source: Node2D, victim: Node2D, sequence: int, move_name: String, blocker: int)
signal received_hit(victim: Node2D, damage: int)
signal skill_started(fighter: Node2D, sequence: int, cooldown: float)
signal jump_started(fighter: Node2D, sequence: int)
signal ground_landed(fighter: Node2D)
signal dash_started(fighter: Node2D)
signal projectile_fired(source: Node2D, move: Dictionary)
signal melee_contact_started(fighter: Node2D, move_name: String)
signal presentation_changed(state: StringName, move_name: StringName, sequence: int)

const Rules = preload("res://game/combat/combat_rules.gd")
const Values = preload("res://game/combat/combat_values.gd")
const Intent = preload("res://game/input/action_intent.gd")
const MoveRules = preload("res://game/combat/move_rules.gd")
const GroundMotion = preload("res://game/combat/ground_motion.gd")
const ATTACK_BUFFER: float = 0.24
const MOVEMENT_BUFFER: float = 0.14
var is_player: bool = false
var facing: float = 1.0
var height: float = 0.0
var vertical_speed: float = 0.0
var hp: int = 100
var max_hp: int = 100
var ground_velocity := Vector2.ZERO
var walk_input := Vector2.ZERO
var arena := Rect2(65, 350, 1150, 290)
var obstacles: Array[Rect2] = []
var targets: Array[Node2D] = []
var moves: Dictionary = {}
var current_move: Dictionary = {}
var attack_name: String = ""
var attack_time: float = 0.0
var combo_step: int = 0
var combo_window: float = 0.0
var hit_targets: Array[int] = []
var action_intent = Intent.new()
var ground_motion = GroundMotion.new()
var stun_time: float = 0.0
var invulnerable: float = 0.0
var respawn_time: float = 0.0
var dash_time: float = 0.0
var dash_cooldown: float = 0.0
var skill_cooldown: float = 0.0
var hit_flash: float = 0.0
var anim_time: float = 0.0
var passive: bool = true
var spawn_point := Vector2.ZERO
var debug_boxes: bool = false
var total_hits: int = 0
var auto_respawn: bool = true
var enemy_kind: String = "sentinel"
var move_speed_multiplier: float = 1.0
var damage_multiplier: float = 1.0
var incoming_multiplier: float = 1.0
var launch_resistance: float = 1.0
var hurt_invulnerability: float = 0.0
var attack_event_fired: bool = false
var enraged: bool = false
var skill_cooldown_multiplier: float = 1.0
var blade_color: Color = Color("d6ecea")
var procedural_visible: bool = true
var terrain_shadow: Texture2D
var sprite_health_bar_height: float = 0.0
var sprite_attack_effect: bool = false
var action_sequence: int = 0
var _last_presentation: StringName = &""
var _last_move: StringName = &""
var _last_sequence: int = -1


func _ready() -> void:
	_publish_presentation()


func is_targetable() -> bool:
	return hp > 0 and invulnerable <= 0.0


func request_action(action: String) -> void:
	if hp <= 0: return
	var window := MOVEMENT_BUFFER if action in ["jump","dash"] else ATTACK_BUFFER
	if action_intent.offer(action,window): _consume_buffer()
	_publish_presentation()


func _consume_buffer(elapsed: float=0.0) -> void:
	if not attack_name.is_empty() or stun_time > 0 or dash_time > 0 or hp <= 0:
		return
	match action_intent.pending(elapsed):
		&"jump":
			if height > 0: return
			action_intent.clear()
			vertical_speed = 560.0
			height = 0.1
			action_sequence += 1
			jump_started.emit(self,action_sequence)
		&"dash":
			if height > 0 or dash_cooldown > 0: return
			action_intent.clear()
			dash_time = 0.17
			dash_cooldown = 0.65
			invulnerable = maxf(invulnerable,0.17)
			action_sequence += 1
			dash_started.emit(self)
		&"skill":
			if skill_cooldown > 0: return
			if _start_attack("rising_slash"):
				action_intent.clear()
				skill_cooldown = float(current_move["cooldown"])*skill_cooldown_multiplier
				if skill_started.has_connections():skill_started.emit(self,action_sequence,skill_cooldown)
		&"attack":
			var next_step: int = (combo_step if combo_window > 0 else 0)%3+1
			if _start_attack("slash_%d" % next_step):
				combo_step = next_step
				action_intent.clear()


func _start_attack(move_name: String) -> bool:
	if hp <= 0 or not MoveRules.valid(moves.get(move_name)): return false
	attack_name = move_name
	current_move = moves[move_name].duplicate(true)
	attack_time = 0.0
	attack_event_fired = false
	hit_targets.clear()
	ground_velocity = Vector2.ZERO
	action_sequence += 1
	return true


func start_warden_attack(move_name: String) -> bool:
	if enemy_kind != "warden" or not attack_name.is_empty() or skill_cooldown > 0.0 or not _start_attack(move_name):
		return false
	skill_cooldown = float(current_move["cooldown"])
	if enraged:
		current_move["windup"] = float(current_move["windup"])*0.75
		skill_cooldown *= 0.72
	return true


func advance(delta: float) -> void:
	if not is_finite(delta) or delta < 0.0: return
	anim_time += delta
	hit_flash = maxf(0, hit_flash - delta)
	invulnerable = maxf(0, invulnerable - delta)
	dash_cooldown = maxf(0, dash_cooldown - delta)
	skill_cooldown = maxf(0, skill_cooldown - delta)
	combo_window = maxf(0, combo_window - delta)
	stun_time = maxf(0, stun_time - delta)
	if hp <= 0:
		clear_buffered_actions()
		respawn_time -= delta
		if auto_respawn and respawn_time <= 0:
			reset_fighter()
		queue_redraw()
		_publish_presentation()
		return
	if height > 0 or vertical_speed > 0:
		vertical_speed -= 1450.0 * delta
		height = maxf(0, height + vertical_speed * delta)
		if height <= 0:
			vertical_speed = 0.0
			ground_landed.emit(self)
	if not attack_name.is_empty():
		_advance_attack(delta)
		_move_ground(ground_velocity,delta)
	elif stun_time > 0:
		ground_velocity = ground_velocity.move_toward(Vector2.ZERO, 480.0 * delta)
		_move_ground(ground_velocity,delta)
	elif dash_time > 0:
		var dash_step := minf(delta,dash_time)
		_move_ground(Vector2(facing*760.0,0),dash_step)
		dash_time = maxf(0.0,dash_time-dash_step)
		# Preserve the two paths: combining dash + walk into a diagonal chord
		# could cut a corner that neither part is allowed to cross.
		if delta > dash_step: _walk_ground(delta-dash_step)
		elif dash_time <= 0: ground_velocity = Vector2.ZERO
	else:
		_walk_ground(delta)
	_consume_buffer(delta)
	action_intent.advance(delta)
	_publish_presentation()
	queue_redraw()


func _walk_ground(delta: float) -> void:
	var direction := walk_input.limit_length() if walk_input.is_finite() else Vector2.ZERO
	var multiplier: float = move_speed_multiplier if is_finite(move_speed_multiplier) and move_speed_multiplier >= 0 else 0.0
	if absf(direction.x) > 0.01: facing = signf(direction.x)
	_move_ground(direction*Values.WALK_SPEED*multiplier,delta)


func _move_ground(velocity: Vector2,delta: float) -> void:
	ground_motion.configure(arena,obstacles)
	position = ground_motion.move(position,velocity*delta)
	ground_velocity = ground_motion.clip_velocity(velocity)


func _advance_attack(delta: float) -> void:
	var previous := attack_time
	attack_time += delta
	var windup: float = current_move["windup"]
	var active_end: float = windup + float(current_move["active"])
	# Test interval overlap so a slow frame cannot skip a whole active phase.
	if attack_time >= windup and previous < active_end:
		var projectile := bool(current_move.get("projectile",false))
		if not attack_event_fired:
			attack_event_fired = true
			if projectile:
				var projectile_move := current_move.duplicate(true)
				projectile_move["damage"] = Values.scaled_damage(float(current_move["damage"]),damage_multiplier)
				projectile_fired.emit(self,projectile_move)
			elif melee_contact_started.has_connections():
				melee_contact_started.emit(self, attack_name)
		for target in targets:
			if projectile:
				break
			if not is_instance_valid(target) or hit_targets.has(target.get_instance_id()):
				continue
			var blocker: int = Rules.hit_blocker(self,target,current_move)
			# Emit only for subscribed observers and inside the actual active-interval check.
			# Slow steps still report this check even if no rendered active frame occurs.
			if attack_checked.has_connections():
				attack_checked.emit(self,target,action_sequence,attack_name,blocker)
			if blocker == Rules.HitBlocker.NONE:
				hit_targets.append(target.get_instance_id())
				var damage: int = Values.scaled_damage(float(current_move["damage"]),damage_multiplier)
				var dealt: int = target.take_hit(damage, facing, current_move)
				if dealt > 0:
					total_hits += 1
					landed_hit.emit(self, target, dealt)
	if attack_time >= active_end + float(current_move["recovery"]):
		var finished_at := maxf(0.0,active_end+float(current_move["recovery"])-previous)
		attack_name = ""
		current_move = {}
		combo_window = 0.45
		# A recovery ending within a slow step may still fall inside the input window.
		_consume_buffer(finished_at)
		# The combo window starts at recovery, even when this update ends later.
		combo_window = maxf(0.0,combo_window-maxf(0.0,delta-finished_at))


func take_hit(damage: int, direction: float, move: Dictionary) -> int:
	if damage <= 0 or not is_finite(direction) or not is_targetable():
		return 0
	var dealt: int = mini(hp,Values.scaled_damage(float(damage),incoming_multiplier))
	hp -= dealt
	hit_flash = 0.12
	invulnerable = maxf(invulnerable,hurt_invulnerability)
	var armored: bool = enemy_kind == "warden" and not attack_name.is_empty() and hp > 0
	stun_time = 0.0 if armored else 0.32
	ground_velocity = Vector2.ZERO if armored else Vector2(direction * float(move["knockback"]) * maxf(0.25,launch_resistance), 0)
	if float(move.get("launch", 0)) * launch_resistance > 0 and not armored:
		vertical_speed = float(move["launch"]) * launch_resistance
		height = maxf(height, 0.1)
	if not armored:
		attack_name = ""
		current_move = {}
	dash_time = 0.0
	clear_buffered_actions()
	combo_window = 0.0
	if hp <= 0:
		respawn_time = 1.7
	received_hit.emit(self, dealt)
	_publish_presentation()
	return dealt


func reset_fighter() -> void:
	hp = max_hp
	invulnerable = 0
	respawn_time = 0
	dash_cooldown = 0
	total_hits = 0
	_reset_combat_pose()


func enter_room(point: Vector2) -> void:
	spawn_point = point
	facing = 1
	invulnerable = 0.7
	_reset_combat_pose()


func _reset_combat_pose() -> void:
	position = spawn_point
	height = 0
	vertical_speed = 0
	ground_velocity = Vector2.ZERO
	walk_input = Vector2.ZERO
	stun_time = 0
	hit_flash = 0
	dash_time = 0
	skill_cooldown = 0
	attack_name = ""
	attack_time = 0
	current_move = {}
	clear_buffered_actions()
	combo_step = 0
	combo_window = 0
	hit_targets.clear()
	attack_event_fired = false
	_publish_presentation()
	queue_redraw()


func clear_buffered_actions() -> void:
	action_intent.clear()


func presentation_state() -> StringName:
	if hp <= 0: return &"dead"
	if stun_time > 0: return &"hurt"
	if not attack_name.is_empty():
		var windup: float = current_move.get("windup",0.0)
		if attack_time < windup: return &"attack_windup"
		if attack_time < windup+float(current_move.get("active",0.0)): return &"attack_active"
		return &"attack_recovery"
	if dash_time > 0: return &"dash"
	if height > 0: return &"rise" if vertical_speed > 0 else &"fall"
	return &"walk" if ground_velocity.length_squared() > 625.0 else &"idle"


func presentation_snapshot() -> Dictionary:
	var state := presentation_state()
	var phase: String = String(state).trim_prefix("attack_") if String(state).begins_with("attack_") else ""
	var offset: float = 0.0
	if phase == "active": offset = float(current_move.get("windup",0.0))
	if phase == "recovery": offset = float(current_move.get("windup",0.0))+float(current_move.get("active",0.0))
	return {"state":state,"move":StringName(attack_name),"phase":phase,"sequence":action_sequence,
		"facing":facing,"height":height,"ground_position":position,"clock":anim_time,
		"attack_time":attack_time if not attack_name.is_empty() else 0.0,"phase_time":maxf(0.0,attack_time-offset) if not phase.is_empty() else 0.0,
		"phase_duration":float(current_move.get(phase,0.0)),
		"attack_duration":float(current_move.get("windup",0.0))+float(current_move.get("active",0.0))+float(current_move.get("recovery",0.0))}


func _publish_presentation() -> void:
	var state := presentation_state()
	var move_name := StringName(attack_name)
	if state == _last_presentation and move_name == _last_move and action_sequence == _last_sequence: return
	_last_presentation = state
	_last_move = move_name
	_last_sequence = action_sequence
	presentation_changed.emit(state,move_name,action_sequence)


func _draw() -> void:
	if hp <= 0: return
	var shadow_scale := clampf(1.0 - height / 320.0, 0.45, 1.0)
	if terrain_shadow != null:
		var base_size := Vector2(72, 24) if enemy_kind == "bulwark" else Vector2(64, 22)
		var size := base_size * shadow_scale
		draw_texture_rect(terrain_shadow, Rect2(Vector2(1.0, 2.0) - size * 0.5, size), false)
	else:
		draw_set_transform(Vector2.ZERO, 0, Vector2(1.0, 0.30))
		draw_circle(Vector2.ZERO, 28.0 * shadow_scale, Color(0.015, 0.018, 0.027, 0.58))
		draw_set_transform(Vector2.ZERO)
	var health_bar_height := sprite_health_bar_height
	var origin := Vector2(0, -height + sin(anim_time * 3.0) * 1.2)
	if procedural_visible:
		var moving := ground_velocity.length() > 25 and stun_time <= 0 and attack_name.is_empty()
		var stride: float = sin(anim_time * 14.0) * (9.0 if moving else 0.0)
		var size_factor := 1.42 if enemy_kind == "warden" else (1.12 if enemy_kind == "bulwark" else 1.0)
		if is_player:
			size_factor = 1.0
		draw_set_transform(origin, 0, Vector2(facing * size_factor, size_factor))
		if is_player:
			_draw_knight(stride)
		else:
			_draw_sentinel(stride)
			_draw_enemy_details()
		draw_set_transform(Vector2.ZERO)
		health_bar_height = 115.0 * size_factor
	if not attack_name.is_empty() and (procedural_visible or sprite_attack_effect):
		draw_set_transform(origin, 0, Vector2(facing, 1))
		_draw_attack()
		draw_set_transform(Vector2.ZERO)
	if not is_player and health_bar_height > 0.0:
		draw_rect(Rect2(-25, -height - health_bar_height, 50, 4), Color("171c27"))
		draw_rect(Rect2(-25, -height - health_bar_height, 50.0 * hp / max_hp, 4), Color("dd9b67"))
	if debug_boxes:
		var footprint: float = GroundMotion.FOOTPRINT
		draw_rect(Rect2(-footprint,-footprint,footprint*2,footprint*2), Color(0.2, 0.9, 0.65, 0.65), false, 2)
		if not current_move.is_empty() and not bool(current_move.get("projectile",false)):
			var bounds: Rect2=Rules.ground_contact_bounds(Vector2.ZERO,facing,float(current_move["reach"]),float(current_move["depth"]),float(current_move.get("rear",Rules.DEFAULT_REAR)),bool(current_move.get("omnidirectional",false)))
			draw_rect(bounds,Color(1,0.7,0.2,0.6),false,2)


func _draw_knight(stride: float) -> void:
	var armor := Color("a5b6bf") if hit_flash <= 0 else Color.WHITE
	var dark := Color("253146")
	# Cape, separate boots, torso plates and scarf create a readable silhouette.
	draw_colored_polygon(PackedVector2Array([Vector2(-13,-79),Vector2(-37,-24),Vector2(-22,-17),Vector2(1,-44)]), Color("923d49"))
	draw_rect(Rect2(-16 + stride * 0.35, -31, 13, 27), dark)
	draw_rect(Rect2(4 - stride * 0.35, -32, 12, 28), dark)
	draw_rect(Rect2(-19 + stride * 0.35, -10, 20, 9), Color("141d2b"))
	draw_rect(Rect2(2 - stride * 0.35, -10, 23, 9), Color("141d2b"))
	draw_colored_polygon(PackedVector2Array([Vector2(-17,-75),Vector2(13,-77),Vector2(22,-50),Vector2(12,-29),Vector2(-15,-30),Vector2(-23,-52)]), dark)
	draw_colored_polygon(PackedVector2Array([Vector2(-13,-72),Vector2(13,-73),Vector2(16,-51),Vector2(0,-41),Vector2(-15,-52)]), armor)
	draw_line(Vector2(-9,-64),Vector2(12,-64),Color("e0e1cf"),3)
	draw_rect(Rect2(-17,-38,34,7),Color("b89557"))
	draw_rect(Rect2(-4,-39,9,9),Color("eed49a"))
	draw_circle(Vector2(-19,-68),12,dark)
	draw_circle(Vector2(-19,-70),9,armor)
	draw_circle(Vector2(18,-68),12,dark)
	draw_circle(Vector2(18,-70),9,armor)
	draw_line(Vector2(-21,-59),Vector2(-20,-40),dark,10)
	draw_line(Vector2(20,-60),Vector2(29,-45),dark,10)
	draw_circle(Vector2(29,-44),6,Color("c5a08b"))
	draw_rect(Rect2(-10,-97,23,22),Color("c5a08b"))
	draw_colored_polygon(PackedVector2Array([Vector2(-14,-101),Vector2(0,-109),Vector2(16,-98),Vector2(9,-91),Vector2(-6,-94),Vector2(-11,-84)]),Color("d8dcdb"))
	draw_rect(Rect2(6,-91,8,3),Color("273244"))
	draw_rect(Rect2(-14,-79,31,9),Color("b65355"))
	if attack_name.is_empty():
		draw_line(Vector2(28,-45),Vector2(65,-80),Color("162331"),8)
		draw_line(Vector2(31,-48),Vector2(65,-80),blade_color,4)
		draw_line(Vector2(22,-53),Vector2(36,-39),Color("d6b468"),5)
	if dash_time > 0:
		draw_line(Vector2(-75,-45),Vector2(-32,-45),Color(0.5,0.8,0.9,0.5),3)
		draw_line(Vector2(-95,-65),Vector2(-39,-65),Color(0.5,0.8,0.9,0.3),2)


func _draw_sentinel(stride: float) -> void:
	var stone := Color("8e8078") if hit_flash <= 0 else Color.WHITE
	var edge := Color("393943")
	draw_rect(Rect2(-19+stride*0.3,-28,14,27),edge)
	draw_rect(Rect2(5-stride*0.3,-28,14,27),edge)
	draw_rect(Rect2(-24+stride*0.3,-9,23,9),Color("292d39"))
	draw_rect(Rect2(3-stride*0.3,-9,23,9),Color("292d39"))
	draw_colored_polygon(PackedVector2Array([Vector2(-24,-73),Vector2(19,-76),Vector2(29,-52),Vector2(17,-27),Vector2(-21,-27),Vector2(-30,-51)]),edge)
	draw_colored_polygon(PackedVector2Array([Vector2(-18,-70),Vector2(14,-71),Vector2(22,-52),Vector2(12,-34),Vector2(-15,-34),Vector2(-24,-52)]),stone)
	draw_line(Vector2(-11,-63),Vector2(11,-44),Color("524b53"),3)
	draw_line(Vector2(11,-64),Vector2(-6,-45),Color("524b53"),2)
	draw_circle(Vector2(0,-91),19,edge)
	draw_colored_polygon(PackedVector2Array([Vector2(-15,-104),Vector2(11,-105),Vector2(17,-81),Vector2(2,-74),Vector2(-15,-80)]),stone)
	draw_rect(Rect2(-9,-92,22,6),Color("292d39"))
	draw_rect(Rect2(2,-91,10,3),Color("e7b97b"))
	draw_line(Vector2(-22,-68),Vector2(-33,-39),edge,12)
	draw_line(Vector2(22,-67),Vector2(38,-44),edge,12)
	draw_rect(Rect2(32,-50,13,15),stone)
	draw_line(Vector2(39,-48),Vector2(54,-90),Color("806c56"),6)
	draw_rect(Rect2(40,-99,31,16),Color("6e737a"))
	if attack_name == "dummy_strike" and attack_time < float(current_move.get("windup",0)):
		draw_circle(Vector2(0,-127),5,Color("f1b371"))


func _draw_attack() -> void:
	var windup: float = current_move["windup"]
	if attack_time < windup or attack_time > windup + float(current_move["active"]) + 0.07:
		return
	if bool(current_move.get("projectile",false)):
		return
	var reach: float = current_move["reach"]
	var rising := attack_name == "rising_slash"
	if sprite_attack_effect:
		if is_player:
			var arc_center := Vector2(reach * 0.26, -65.0)
			var radius := reach * (0.78 if rising else 0.63)
			var start_angle := -1.26 if rising else -0.96
			var end_angle := 0.62 if rising else 0.82
			draw_arc(arc_center, radius, start_angle, end_angle, 24, Color(0.60, 0.86, 0.88, 0.28), 6.0, true)
			draw_arc(arc_center, radius, start_angle + 0.17, end_angle - 0.17, 20, Color(0.82, 0.95, 0.91, 0.72), 2.0, true)
		return
	var center := Vector2(14,-54)
	var points := PackedVector2Array([center + Vector2(14,37),center + Vector2(reach * 0.74,27),center + Vector2(reach, -19),center + Vector2(reach * 0.76,-70),center + Vector2(25,-83)])
	if rising:
		points = PackedVector2Array([Vector2(18,-17),Vector2(reach,-54),Vector2(reach-8,-140),Vector2(63,-178),Vector2(83,-90)])
	draw_colored_polygon(points,Color(0.48,0.83,0.91,0.35))
	for i in range(points.size()-1):
		draw_line(points[i],points[i+1],Color("d5f6ef"),3,true)


func _draw_enemy_details() -> void:
	if enemy_kind == "archer":
		draw_colored_polygon(PackedVector2Array([Vector2(-19,-104),Vector2(5,-116),Vector2(23,-98),Vector2(16,-81),Vector2(-16,-81)]),Color("476e64"))
		draw_arc(Vector2(35,-64),27,-1.3,1.3,12,Color("c49e6c"),4)
		draw_line(Vector2(42,-90),Vector2(42,-39),Color("dad3ac"),1)
	elif enemy_kind == "bulwark":
		draw_rect(Rect2(-32,-80,25,53),Color("414b66"))
		draw_rect(Rect2(-29,-77,19,46),Color("b5a47b"),false,3)
		draw_line(Vector2(0,-110),Vector2(0,-121),Color("b7a48a"),5)
	elif enemy_kind == "warden":
		draw_colored_polygon(PackedVector2Array([Vector2(-25,-68),Vector2(-38,-10),Vector2(-6,-24),Vector2(15,-65)]),Color("754652"))
		for x in [-16,0,16]:
			draw_line(Vector2(x,-105),Vector2(x-3,-122),Color("d3b57b"),6)
		draw_circle(Vector2(0,-54),7,Color("eb956d") if enraged else Color("85d2d1"))
