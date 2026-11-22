extends RefCounted

const FLOOR=Rect2(40,350,1200,300)
const OUTLINE=Color(0.94,0.45,0.28,0.85)
const PROGRESS=Color("f2bc8b")

## Called only from the owner's _draw, under the same world transform as actors.
static func paint(canvas: CanvasItem,view: Dictionary,font: Font) -> void:
	if not view.get("visible",false):return
	if view["kind"]=="shot":
		var marker: Vector2=view["marker"]
		marker.x=clampf(marker.x,80,1200);marker.y=clampf(marker.y,155,610)
		var diamond:=PackedVector2Array([marker+Vector2(0,-15),marker+Vector2(17,0),marker+Vector2(0,15),marker+Vector2(-17,0)])
		canvas.draw_colored_polygon(diamond,Color("392e2a"))
		var outline: PackedVector2Array=diamond.duplicate();outline.append(diamond[0])
		canvas.draw_polyline(outline,OUTLINE,2)
		canvas.draw_string(font,marker+Vector2(-9,6),"射",HORIZONTAL_ALIGNMENT_LEFT,24,16,PROGRESS)
		canvas.draw_string(font,marker+Vector2(-32,-23),view["label"],HORIZONTAL_ALIGNMENT_LEFT,90,14,PROGRESS)
		canvas.draw_rect(Rect2(marker+Vector2(-32,22),Vector2(64,4)),Color("463d39"))
		canvas.draw_rect(Rect2(marker+Vector2(-32,22),Vector2(64*float(view["progress"]),4)),PROGRESS)
	elif view["kind"]=="melee":
		var bounds: Rect2=view["bounds"]
		var clipped: Rect2=bounds.intersection(FLOOR)
		if clipped.has_area():
			canvas.draw_rect(clipped,Color(0.85,0.25,0.19,0.18))
			canvas.draw_rect(clipped,OUTLINE,false,2)
			var bottom: Vector2=clipped.position+Vector2(0,clipped.size.y)
			canvas.draw_line(bottom,bottom+Vector2(clipped.size.x*float(view["progress"]),0),PROGRESS,4)
		# Distinct shape indicates direction or surrounding coverage, independent of hue.
		var origin: Vector2=view["origin"]
		if not FLOOR.has_point(origin):return
		var directions: Array[Vector2]=[Vector2(float(view["facing"]),0)]
		if view["omnidirectional"]:directions=[Vector2.RIGHT,Vector2.LEFT,Vector2.UP,Vector2.DOWN]
		for direction in directions:
			var tip: Vector2=origin+direction*19
			var side: Vector2=direction.orthogonal()*5
			canvas.draw_line(tip-direction*7+side,tip,PROGRESS,2)
			canvas.draw_line(tip-direction*7-side,tip,PROGRESS,2)
