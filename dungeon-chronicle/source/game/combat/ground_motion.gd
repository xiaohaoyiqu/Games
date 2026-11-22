extends RefCounted

## Swept point against static AABBs expanded by the character's ground footprint.
## Height is deliberately absent: the current pillars block grounded/airborne feet.
const FOOTPRINT: float = 15.0
const SKIN: float = 0.01
const EPSILON: float = 0.000001
const MAX_SLIDES: int = 4
const MAX_OBSTACLES: int = 256
const MAX_COORDINATE: float = 100000.0
const MAX_DISPLACEMENT: float = 1000000.0
var last_normals: Array[Vector2] = []
var last_recovered: bool = false
var last_recovery_failed: bool = false
var last_limited: bool = false
var last_iterations: int = 0
var last_error: String = ""
var _bounds := Rect2()
var _source: Array[Rect2] = []
var _boxes: Array[Rect2] = []
var _configured: bool = false
var _valid: bool = false
var _configuration_error: String = ""
var _skin: float = SKIN
var _recovery_query := Vector2(INF,INF)
var _recovery_result := Vector2.ZERO
var _recovery_failed: bool = false


static func _valid_rect(value: Rect2) -> bool:
	return value.position.is_finite() and value.end.is_finite() and value.size.x > 0 and value.size.y > 0 and value.position.abs().x <= MAX_COORDINATE and value.position.abs().y <= MAX_COORDINATE and value.end.abs().x <= MAX_COORDINATE and value.end.abs().y <= MAX_COORDINATE


func configure(bounds: Rect2,obstacles: Array[Rect2]) -> bool:
	# Keep an independent snapshot, so edits to an existing array invalidate it too.
	if _configured and _valid and bounds == _bounds and obstacles == _source: return true
	_configured = true
	_valid = false
	_bounds = bounds
	_source.clear()
	if obstacles.size() <= MAX_OBSTACLES: _source = obstacles.duplicate()
	_boxes.clear()
	_recovery_query = Vector2(INF,INF)
	_configuration_error = "地面碰撞范围或障碍参数无效"
	if not _valid_rect(bounds) or obstacles.size() > MAX_OBSTACLES: return false
	var magnitude := maxf(maxf(absf(bounds.position.x),absf(bounds.position.y)),maxf(absf(bounds.end.x),absf(bounds.end.y)))
	_skin = maxf(SKIN,magnitude*0.000001)
	for obstacle in obstacles:
		if not _valid_rect(obstacle): return false
		var expanded := obstacle.grow(FOOTPRINT)
		if not _valid_rect(expanded): return false
		_boxes.append(expanded)
	_valid = true
	_configuration_error = ""
	return true


func is_free(point: Vector2) -> bool:
	if not _valid or not point.is_finite(): return false
	if point.x < _bounds.position.x or point.x > _bounds.end.x or point.y < _bounds.position.y or point.y > _bounds.end.y: return false
	for box in _boxes:
		if point.x > box.position.x and point.x < box.end.x and point.y > box.position.y and point.y < box.end.y: return false
	return true


func _candidate(origin: Vector2,point: Vector2,best: Vector3) -> Vector3:
	point = point.clamp(_bounds.position,_bounds.end)
	var distance := point.distance_squared_to(origin)
	if distance > best.z or not is_free(point): return best
	# Stable tie-breaking makes recovery independent of obstacle array order.
	if distance == best.z and (point.x > best.x or (point.x == best.x and point.y >= best.y)): return best
	return Vector3(point.x,point.y,distance)


func _recover(start: Vector2) -> Vector2:
	var point := start.clamp(_bounds.position,_bounds.end) if start.is_finite() else _bounds.get_center()
	last_recovered = not start.is_finite() or point != start
	if point == _recovery_query:
		last_recovery_failed = _recovery_failed
		last_recovered = last_recovered or _recovery_result != point
		return _recovery_result
	if is_free(point): return point
	# Recovery is exceptional, bounded by the content limit, and cached for a
	# repeated invalid spawn. It never blindly pushes through another obstacle.
	var best := Vector3(0,0,INF)
	for box in _boxes:
		var left := box.position.x-_skin
		var right := box.end.x+_skin
		var top := box.position.y-_skin
		var bottom := box.end.y+_skin
		best = _candidate(point,Vector2(left,point.y),best)
		best = _candidate(point,Vector2(right,point.y),best)
		best = _candidate(point,Vector2(point.x,top),best)
		best = _candidate(point,Vector2(point.x,bottom),best)
		for x in [left,right]:
			for y in [top,bottom]: best = _candidate(point,Vector2(x,y),best)
	for x in [_bounds.position.x,_bounds.end.x]:
		for y in [_bounds.position.y,_bounds.end.y]: best = _candidate(point,Vector2(x,y),best)
	_recovery_query = point
	_recovery_failed = not is_finite(best.z)
	_recovery_result = point if _recovery_failed else Vector2(best.x,best.y)
	last_recovery_failed = _recovery_failed
	last_recovered = last_recovered or _recovery_result != point
	return _recovery_result


