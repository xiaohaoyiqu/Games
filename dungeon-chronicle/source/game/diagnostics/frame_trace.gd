extends RefCounted

## Explicit diagnostic opt-in. No files, network messages or gameplay clocks.
## Times are inclusive wall-clock microseconds; nested scopes are not additive.
const SCOPES: Array[String] = ["content_fingerprint","profile_load","party_commit","journal_validate","journal_read","journal_prepare","journal_clear","journal_inspect","scene_ready","scene_font","scene_world","scene_populate","scene_hud","room_load","host_physics","client_physics","host_model","snapshot","replica","navigate","scene_wait","scene_commit","publish","publish_validate","save","poll","native_poll","native_send","packet_dispatch","encode","decode"]
const MAX_SAMPLES: int = 4096
const MAX_EVENTS: int = 64
const SLOW_USEC: int = 16667
static var enabled: bool = false
static var room: int = -1
static var _buckets: Dictionary = {}
static var _events: Array = []
static var _mutex: Mutex = Mutex.new()


static func reset(active: bool=false) -> void:
	_mutex.lock()
	enabled = active; room = -1; _buckets.clear(); _events.clear()
	_mutex.unlock()


static func begin() -> int:
	return Time.get_ticks_usec() if enabled else -1


static func end(scope: String,started: int,room_id: int=-2) -> void:
	if not enabled or started < 0: return
	add(scope,maxi(0,Time.get_ticks_usec()-started),room if room_id == -2 else room_id,started)


static func add(scope: String,duration: int,room_id: int=-1,started: int=0) -> void:
	if not enabled or scope not in SCOPES or room_id < -1 or room_id > 3 or duration < 0 or duration > 172800000000: return
	var thread_kind: String = "main" if OS.get_thread_caller_id() == OS.get_main_thread_id() else "worker"
	var key: String = scope+":"+str(room_id)+":"+thread_kind
	_mutex.lock()
	if not enabled: _mutex.unlock(); return
	var bucket: Dictionary
	if _buckets.has(key): bucket = _buckets[key]
	else: bucket = {"scope":scope,"room":room_id,"thread":thread_kind,"count":0,"sum":0,"max":0,"values":[]}
	var count: int = bucket["count"]
	var values: Array = bucket["values"]
	if values.size() < MAX_SAMPLES: values.append(duration)
	else: values[count%MAX_SAMPLES] = duration
	bucket["values"] = values; bucket["count"] = count+1
	bucket["sum"] = int(bucket["sum"])+duration; bucket["max"] = maxi(int(bucket["max"]),duration)
	_buckets[key] = bucket
	if duration >= SLOW_USEC:
		var event := {"scope":scope,"room":room_id,"thread":thread_kind,"started_usec":started,"duration_usec":duration}
		if _events.size() < MAX_EVENTS: _events.append(event)
		else:
			var smallest: int = 0
			for index in range(1,_events.size()):
				if _events[index]["duration_usec"] < _events[smallest]["duration_usec"]: smallest = index
			if duration > _events[smallest]["duration_usec"]: _events[smallest] = event
	_mutex.unlock()


static func report() -> Dictionary:
	_mutex.lock()
	var buckets: Array = _buckets.values().duplicate(true)
	var slow: Array = _events.duplicate(true)
	var active: bool = enabled
	_mutex.unlock()
	var statistics: Array = []
	for bucket in buckets:
		var values: Array = bucket["values"].duplicate(); values.sort()
		var size: int = values.size()
		statistics.append({"scope":bucket["scope"],"room":bucket["room"],"thread":bucket["thread"],"count":bucket["count"],"sampled":size,
			"mean_usec":float(bucket["sum"])/int(bucket["count"]),"max_usec":bucket["max"],
			"p50_usec":values[maxi(0,ceili(size*0.5)-1)],"p95_usec":values[maxi(0,ceili(size*0.95)-1)]})
	slow.sort_custom(func(a,b): return a["duration_usec"] > b["duration_usec"])
	return {"enabled":active,"scopes":statistics,"slow_events":slow,"per_scope_room_capacity":MAX_SAMPLES,
		"slow_event_capacity":MAX_EVENTS,"semantics":"inclusive wall-clock usec; scene_wait is asynchronous latency, not main-thread work; native_send measures local put_packet through return, including local ENet flush; quantiles use at most latest 4096 calls per scope, room and thread kind, means/maxima use all calls; clocks never advance gameplay"}
