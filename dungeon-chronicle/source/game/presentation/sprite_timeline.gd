extends RefCounted

signal invalidated
const MAX_FRAMES: int = 4096
const MAX_TRACKS: int = 32
var _frames: SpriteFrames
var _tracks: Dictionary = {}


func set_frames(value: SpriteFrames) -> void:
	if _frames == value: return
	if _frames != null and _frames.changed.is_connected(invalidate):
		_frames.changed.disconnect(invalidate)
	_frames = value
	if _frames != null: _frames.changed.connect(invalidate)
	invalidate()


func invalidate() -> void:
	_tracks.clear()
	invalidated.emit()


func track(animation: StringName) -> Dictionary:
	if _tracks.has(animation): return _tracks[animation]
	if _frames == null or not _frames.has_animation(animation): return {}
	var count := _frames.get_frame_count(animation)
	var fps := _frames.get_animation_speed(animation)
	if count <= 0 or count > MAX_FRAMES or not is_finite(fps) or fps <= 0: return {}
	var ends := PackedFloat64Array()
	var duration: float = 0.0
	for index in range(count):
		var weight := _frames.get_frame_duration(animation,index)
		if not is_finite(weight) or weight <= 0 or _frames.get_frame_texture(animation,index) == null: return {}
		var next: float = duration+weight/fps
		if not is_finite(next) or next <= duration: return {}
		duration = next
		ends.append(duration)
	if _tracks.size() >= MAX_TRACKS: _tracks.erase(_tracks.keys()[0])
	var result := {"ends":ends,"duration":duration,"loop":_frames.get_animation_loop(animation)}
	_tracks[animation] = result
	return result


## Return frame index and fraction without advancing gameplay or using wall time.
func sample(animation: StringName,seconds: float,loop: bool) -> Vector2:
	if not is_finite(seconds): return Vector2(-1,0)
	var value := track(animation)
	if value.is_empty(): return Vector2(-1,0)
	var ends: PackedFloat64Array = value["ends"]
	var duration: float = value["duration"]
	var point: float = fposmod(maxf(0,seconds),duration) if loop else clampf(seconds,0.0,duration)
	var low: int = 0
	var high: int = ends.size()-1
	while low < high:
		var middle: int = (low+high)/2
		if point < ends[middle]: high = middle
		else: low = middle+1
	var start: float = 0.0 if low == 0 else ends[low-1]
	return Vector2(low,clampf((point-start)/(ends[low]-start),0.0,1.0))
