extends RefCounted
var _tick: int=-1
var _room: int=-1
var _hp: Dictionary={}
var _arrows: Dictionary={}
# Contract: caller already validated the entire BattleFrame. No second protocol pass.
# HP deltas are net snapshot changes, not a replay or attribution to a particular attacker.
func observe_validated(frame: Dictionary,local_peer: int,after_gap: bool=false) -> Array[String]:
	var result: Array[String]=[]
	var tick: int=int(frame["tick"])
	if tick<=_tick:return result
	var baseline: bool=after_gap or _room!=int(frame["room"]) or _tick<0
	var hp: Dictionary={};var arrows: Dictionary={}
	var hurt: bool=false;var impact: bool=false;var shot: bool=false
	for actor: Array in frame["actors"]:
		var id: int=int(actor[0]);hp[id]=int(actor[5])
		if not baseline and _hp.has(id) and int(actor[5])<int(_hp[id]):
			if id==local_peer:hurt=true
			elif id<0:impact=true
	for arrow: Array in frame["arrows"]:
		var id: int=int(arrow[0]);arrows[id]=true
		if not baseline and not _arrows.has(id):shot=true
	_tick=tick;_room=int(frame["room"]);_hp=hp;_arrows=arrows
	if hurt:result.append("hurt")
	if impact:result.append("impact")
	if shot:result.append("shot")
	return result
