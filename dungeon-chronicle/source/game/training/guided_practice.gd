extends RefCounted

enum Lesson { DEPTH, ATTACK, LAUNCH, DASH, EVADE, JUMP, RETREAT, COVER, WARDEN, WARDEN_RAGE, ARROW_DODGE }
enum AttackStage { APPROACH, FIRST, SECOND, EXIT }
const LAST_LESSON: int = Lesson.JUMP
const WARDEN_MOVES: Array[String] = ["warden_slam","warden_sweep"]
const RETREAT_ENTRY := Rect2(358,538,38,26)
const RETREAT_EXIT := Rect2(528,418,108,57)
const COVER_MARKER := Rect2(148,488,70,20)
const ARROW_DODGE_LANE := Rect2(432,538,96,24)
const ARROW_DODGE_ZONES: Array[Rect2] = [Rect2(432,465,96,50),Rect2(432,585,96,50)]
const JUMP_HEIGHT: float = 24.0
const TITLES: Array[String] = ["地面纵深","同线普攻","破空斩挑起","冲刺进入标记","上下错位避开挥击","起跳与脚点落地","绕柱连续撤离","柱后掩护","守门人双招","守门人狂怒节奏","离弦后错位"]
const INSTRUCTIONS: Array = [
	["用走位进入金色地面框。","上下移动改变地面纵深，跳跃高度另算。"],
	["先向下靠近守卫脚点这一线，用普攻命中。","前后错得太远，挥剑也不会命中。"],
	["靠近守卫，用破空斩命中并挑起目标。","技能有冷却，按键不等于已经命中。"],
	["向右冲刺，进入金色地面框。","只走进框内不算完成，需要实际冲刺。"],
	["留在守卫身前，等橙色预警出现后上下错位。","避开一次完整挥击；被击中可重试本项。"],
	["在金框内起跳，再落回金框。","看影子／脚点；起跳升高，上下走位才改纵深。"],
	["从左柱旁绕到金框，引出挥击。","看两次预警，用走位或冲刺避开，再进撤离框。"],
	["弓手拉弓时，向上走进柱后的金框。","留在金框内，等箭矢被石柱挡下。"],
	["在震地范围内等前摇后段，起跳越过重击。","落地后看横扫预警，向上或向下走出橙框。"],
	["狂怒前摇更短，在震地范围内看准时机起跳。","落地后留意更快的横扫，向上或向下走出橙框。"],
	["等箭离弦，再用上下走位进入任一金框。","脚点留在框内，等箭穿过练习区域。"]]
const OBSERVATION_MESSAGES: Dictionary = {
	"in-range":"",
	"target":"本次出招观察：守卫暂不可受击，稍等再试",
	"facing":"本次出招观察：守卫在身后，先转身",
	"reach":"本次出招观察：横向太远，靠近后出招",
	"depth":"本次出招观察：脚点前后错开，先上下靠近",
	"height":"本次出招观察：高度未重合，等落地再试"}
var _last_observed_sequence: int = -1
var _observation: String = ""
var lesson: int = Lesson.DEPTH
var completed: bool = false
var failed: bool = false
var revision: int = 0
var retries: int = 0
var trial_active: bool = false
var _trial_sequence: int = -1
var _failure: String = ""
var _jump_sequence: int = -1
var _last_jump_sequence: int = -1
var _jump_high: bool = false
var _jump_note: String = ""
var attack_stage: int = AttackStage.APPROACH
var _sequence_checked: bool = false
var _sequence_all_clear: bool = true
var projectile_in_flight: bool = false

static func valid_lesson(index: int) -> bool:
	return index >= Lesson.DEPTH and index < TITLES.size()

# Explicitly start one lesson; selecting a UI draft never calls this method.
func select_lesson(index: int) -> bool:
	if not valid_lesson(index): return false
	lesson = index
	completed = false; failed = false; trial_active = false
	_trial_sequence = -1; _failure = ""; retries = 0; revision += 1
	_clear_observation()
	_clear_jump()
	_reset_attack_sequence()
	projectile_in_flight = false
	return true

