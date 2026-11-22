extends RefCounted

# One bounded, data-only issue. Never retain source values or parser error text.
const NAMES = {"configuration":"游戏配置","items":"装备配置（items.json）","quests":"委托配置（town_quests.json）","skills":"技能配置（training_moves.json）","dungeon":"副本配置（echo_ruins.json）","roster":"敌人配置（ruins_roster.json）","difficulties":"难度配置（difficulties.json）"}
const REASONS = {"open":"文件无法读取","size":"文件为空或超过64KiB","changed-size":"读取期间文件长度改变","short-read":"文件未完整读入","utf8":"文件不是有效UTF-8字节","depth":"结构深度超限或括号／引号不完整","json":"JSON解析失败","duplicate":"JSON对象包含重复字段","shape":"结构或版本不符合规则","value":"字段内容、类型或数值不符合规则","timing":"前摇、有效和后摇的总时长超过60秒","required":"缺少必需定义","reference":"引用的定义不存在"}
const MAX_FIELD: int = 160

static func make(kind: String,code: String,field: String="") -> Dictionary:
	var safe_field: String = field if field.length()<=MAX_FIELD else ""
	for i in range(safe_field.length()):
		var ch: int = safe_field.unicode_at(i)
		if not (ch>=97 and ch<=122) and not (ch>=48 and ch<=57) and ch!=95 and ch!=46 and ch!=91 and ch!=93:
			safe_field = ""; break
	return {"kind":kind if NAMES.has(kind) else "configuration","code":code if REASONS.has(code) else "shape","field":safe_field}

static func message(value: Dictionary) -> String:
	if value.is_empty(): return ""
	var kind: Variant = value.get("kind"); var code: Variant = value.get("code"); var field: Variant = value.get("field")
	var safe: Dictionary = make(kind if kind is String else "",code if code is String else "",field if field is String else "")
	var location: String = " · 字段 "+safe["field"] if not safe["field"].is_empty() else ""
	return NAMES[safe["kind"]]+location+"："+REASONS[safe["code"]]+"。"
