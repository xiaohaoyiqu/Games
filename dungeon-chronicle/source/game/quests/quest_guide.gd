extends RefCounted

# Display-only projection: never returns a profile candidate or grants rewards.
const Actions = preload("res://game/input/actions.gd")
var rules: RefCounted

func _init(profile_rules: RefCounted) -> void:
	rules = profile_rules

static func trackable(profile: Dictionary,id: String) -> bool:
	var quests: Variant = profile.get("quests")
	if not quests is Dictionary: return false
	var quest: Variant = quests.get(id)
	if not quest is Dictionary: return false
	# JSON decodes saved quest states as numbers that may be floats.
	var state: Variant = quest.get("state")
	return typeof(state) in [TYPE_INT,TYPE_FLOAT] and float(state) in [1.0,2.0]


static func committed_increments(before: Dictionary,after: Dictionary) -> Dictionary:
	var increments: Dictionary = {}
	for key: String in before:
		var previous: Dictionary = before[key]
		if int(previous["state"]) != 1 or not after.has(key): continue
		var increment: int = int(after[key]["progress"])-int(previous["progress"])
		if increment > 0: increments[key] = increment
	return increments


func describe(profile: Dictionary, context: Dictionary={}) -> Dictionary:
	if not rules.valid(profile):
		return {"ok":false,"rows":[],"tracked_quest":"","claimable_count":0,"has_offers":false,
			"hint":"委托记录暂不可用","detail":"请返回入口检查角色档案"}
	var tracked: String = str(context.get("tracked_quest",""))
	if not rules.quest_defs.has(tracked) or not trackable(profile,tracked): tracked = ""
	var in_run: bool = context.get("in_run",false) == true
	var id: String = str(context.get("id",""))
	var kills: Variant = context.get("kills",0)
	var difficulty: String = str(context.get("difficulty","normal"))
	var party: bool = in_run and context.get("party",false) == true
	var awaiting_result: bool = party and context.get("awaiting_result",false) == true
	var cleared: bool = not awaiting_result and context.get("cleared",false) == true
	var valid_run: bool = in_run and not id.is_empty() and id.length() <= 120 and rules.whole(kills,0,64)
	# A saved receipt already includes this run; do not add its kills twice.
	var settled: bool = valid_run and profile["settled_runs"].has(id)
	var projected: bool = valid_run and not settled
	var pending: bool = projected and context.get("pending",false) == true
	var saving: bool = pending and context.get("saving",false) == true
	var saved_progress: Dictionary = {}
	if settled and not awaiting_result and context.get("ended",false) == true:
		var receipt: Variant = context.get("saved_progress")
		if receipt is Dictionary: saved_progress = receipt
	var rows: Array[Dictionary] = []
	var unaccepted: bool = false
	var ready: bool = false
	var claimable_count: int = 0
	for key: String in rules.quest_defs:
		# A validated legacy archive has no state for the newly added quest.
		var quest: Dictionary = profile["quests"].get(key,{"state":0,"progress":0})
		var state: int = int(quest["state"])
		unaccepted = unaccepted or state == 0
		var supplemental: bool = rules.elite_armor_pending(profile,key)
		var claimable: bool = state == 2 or supplemental
		if claimable: claimable_count += 1
		if (state != 1 and not claimable) or (not tracked.is_empty() and key != tracked): continue
		var definition: Dictionary = rules.quest_defs[key]
		var progress: int = int(quest["progress"])
		var provisional: bool = projected and state == 1
		if provisional:
			progress = mini(int(definition["goal"]),progress+rules.quest_increment(key,int(kills),cleared,difficulty))
		ready = ready or claimable
		var objective: String = "回城补领回响重甲" if supplemental else _objective(definition["type"])
		rows.append({"id":key,"name":definition["name"],"progress":progress,"goal":int(definition["goal"]),"claimable":claimable,"provisional":provisional,
			"objective":objective,"saved_increment":int(saved_progress.get(key,0))})
	if party:
		var status: Array[String] = _party_status(rows.is_empty(),unaccepted,ready,projected,pending,awaiting_result,saving)
		return {"ok":true,"rows":rows,"tracked_quest":tracked,"claimable_count":claimable_count,"has_offers":unaccepted,
			"hint":status[0],"detail":status[1]}
	var hint: String
	var detail: String
	if saving:
		hint = "正在保存本局结算"
		detail = "保存后更新委托进度"
	elif pending:
		hint = "本局尚未保存 · 进度未生效"
		detail = Actions.binding_text("interact")+" 重试结算" if context.get("ended",false) == true else Actions.binding_text("return_menu")+" 重试回城结算"
	elif projected:
		hint = "本局预计 · 结算保存后生效"
		detail = "通关或撤退时结算"
	elif in_run:
		hint = "已保存 · 回城后在委托板领奖" if ready else "委托进度以已保存记录为准"
		detail = Actions.binding_text("return_menu")+" 返回城镇"
	else:
		hint = "城镇左侧委托板 · %s 查看" % Actions.binding_text("quests")
		detail = "%d 项待领奖 · 前往委托板领取" % claimable_count if claimable_count > 0 else "右侧遗迹入口 · %s 出征" % Actions.binding_text("interact")
	if rows.is_empty():
		if unaccepted:
			hint = "未接委托 · 城镇左侧委托板"
			detail = "回城后可接受委托" if in_run else "%s 查看 · 到委托板前接受" % Actions.binding_text("quests")
		else:
			hint = "委托奖励均已领取"
			detail = "回城后可再次挑战遗迹" if in_run else "右侧遗迹入口可再次挑战"
	return {"ok":true,"rows":rows,"tracked_quest":tracked,"claimable_count":claimable_count,"has_offers":unaccepted,
		"hint":hint,"detail":detail}


