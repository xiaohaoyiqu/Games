extends RefCounted

## Read-only explanation of current scalar actor state. Never grants or queues an action.
const LIMIT: int=1_000_000_000
static func cooldown_tenths(value: float) -> int:
	if not is_finite(value) or value<0.0 or value>1_000_000.0:return -1
	return ceili(value*10.0)

static func describe(alive: bool,attacking: bool,hurt: bool,dashing: bool,cooldown: float,buffered: bool,valid_skill: bool,starts: int,incomplete: bool=false) -> Dictionary:
	var tenths: int=cooldown_tenths(cooldown)
	if tenths<0 or starts<0 or starts>LIMIT:return {"ok":false,"text":"技能状态暂不可读","note":"未据此判断可以出招。"}
	var state: String="ready"
	if not alive:state="dead"
	elif attacking:state="action"
	elif hurt:state="hurt"
	elif dashing:state="dash"
	elif cooldown>0.0:state="cooldown"
	elif not valid_skill:state="unavailable"
	var text: String={"ready":"可尝试出招","dead":"已倒地，等待复位","action":"当前动作尚未结束","hurt":"受击恢复中","dash":"冲刺中","cooldown":"冷却中","unavailable":"招式数据不可用"}[state]
	if tenths>0 and state!="dead":text+=" · 冷却 %.1f秒" % (tenths/10.0)
	var pending: bool=buffered and alive
	var note: String="技能键已短暂缓冲；过期需再按。" if pending else "本轮实际技能出招 %d次；出招不等于命中。" % starts
	if incomplete:note+="（计数不完整）"
	return {"ok":true,"state":state,"tenths":tenths,"buffered":pending,"starts":starts,"text":text,"note":note}
