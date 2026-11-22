extends RefCounted

## Shared arithmetic only. Eligibility, hit geometry, HP caps and timing remain callers' rules.
const WALK_SPEED: Vector2=Vector2(255.0,172.0)
static func scaled_damage(base: float,multiplier: float) -> int:
	return maxi(1,roundi(base*multiplier))