func _clear_jump() -> void:
	_jump_sequence = -1; _jump_high = false; _jump_note = ""

func _clear_observation() -> void:
	_last_observed_sequence = -1
	_observation = ""

# One first active-stage sample per actual attack. No input presses, actor refs or history.
func observe_attack(sequence: int, move_id: String, reason: String) -> bool:
	if completed or failed or sequence < 0 or sequence > 1000000000 or sequence <= _last_observed_sequence: return false
	if lesson == Lesson.ATTACK:
		if move_id not in ["slash_1","slash_2","slash_3"]: return false
	elif lesson == Lesson.LAUNCH:
		if move_id != "rising_slash": return false
	else: return false
	if not OBSERVATION_MESSAGES.has(reason): return false
	_last_observed_sequence = sequence
	var next_observation: String = OBSERVATION_MESSAGES[reason]
	if next_observation != _observation:
		_observation = next_observation
		revision += 1
	return true

func marker() -> Rect2:
	if lesson == Lesson.DEPTH: return Rect2(520,420,80,60)
	if lesson == Lesson.DASH: return Rect2(650,510,100,80)
	if lesson == Lesson.JUMP: return Rect2(520,500,120,100)
	if lesson == Lesson.COVER and not failed and not completed: return COVER_MARKER
	if lesson == Lesson.RETREAT and not failed and not completed:
		if attack_stage == AttackStage.APPROACH: return RETREAT_ENTRY
		if attack_stage == AttackStage.EXIT: return RETREAT_EXIT
	return Rect2()

func _complete() -> void:
	completed = true; trial_active = false; revision += 1
	_clear_observation()
	_clear_jump()

func observe_motion(point: Vector2, height: float, dashing: bool) -> void:
	if completed or failed or not point.is_finite() or not is_finite(height) or height < 0: return
	if lesson == Lesson.DEPTH and height <= 1.0 and marker().has_point(point): _complete()
	elif lesson == Lesson.DASH and dashing and height <= 1.0 and marker().has_point(point): _complete()
	elif lesson == Lesson.JUMP and _jump_sequence >= 0 and height >= JUMP_HEIGHT and not _jump_high:
		_jump_high = true; revision += 1
	elif lesson == Lesson.RETREAT and height <= 1.0 and marker().has_point(point):
		if attack_stage == AttackStage.APPROACH:
			attack_stage = AttackStage.FIRST
			revision += 1
		elif attack_stage == AttackStage.EXIT: _complete()

# Called only for an accepted actor jump; motion samples or key presses cannot arm it.
func begin_jump(sequence: int, point: Vector2) -> bool:
	if lesson != Lesson.JUMP or completed or failed or _jump_sequence >= 0 or not point.is_finite(): return false
	if sequence < 0 or sequence > 1000000000 or sequence <= _last_jump_sequence: return false
	_last_jump_sequence = sequence
	if not marker().has_point(point):
		_set_jump_note("先回到金色地面框内，再起跳")
		return false
	_jump_sequence = sequence; _jump_high = false; _jump_note = ""; revision += 1
	return true

func _set_jump_note(value: String) -> void:
	if value != _jump_note:
		_jump_note = value; revision += 1

# A real landing event is needed, even after a motion sample has reached the ground.
func finish_jump(point: Vector2) -> bool:
	if lesson != Lesson.JUMP or completed or failed or _jump_sequence < 0 or not point.is_finite(): return false
	var high: bool = _jump_high
	_clear_jump()
	if high and marker().has_point(point): _complete(); return true
	_set_jump_note("落在框外，回框内再跳一次" if high else "这次未完成起落，回框内再跳一次")
	return false

func landed(move_id: String, damage: int, launched: bool) -> void:
	if completed or failed or damage <= 0 or damage > 1000000: return
	if lesson == Lesson.ATTACK and move_id in ["slash_1","slash_2","slash_3"]: _complete()
	elif lesson == Lesson.LAUNCH and move_id == "rising_slash" and launched: _complete()

