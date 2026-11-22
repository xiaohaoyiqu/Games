extends Node2D

# Existing static commands are retained by CanvasItem between redraws.
func _draw() -> void:
	draw_rect(Rect2(0,0,1280,720),Color("101723"))
	# Recessed arches and staggered wall blocks sit behind the walkable plane.
	for row in range(7):
		for column in range(18):
			var x := column*78.0-(38 if row%2 else 0)
			var shade := 0.10+float((column*7+row*3)%5)*0.008
			draw_rect(Rect2(x,row*48+9,75,45),Color(shade,shade+0.015,shade+0.044))
	for center_x in [156,477,798,1119]:
		draw_circle(Vector2(center_x,181),86,Color("424351"))
		draw_rect(Rect2(center_x-86,181,172,167),Color("424351"))
		draw_circle(Vector2(center_x,183),69,Color("111c29"))
		draw_rect(Rect2(center_x-69,183,138,151),Color("111c29"))
		for bar in range(5):
			draw_line(Vector2(center_x-48+bar*24,152),Vector2(center_x-48+bar*24,333),Color("25313e"),5)
		draw_rect(Rect2(center_x-92,330,184,20),Color("5b5964"))
	draw_rect(Rect2(0,350,1280,320),Color("343b49"))
	for row in range(8):
		for column in range(17):
			var x := column*83.0-(41 if row%2 else 0)
			var y := 354.0+row*39
			var shade := 0.22+float((column*13+row*7)%6)*0.009
			draw_rect(Rect2(x+2,y+2,79,35),Color(shade,shade+0.025,shade+0.048))
			draw_line(Vector2(x+3,y+3),Vector2(x+79,y+3),Color(0.46,0.47,0.5,0.25),1)
	# Center training seal lies on the ground, so actors always draw above it.
	draw_set_transform(Vector2(650,520),0,Vector2(1,0.38))
	draw_arc(Vector2.ZERO,179,0,TAU,64,Color(0.68,0.61,0.45,0.35),3)
	draw_arc(Vector2.ZERO,158,0,TAU,64,Color(0.68,0.61,0.45,0.25),2)
	for i in range(8):
		var a := i*TAU/8
		draw_line(Vector2.from_angle(a)*152,Vector2.from_angle(a)*180,Color(0.68,0.61,0.45,0.3),3)
	draw_set_transform(Vector2.ZERO)
