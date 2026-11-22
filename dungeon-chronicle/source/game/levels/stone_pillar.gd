extends Node2D

func _draw() -> void:
	draw_set_transform(Vector2.ZERO,0,Vector2(1,0.32))
	draw_circle(Vector2(7,0),51,Color(0.01,0.015,0.025,0.4))
	draw_set_transform(Vector2.ZERO)
	draw_colored_polygon(PackedVector2Array([Vector2(-37,-10),Vector2(26,-10),Vector2(41,1),Vector2(31,12),Vector2(-31,12),Vector2(-43,2)]),Color("292e3c"))
	draw_rect(Rect2(-30,-32,60,26),Color("535363"))
	draw_rect(Rect2(-35,-37,70,10),Color("777184"))
	draw_rect(Rect2(-24,-203,48,166),Color("555766"))
	draw_rect(Rect2(11,-203,13,166),Color("363c4d"))
	draw_rect(Rect2(-21,-203,7,166),Color("747080"))
	for y in [-192,-152,-112,-72]:
		draw_line(Vector2(-24,y),Vector2(23,y),Color("353c4e"),2)
	draw_line(Vector2(-4,-184),Vector2(5,-166),Color("303749"),2)
	draw_line(Vector2(5,-166),Vector2(-3,-147),Color("303749"),2)
	draw_rect(Rect2(-33,-216,66,17),Color("797382"))
	draw_rect(Rect2(-39,-224,78,10),Color("474d60"))
	draw_line(Vector2(-36,-224),Vector2(36,-224),Color("aaa0a0"),3)
	draw_rect(Rect2(-7,-131,14,35),Color("303849"))
	draw_line(Vector2(0,-124),Vector2(0,-103),Color("b9c2b1"),2)
	draw_line(Vector2(-4,-116),Vector2(4,-110),Color("b9c2b1"),2)