## Entry fraction + axis normals. Grazing without entering is not a blocker.
static func _cast(start: Vector2,motion: Vector2,box: Rect2) -> Vector3:
	var entry: float = -INF
	var exit: float = INF
	var normal := Vector2.ZERO
	var tolerance := EPSILON/maxf(1.0,maxf(absf(motion.x),absf(motion.y)))
	for axis in range(2):
		if absf(motion[axis]) <= EPSILON:
			if start[axis] <= box.position[axis] or start[axis] >= box.end[axis]: return Vector3(2,0,0)
			continue
		var a: float = (box.position[axis]-start[axis])/motion[axis]
		var b: float = (box.end[axis]-start[axis])/motion[axis]
		var near: float = minf(a,b)
		if near > entry+tolerance:
			entry = near
			normal = Vector2.ZERO
			normal[axis] = -signf(motion[axis])
		elif absf(near-entry) <= tolerance:
			entry = maxf(entry,near)
			normal[axis] = -signf(motion[axis])
		exit = minf(exit,maxf(a,b))
	if entry < -tolerance or entry > 1.0 or exit <= maxf(0.0,entry) or entry > exit: return Vector3(2,0,0)
	return Vector3(maxf(0.0,entry),normal.x,normal.y)


static func _earlier(a: Vector3,b: Vector3,tolerance: float) -> Vector3:
	if b.x < a.x-tolerance: return b
	if b.x <= 1.0 and absf(b.x-a.x) <= tolerance:
		return Vector3(minf(a.x,b.x),a.y if a.y != 0 else b.y,a.z if a.z != 0 else b.z)
	return a


func _first_contact(start: Vector2,motion: Vector2) -> Vector3:
	var hit := Vector3(2,0,0)
	var tolerance := EPSILON/maxf(1.0,maxf(absf(motion.x),absf(motion.y)))
	for axis in range(2):
		if motion[axis] > 0.0 and start[axis]+motion[axis] >= _bounds.end[axis]:
			var normal := Vector2.ZERO
			normal[axis] = -1
			hit = _earlier(hit,Vector3((_bounds.end[axis]-start[axis])/motion[axis],normal.x,normal.y),tolerance)
		elif motion[axis] < 0.0 and start[axis]+motion[axis] <= _bounds.position[axis]:
			var normal := Vector2.ZERO
			normal[axis] = 1
			hit = _earlier(hit,Vector3((_bounds.position[axis]-start[axis])/motion[axis],normal.x,normal.y),tolerance)
	for box in _boxes: hit = _earlier(hit,_cast(start,motion,box),tolerance)
	return hit


func move(start: Vector2,displacement: Vector2) -> Vector2:
	last_normals.clear()
	last_recovered = false
	last_recovery_failed = false
	last_limited = false
	last_iterations = 0
	last_error = _configuration_error
	if not _valid: return start if start.is_finite() else Vector2.ZERO
	var point := _recover(start)
	if last_recovery_failed:
		last_error = "没有找到可恢复的地面脚点；本次移动已阻止"
		return point
	if not displacement.is_finite() or displacement.abs().x > MAX_DISPLACEMENT or displacement.abs().y > MAX_DISPLACEMENT:
		last_error = "地面位移无效；本次移动已阻止"
		return point
	var remaining := displacement
	for step in range(MAX_SLIDES):
		if remaining.length_squared() <= EPSILON*EPSILON: return point
		last_iterations += 1
		var hit := _first_contact(point,remaining)
		if hit.x > 1.0: return point+remaining
		# Back off along a proven clear path instead of pushing into a second wall.
		var safe_time := maxf(0.0,hit.x-_skin/maxf(absf(remaining.x),absf(remaining.y)))
		point += remaining*safe_time
		remaining *= 1.0-hit.x
		if hit.y != 0.0: remaining.x = 0.0
		if hit.z != 0.0: remaining.y = 0.0
		last_normals.append(Vector2(hit.y,hit.z))
	last_limited = remaining.length_squared() > EPSILON*EPSILON
	return point


func clip_velocity(value: Vector2) -> Vector2:
	if not _valid or last_recovery_failed or not last_error.is_empty() or not value.is_finite(): return Vector2.ZERO
	for normal in last_normals:
		if normal.x*value.x < 0: value.x = 0.0
		if normal.y*value.y < 0: value.y = 0.0
	return value
