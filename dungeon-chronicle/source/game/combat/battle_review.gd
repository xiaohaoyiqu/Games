extends RefCounted

const Practice = preload("res://game/training/guided_practice.gd")
const LESSON_BY_MOVE := {
	"raider_cut":Practice.Lesson.EVADE,
	"bulwark_crush":Practice.Lesson.RETREAT,
	"archer_shot":Practice.Lesson.COVER,
	"warden_slam":Practice.Lesson.WARDEN,
	"warden_sweep":Practice.Lesson.WARDEN
}
const MAX_RECENT: int = 5
const DISPLAY_RECENT: int = 3
const MAX_ELAPSED_SECONDS: float = 1000000000.0
const MAX_HITS: int = 1000000
const MAX_DAMAGE: int = 1000000000
const SOURCE_MOVES := {"raider":["raider_cut"],"archer":["archer_shot"],"bulwark":["bulwark_crush"],"warden":["warden_slam","warden_sweep"]}
const TIPS := {
	"raider_cut":["近身劈砍前会出现橙色预警。","可上下错位，等出手后再反击。"],
	"archer_shot":["箭矢沿发射时的方向直线飞行。","可上下错位，或利用石柱阻挡。"],
	"bulwark_crush":["重锤前摇较长，留意橙色预警。","先退出范围，再抓收招时机。"],
	"warden_slam":["震地覆盖前后，及时起跳或退开。","狂怒后更快，留意预警再进攻。"],
	"warden_sweep":["横扫覆盖身前，先退出橙框。","狂怒后更快，留意预警再进攻。"]}
var revision: int = 0
var hit_count: int = 0
var damage_total: int = 0
var incomplete: bool = false
var _recent: Array[Dictionary] = []
var _names: Dictionary = {}
var _labels: Dictionary = {}
var _sources: Dictionary = {}
var _last_warden_enraged: bool = false

func _init(roster: Dictionary={}, moves: Dictionary={}) -> void:
	# Freeze only bounded display strings, never actors or whole moves/resources.
	for kind: String in SOURCE_MOVES:
		if not roster.get(kind) is Dictionary: continue
		_names[kind] = str(roster[kind].get("name","未知敌人")).left(10)
		_sources[kind] = {"hits":0,"damage":0}
		for id: String in SOURCE_MOVES[kind]:
			if moves.get(id) is Dictionary: _labels[id] = str(moves[id].get("label","未知招式")).left(10)

func record(kind: String, move_id: String, damage: int, hp_remaining: int, room_index: int, elapsed: float, enraged: bool=false) -> bool:
	if not _names.has(kind) or not SOURCE_MOVES[kind].has(move_id) or not _labels.has(move_id) or damage <= 0 or damage > 1000000 or hp_remaining < 0 or hp_remaining > 1000000 or room_index < 0 or room_index > 63 or not is_finite(elapsed) or elapsed < 0 or elapsed > MAX_ELAPSED_SECONDS or hit_count >= MAX_HITS or damage_total > MAX_DAMAGE-damage:
		if not incomplete: incomplete = true; revision += 1
		return false
	var entry := {"kind":kind,"move":move_id,"damage":damage,"hp":hp_remaining,"room":room_index+1,"time":elapsed}
	_recent.append(entry)
	_last_warden_enraged = kind == "warden" and enraged
	if _recent.size() > MAX_RECENT: _recent.pop_front()
	hit_count += 1; damage_total += damage; revision += 1
	_sources[kind]["hits"] += 1; _sources[kind]["damage"] += damage
	return true

func practice_lesson() -> int:
	if _recent.is_empty() or int(_recent.back()["hp"]) != 0: return -1
	if _last_warden_enraged: return Practice.Lesson.WARDEN_RAGE
	return int(LESSON_BY_MOVE.get(_recent.back()["move"],-1))


func recent_records() -> Array[Dictionary]:
	var rows: Array[Dictionary] = []
	for index in range(_recent.size()-1,maxi(-1,_recent.size()-1-DISPLAY_RECENT),-1):
		rows.append(_recent[index].duplicate())
	return rows


func describe(actual_damage: int, defeated: bool) -> Dictionary:
	var rows: Array[Dictionary] = recent_records()
	for entry in rows:
		entry["source"] = _names[entry["kind"]]; entry["label"] = _labels[entry["move"]]
	var last: Dictionary = rows[0].duplicate() if not rows.is_empty() else {}
	var fatal: bool = defeated and not last.is_empty() and int(last["hp"]) == 0
	var hints: Array = TIPS[last["move"]].duplicate() if not last.is_empty() else ["留意敌人出手前的橙色预警。","回城可查看装备，调整后再出发。"]
	return {"hits":hit_count,"observed_damage":damage_total,"actual_damage":actual_damage,"unattributed_damage":maxi(0,actual_damage-damage_total),"complete":not incomplete and actual_damage == damage_total,"recent":rows,"retained":_recent.size(),"sources":_sources.duplicate(true),"last":last,"fatal":fatal,"hints":hints,"practice_lesson":practice_lesson() if fatal else -1}