func begin_trial(sequence: int) -> bool:
	if lesson != Lesson.EVADE or completed or failed or trial_active or sequence < 0 or sequence > 1000000000: return false
	_trial_sequence = sequence; trial_active = true; revision += 1
	return true

func hurt() -> void:
	if lesson == Lesson.JUMP and _jump_sequence >= 0:
		_clear_jump(); _set_jump_note("受击中断起落，回框内再跳一次")
	if is_projectile_lesson(lesson) and trial_active:
		_fail_projectile_trial("箭矢命中 · 重试后回到起点")
	if uses_attack_sequence() and trial_active:
		var message: String = "撤离途中受击 · 从柱旁重试"
		if is_warden_lesson(lesson):
			message = "重击命中 · 重试，在前摇后段起跳" if attack_stage == AttackStage.FIRST else "横扫命中 · 重试，上下走出橙框"
		_fail_attack_sequence(message)
	if lesson == Lesson.EVADE and trial_active:
		failed = true; trial_active = false; _failure = "这次被击中了 · 重试本项"; revision += 1

func finish_trial(sequence: int, full_window: bool, depth_escaped: bool) -> bool:
	if not trial_active or sequence != _trial_sequence or failed or completed: return false
	trial_active = false
	if full_window and depth_escaped: _complete(); return true
	failed = true; revision += 1
	_failure = "出招中断 · 重试，等待完整挥击" if not full_window else "请试着上下错位 · 重试本项"
	return false

static func is_warden_lesson(index: int) -> bool:
	return index in [Lesson.WARDEN,Lesson.WARDEN_RAGE]


func uses_attack_sequence() -> bool:
	return lesson == Lesson.RETREAT or is_warden_lesson(lesson)


func _reset_attack_sequence() -> void:
	attack_stage = AttackStage.FIRST if is_warden_lesson(lesson) else AttackStage.APPROACH
	_sequence_checked = false
	_sequence_all_clear = true


func sequence_move_id() -> String:
	if not uses_attack_sequence() or failed or completed: return ""
	match attack_stage:
		AttackStage.FIRST: return WARDEN_MOVES[0] if is_warden_lesson(lesson) else "dummy_strike"
		AttackStage.SECOND: return WARDEN_MOVES[1] if is_warden_lesson(lesson) else "bulwark_crush"
	return ""


func begin_sequence_attack(sequence: int, move_id: String) -> bool:
	if trial_active or move_id.is_empty() or move_id != sequence_move_id(): return false
	if sequence < 0 or sequence > 1000000000 or sequence <= _trial_sequence: return false
	_trial_sequence = sequence
	_sequence_checked = false
	_sequence_all_clear = true
	trial_active = true
	revision += 1
	return true


func observe_sequence_check(sequence: int, move_id: String, clear: bool) -> bool:
	if not uses_attack_sequence() or not trial_active or sequence != _trial_sequence or move_id != sequence_move_id():
		return false
	_sequence_checked = true
	_sequence_all_clear = _sequence_all_clear and clear
	return true


func finish_sequence_attack(sequence: int, recovered: bool) -> bool:
	if not uses_attack_sequence() or not trial_active or sequence != _trial_sequence: return false
	if not recovered or not _sequence_checked:
		cancel_attack_sequence()
		return false
	if not _sequence_all_clear:
		var message: String = "招式中断或缺少完整判定 · 重试"
		if is_warden_lesson(lesson):
			message = "用起跳越过震地 · 重试" if attack_stage == AttackStage.FIRST else "落地后上下错位避开横扫 · 重试"
		_fail_attack_sequence(message)
		return false
	trial_active = false
	attack_stage += 1
	if is_warden_lesson(lesson) and attack_stage == AttackStage.EXIT:
		_complete()
	else:
		revision += 1
	return true


func cancel_attack_sequence() -> void:
	_fail_attack_sequence("招式中断或缺少完整判定 · 重试")


func _fail_attack_sequence(message: String) -> void:
	if not uses_attack_sequence() or completed or failed: return
	failed = true
	trial_active = false
	_failure = message
	revision += 1