func departure_hint(profile: Dictionary,difficulty: String,tracked_id: String="") -> String:
	if difficulty not in ["normal","veteran"]: return ""
	var view: Dictionary = describe(profile,{"tracked_quest":tracked_id})
	var eligible := PackedStringArray()
	var elite_only := PackedStringArray()
	for row: Dictionary in view["rows"]:
		var name: String = row["name"]
		if row["claimable"]:
			if row["id"] == view["tracked_quest"]: return "回委托板领奖 · "+name
			continue
		var id: String = row["id"]
		# A kill and a clear show which objectives could progress in this mode.
		if rules.quest_increment(id,1,true,difficulty) > 0:
			eligible.append(name)
		elif difficulty == "normal" and rules.quest_increment(id,1,true,"veteran") > 0:
			elite_only.append(name)
	var parts := PackedStringArray()
	if not eligible.is_empty(): parts.append("本难度可推进 · "+"、".join(eligible))
	if not elite_only.is_empty(): parts.append("需通关精英 · "+"、".join(elite_only))
	return "    ".join(parts)


func _objective(kind: String) -> String:
	match kind:
		"kills": return "回响遗迹 · 击败敌人"
		"veteran_clear": return "精英回响遗迹 · 通关一次"
	return "回响遗迹 · 通关一次"


func _party_status(empty: bool,unaccepted: bool,ready: bool,projected: bool,pending: bool,awaiting_result: bool,saving: bool) -> Array[String]:
	if empty:
		if unaccepted: return ["本机角色尚未接委托","城镇委托板可接受"]
		return ["委托奖励均已领取","可继续挑战遗迹"]
	if awaiting_result: return ["等待房主确认终局","当前进度仍为预计"]
	if pending:
		if saving: return ["正在保存本机结算","当前进度仍为预计"]
		return ["结算尚未保存","可重试保存本机结算"]
	if projected: return ["队伍进度 · 预计","保存后计入个人委托"]
	if ready: return ["可领奖","回城后到委托板领取"]
	return ["显示本机已存进度","组队结算后更新"]
