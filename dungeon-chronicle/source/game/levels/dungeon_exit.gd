extends RefCounted

enum Status { HIDDEN, DOWN, APPROACH, LAND, EDGE, READY }
const FLOOR := Rect2(1100,445,140,175)
const GROUND_LIMIT: float = 1.0


static func _in_floor(point: Vector2) -> bool:
	# Preserve the inclusive depth boundary and the original rightward threshold.
	return point.x >= FLOOR.position.x and point.y >= FLOOR.position.y and point.y <= FLOOR.end.y


static func can_cross(point: Vector2,height: float) -> bool:
	return _in_floor(point) and height <= GROUND_LIMIT


static func status(point: Vector2,height: float,hp: int,rounded: bool=false) -> Status:
	if hp <= 0: return Status.DOWN
	if not _in_floor(point): return Status.APPROACH
	if height > GROUND_LIMIT: return Status.LAND
	# Wire coordinates round to hundredths. A rounded boundary value may lie
	# on either side of the host's inclusive comparison.
	if rounded and (point.x == FLOOR.position.x or point.y == FLOOR.position.y or point.y == FLOOR.end.y or height == GROUND_LIMIT):
		return Status.EDGE
	return Status.READY
