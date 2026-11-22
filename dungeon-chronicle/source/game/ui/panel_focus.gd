extends RefCounted

const MAX_BUTTONS: int=64
const LOGICAL_BOUNDS := Rect2(0,0,1280,720)

# Pure geometry metadata, rebuilt on panel refresh only. No nodes or callbacks retained.
static func describe(entries: Array) -> Dictionary:
	if entries.size()>MAX_BUTTONS:return {}
	var rows: Array[Dictionary]=[]
	var seen: Dictionary={}
	for value: Variant in entries:
		if not value is Dictionary:return {}
		var id: Variant=value.get("id")
		var rect: Variant=value.get("rect")
		var enabled: Variant=value.get("enabled")
		if not id is String or id.is_empty() or id.length()>96 or seen.has(id):return {}
		if not rect is Rect2 or not rect.position.is_finite() or not rect.size.is_finite() or rect.size.x<=0 or rect.size.y<=0 or not LOGICAL_BOUNDS.encloses(rect):return {}
		if not enabled is bool:return {}
		seen[id]=true
		if enabled:rows.append({"id":id,"rect":rect})
	rows.sort_custom(_before)
	var order: Array[String]=[]
	var links: Dictionary={}
	for row: Dictionary in rows:order.append(row["id"])
	for index in range(rows.size()):
		var id: String=rows[index]["id"]
		var neighbors: Dictionary={"next":order[(index+1)%order.size()],"previous":order[posmod(index-1,order.size())]}
		for side: int in [SIDE_LEFT,SIDE_TOP,SIDE_RIGHT,SIDE_BOTTOM]:neighbors[side]=_neighbor(rows,index,side)
		links[id]=neighbors
	return {"order":order,"links":links}

static func _before(a: Dictionary,b: Dictionary) -> bool:
	var first: Rect2=a["rect"];var second: Rect2=b["rect"]
	if first.position.y!=second.position.y:return first.position.y<second.position.y
	if first.position.x!=second.position.x:return first.position.x<second.position.x
	return str(a["id"])<str(b["id"])

# Prefer a button whose perpendicular interval overlaps, then center distance.
# Directional boundaries stay on the same control; sequential Tab navigation wraps.
static func _neighbor(rows: Array[Dictionary],index: int,side: int) -> String:
	var source: Rect2=rows[index]["rect"]
	var horizontal: bool=side in [SIDE_LEFT,SIDE_RIGHT]
	var sign: float=-1.0 if side in [SIDE_LEFT,SIDE_TOP] else 1.0
	var best: String=rows[index]["id"]
	var best_beam: int=2
	var best_distance: float=INF
	for other in range(rows.size()):
		if other==index:continue
		var target: Rect2=rows[other]["rect"]
		var offset: Vector2=target.get_center()-source.get_center()
		if (offset.x if horizontal else offset.y)*sign<=0.0:continue
		var overlap: bool
		if horizontal:overlap=target.position.y<source.end.y and target.end.y>source.position.y
		else:overlap=target.position.x<source.end.x and target.end.x>source.position.x
		var beam: int=0 if overlap else 1
		var distance: float=offset.length_squared()
		if beam<best_beam or (beam==best_beam and distance<best_distance):
			best=rows[other]["id"];best_beam=beam;best_distance=distance
	return best
