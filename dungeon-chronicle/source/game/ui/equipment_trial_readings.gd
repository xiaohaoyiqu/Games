extends RefCounted

const TRIAL_LABELS: Dictionary = {"skill":"固定试斩","incoming":"固定承伤","walk":"定距走位","evade":"走位避锤"}
const WAITING: Dictionary = {"skill":"待试斩","incoming":"待承伤","walk":"待走位","evade":"待避锤"}


static func reading(kind: String, side: String, result: Dictionary) -> String:
	if result.is_empty(): return "%s %s" % [side,WAITING[kind]]
	match kind:
		"skill":
			var contact: String = "未命中" if int(result["damage"]) == 0 else "损血 %d" % int(result["damage"])
			return "%s %s · 冷却 %.3f 秒" % [side,contact,float(result["cooldown"])]
		"incoming":
			var contact: String = "未命中" if int(result["damage"]) == 0 else "扣 %d" % int(result["damage"])
			return "%s %s 剩 %d/%d" % [side,contact,int(result["remaining_hp"]),int(result["target_hp"])]
		"walk":
			return "%s %.3f 秒" % [side,float(result["seconds"])]
		"evade":
			var damage: int = int(result["damage"])
			return "%s 扣 %d" % [side,damage] if damage > 0 else side + " 走位避开"
	return ""


static func evade_detail(side: String, result: Dictionary) -> String:
	return "%s 判定时最近纵深差 %.2f，重锤纵深阈值 %.2f，剩余生命 %d/%d。" % [
		side,float(result["min_depth_gap"]),float(result["attack_depth"]),int(result["remaining_hp"]),int(result["target_hp"])]


static func report_text(report: Dictionary) -> String:
	var lines := PackedStringArray([
		"试用时 Lv.%d · A 当时穿戴：%s +%d  /  B 所选：%s +%d" % [
			report["level"],report["current_name"],report["current_rank"],report["candidate_name"],report["candidate_rank"]],
		"每侧保留本次试用最近完成的一次。离开城镇后清除。",
		"试斩与重锤均从同线相距 %.0f 开始。出征时的敌人和站位可能不同。" % float(report["separation"]),
		"",
	])
	var results: Dictionary = report["results"]
	for kind: String in TRIAL_LABELS:
		var sides: Dictionary = results.get(kind,{})
		var current: Dictionary = sides.get("current",{})
		var candidate: Dictionary = sides.get("candidate",{})
		var heading: String = TRIAL_LABELS[kind]
		if kind == "walk" and not sides.is_empty():
			var measured: Dictionary = current if not current.is_empty() else candidate
			heading += " · %.0f 单位／模拟用时" % float(measured["distance"])
		lines.append("%s   %s  /  %s" % [heading,reading(kind,"A",current),reading(kind,"B",candidate)])
		if kind == "evade":
			if not current.is_empty(): lines.append(evade_detail("A",current))
			if not candidate.is_empty(): lines.append(evade_detail("B",candidate))
	return "\n".join(lines)