func _retreat_status() -> String:
	match attack_stage:
		AttackStage.APPROACH: return "绕过左柱，走进金框"
		AttackStage.FIRST: return "1 / 2 挥击 · 离开橙框"
		AttackStage.SECOND: return "2 / 2 重锤 · 留出更大纵深"
	return "已避开两招 · 脚点进入撤离框"


static func is_projectile_lesson(index: int) -> bool:
	return index in [Lesson.COVER,Lesson.ARROW_DODGE]


func begin_projectile_trial(sequence: int) -> bool:
	if not is_projectile_lesson(lesson) or completed or failed or trial_active or sequence < 0 or sequence > 1000000000 or sequence <= _trial_sequence:
		return false
	_trial_sequence = sequence
	trial_active = true
	revision += 1
	return true


func observe_projectile_launch(sequence: int) -> bool:
	if not is_projectile_lesson(lesson) or not trial_active or projectile_in_flight or sequence != _trial_sequence: return false
	projectile_in_flight = true
	revision += 1
	return true


func finish_projectile_trial(sequence: int, observed_success: bool) -> bool:
	if not is_projectile_lesson(lesson) or not trial_active or sequence != _trial_sequence: return false
	if projectile_in_flight and observed_success:
		projectile_in_flight = false
		_complete()
		return true
	var message: String = "射箭中断 · 重试"
	if projectile_in_flight:
		message = "请在发射前进入金框，并留到箭撞柱 · 重试" if lesson == Lesson.COVER else "用上下走位进金框，留到箭通过 · 重试"
	_fail_projectile_trial(message)
	return false


func cancel_projectile_trial(message: String="训练射击不可用 · 重试") -> void:
	_fail_projectile_trial(message)


func _fail_projectile_trial(message: String) -> void:
	if not is_projectile_lesson(lesson) or failed or completed: return
	failed = true
	trial_active = false
	projectile_in_flight = false
	_failure = message
	revision += 1


func retry() -> void:
	completed = false; failed = false; trial_active = false; _trial_sequence = -1; _failure = ""
	retries = mini(1000000,retries+1); revision += 1
	_clear_observation()
	_clear_jump()
	_reset_attack_sequence()
	projectile_in_flight = false

func advance() -> bool:
	if not completed or lesson >= LAST_LESSON: return false
	lesson += 1; completed = false; failed = false; trial_active = false; _trial_sequence = -1; _failure = ""; revision += 1
	_clear_observation()
	_clear_jump()
	return true

func describe() -> Dictionary:
	var status: String = "完成目标后可继续，也可重试本项"
	if completed: status = "完成 · 结束引导后返回自由练习" if lesson >= LAST_LESSON else "完成 · 可进入下一项"
	elif failed: status = _failure
	elif lesson == Lesson.COVER: status = "箭已射出 · 留在金框内等撞柱" if projectile_in_flight else "看弓手预警 · 尽快走到金框"
	elif lesson == Lesson.ARROW_DODGE:
		status = "箭已离弦 · 上下走入金框" if projectile_in_flight else "留在箭路上 · 等箭离弦"
	elif lesson == Lesson.RETREAT: status = _retreat_status()
	elif is_warden_lesson(lesson):
		status = "1 / 2 震地重击 · 看准时机起跳" if attack_stage == AttackStage.FIRST else "2 / 2 裂空横扫 · 落地上下错位"
	elif trial_active: status = "守卫正在挥击 · 上下走位避开橙框"
	elif lesson == Lesson.JUMP:
		if _jump_sequence >= 0: status = "已经腾空 · 看脚点影子，落回金框" if _jump_high else "正在起跳 · 落地后才算完成"
		elif not _jump_note.is_empty(): status = _jump_note
		else: status = "在框内起跳，再落地 · 只走入框内不算完成"
	elif not _observation.is_empty(): status = _observation
	return {"lesson":lesson,"title":"%d / %d  %s" % [lesson+1,TITLES.size(),TITLES[lesson]],"instructions":INSTRUCTIONS[lesson].duplicate(),"status":status,"completed":completed,"failed":failed,"jump_active":_jump_sequence >= 0,"jump_high":_jump_high}
