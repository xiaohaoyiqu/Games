extends RefCounted

# Optional terminal presentation, separate from the frame used for settlement.
const Frame = preload("res://game/network/battle_frame.gd")
const Review = preload("res://game/combat/battle_review.gd")


static func valid(value: Variant) -> bool:
	if not value is Array or value.is_empty() or value.size() > 4: return false
	var peers: Dictionary = {}
	for row in value:
		if not row is Dictionary or not Frame.fields(row,["peer","damage","observed","complete","recent"]): return false
		if not Frame.whole(row["peer"],1) or peers.has(int(row["peer"])): return false
		peers[int(row["peer"])] = true
		if not Frame.whole(row["damage"],0,Review.MAX_DAMAGE) or not Frame.whole(row["observed"],0,int(row["damage"])): return false
		if not row["complete"] is bool or (row["complete"] and row["damage"] != row["observed"]): return false
		if not row["recent"] is Array or row["recent"].size() > Review.DISPLAY_RECENT: return false
		if row["recent"].is_empty() != (row["observed"] == 0): return false
		var recent_damage: int = 0
		var previous_room: int = 4
		var previous_time: float = Review.MAX_ELAPSED_SECONDS
		for hit in row["recent"]:
			if not hit is Dictionary or not Frame.fields(hit,["kind","move","damage","hp","room","time"]): return false
			if not hit["kind"] is String or not hit["move"] is String or not Review.SOURCE_MOVES.get(hit["kind"],[]).has(hit["move"]): return false
			if not Frame.whole(hit["damage"],1,10000) or not Frame.whole(hit["hp"],0,10000): return false
			if not Frame.whole(hit["room"],1,previous_room) or not Frame.number(hit["time"],0,previous_time): return false
			previous_room = int(hit["room"])
			previous_time = float(hit["time"])
			recent_damage += int(hit["damage"])
		if recent_damage > int(row["observed"]): return false
	return true


static func normalize(rows: Array) -> void:
	for row: Dictionary in rows:
		for field: String in ["peer","damage","observed"]: row[field] = int(row[field])
		for hit: Dictionary in row["recent"]:
			for field: String in ["damage","hp","room"]: hit[field] = int(hit[field])
			hit["time"] = float(hit["time"])


static func agrees_with_terminal(rows: Array,frame: Dictionary,dungeon: Dictionary) -> bool:
	if not valid(rows) or int(frame["phase"]) not in [2,3]: return false
	var heroes: Dictionary = {}
	var room_cleared := true
	for actor: Array in frame["actors"]:
		if int(actor[0]) > 0:
			heroes[int(actor[0])] = Vector2i(int(actor[5]),int(actor[6]))
		elif actor[5] > 0:
			room_cleared = false
	if rows.size() != heroes.size(): return false
	var rooms: Array = dungeon["rooms"]
	var room_count: int = int(frame["room"])+1
	var remaining_potions := room_count-1
	if room_cleared and room_count < rooms.size(): remaining_potions += 1
	var potion_heal := int(dungeon["room_heal"])
	for row: Dictionary in rows:
		var peer: int = int(row["peer"])
		if not heroes.has(peer): return false
		var hp: Vector2i = heroes[peer]
		var net_loss: int = hp.y-hp.x
		var healed := int(row["damage"])-net_loss
		if healed < 0: return false
		if healed > 0:
			# A living collector consumes the entire potion even for one point of healing.
			var per_pickup := mini(potion_heal,hp.y-1)
			if per_pickup <= 0: return false
			remaining_potions -= ceili(float(healed)/per_pickup)
			if remaining_potions < 0: return false
		for hit: Dictionary in row["recent"]:
			if hit["room"] > room_count or hit["time"] > frame["elapsed"] or hit["hp"]+hit["damage"] > hp.y: return false
			var present: bool = false
			for enemy: Dictionary in rooms[int(hit["room"])-1]["enemies"]:
				if enemy["kind"] == hit["kind"]:
					present = true
					break
			if not present: return false
	return true


static func describe(row: Dictionary,roster: Dictionary,moves: Dictionary) -> Dictionary:
	if row.is_empty(): return {}
	var recent: Array = row["recent"].duplicate(true)
	for hit: Dictionary in recent:
		hit["source"] = str(roster[hit["kind"]]["name"]).left(10)
		hit["label"] = str(moves[hit["move"]]["label"]).left(10)
	return {"actual_damage":row["damage"],"complete":row["complete"],"recent":recent}
