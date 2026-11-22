extends RefCounted

const Icons = preload("res://game/ui/inventory_icons.gd")
const Forest = preload("res://game/levels/forest_room_art.gd")
const POTION_SEPARATION := 30.0
const DUST_LIMIT := 12
const LAND_LIFE := 0.20
const HAMMER_LIFE := 0.28

var _gold := Icons.texture_for("gold")
var _potion := Icons.texture_for("healing_potion")
var _dust: Array[Dictionary] = []


func clear() -> void:
	_dust.clear()


func step(delta: float) -> void:
	for index in range(_dust.size() - 1, -1, -1):
		_dust[index]["life"] -= delta
		if _dust[index]["life"] <= 0.0:
			_dust.remove_at(index)


func add_dust(point: Vector2, heavy: bool) -> void:
	if _dust.size() >= DUST_LIMIT:
		_dust.pop_front()
	var duration := HAMMER_LIFE if heavy else LAND_LIFE
	_dust.append({"point": point, "life": duration, "duration": duration, "heavy": heavy})


func draw_dust(target: CanvasItem, shake_offset: Vector2) -> void:
	for event in _dust:
		var point: Vector2 = event["point"] + shake_offset
		var age: float = 1.0 - float(event["life"]) / float(event["duration"])
		var fade := (1.0 - age) * minf(1.0, 0.42 + age * 4.0)
		var heavy: bool = event["heavy"]
		var distance := lerpf(10.0, 34.0 if heavy else 20.0, age)
		var color := Color(0.69, 0.64, 0.47, fade * (0.36 if heavy else 0.28))
		if heavy:
			target.draw_set_transform(point, 0.0, Vector2(1.0, 0.29))
			target.draw_arc(Vector2.ZERO, distance, 0.02, TAU - 0.02, 24, color, 2.5, true)
			target.draw_set_transform(Vector2.ZERO)
		for index in range(-2, 3):
			var offset := Vector2(index * distance * 0.46, -absf(index) * 1.1 - age * 11.0)
			target.draw_circle(point + offset, (4.7 if heavy else 3.6) * (1.0 + age * 0.3), color, true, -1.0, true)


func draw_drops(target: CanvasItem, drops: Array[Dictionary], elapsed: float, shake_offset: Vector2) -> void:
	if drops.is_empty(): return
	var shadow := Forest.contact_shadow_texture()
	if drops.size() == 1:
		var drop := drops[0]
		var gold := int(drop["gold"]) > 0
		var heal := int(drop["heal"]) > 0
		if not (gold and heal):
			if gold or heal:
				var point: Vector2 = drop["point"] + shake_offset
				_draw_item_shadow(target,point,shadow,gold)
				_draw_item(target,point,elapsed,gold)
			return

	# Multiple rewards may share a foot. Keep the IDs for pickup, but draw each silhouette once.
	var coins: Dictionary[Vector2,bool] = {}
	var potions: Dictionary[Vector2,bool] = {}
	for drop in drops:
		if int(drop["gold"]) > 0: coins[drop["point"]] = true
	for drop in drops:
		if int(drop["heal"]) > 0:
			var foot: Vector2 = drop["point"]
			potions[_potion_foot(foot,coins)] = true
	for foot: Vector2 in coins: _draw_item_shadow(target,foot+shake_offset,shadow,true)
	for foot: Vector2 in potions: _draw_item_shadow(target,foot+shake_offset,shadow,false)
	for foot: Vector2 in coins: _draw_item(target,foot+shake_offset,elapsed,true)
	for foot: Vector2 in potions: _draw_item(target,foot+shake_offset,elapsed,false)


func _draw_item_shadow(target: CanvasItem, point: Vector2, shadow: Texture2D, is_gold: bool) -> void:
	if is_gold:
		target.draw_texture_rect(shadow, Rect2(point + Vector2(-9, -1), Vector2(20, 5)), false, Color(1, 1, 1, 0.40))
	else:
		target.draw_texture_rect(shadow, Rect2(point - Vector2(13, 4), Vector2(26, 8)), false)


func _draw_item(target: CanvasItem, point: Vector2, elapsed: float, is_gold: bool) -> void:
	var texture: Texture2D = _gold if is_gold else _potion
	var bob := sin(elapsed * 4.0 + point.x * 0.025) * 1.8
	var bounds := Rect2(point + Vector2(-14.0, -30.0 + bob), Vector2(28, 28))
	if texture != null:
		target.draw_texture_rect(texture, bounds, false)
	else:
		target.draw_circle(bounds.get_center(), 6.0, Color("d4b77b") if is_gold else Color("8cd4a7"))


# Keep the visual inside pickup reach when a coin and potion share a landing spot.
func _potion_foot(foot: Vector2, coins: Dictionary[Vector2,bool]) -> Vector2:
	const CLEARANCE_SQUARED := 20.0 * 20.0
	var best := foot
	var clearance := _coin_clearance(foot,coins)
	if clearance >= CLEARANCE_SQUARED: return foot
	for direction: Vector2 in [Vector2.RIGHT,Vector2.LEFT,Vector2.DOWN,Vector2.UP]:
		var candidate := foot + direction * POTION_SEPARATION
		var distance := _coin_clearance(candidate,coins)
		if distance > clearance:
			best = candidate
			clearance = distance
		# Preserve horizontal presentation whenever one side is clear.
		if direction == Vector2.LEFT and clearance >= CLEARANCE_SQUARED: break
	return best


func _coin_clearance(point: Vector2, coins: Dictionary[Vector2,bool]) -> float:
	var nearest := INF
	for coin: Vector2 in coins:
		nearest = minf(nearest,point.distance_squared_to(coin))
	return nearest
