extends Node2D

const BACKDROPS: Array[Texture2D] = [
	preload("res://素材/运行/场景/林间回响遗迹/01-forest-edge.png"),
	preload("res://素材/运行/场景/林间回响遗迹/02-overgrown-camp.png"),
	preload("res://素材/运行/场景/林间回响遗迹/03-vine-sanctuary.png"),
	preload("res://素材/运行/场景/林间回响遗迹/04-echo-grove.png"),
]
const PROPS: Texture2D = preload("res://素材/运行/场景/林间回响遗迹/forest-props.png")
const CAMP_ROOM: int = 1
const CAMP_FOOTINGS: Texture2D = preload("res://素材/运行/场景/林间回响遗迹/camp-footings.png")
const CAMP_FOOTING_REGIONS := [Rect2(106, 318, 848, 150), Rect2(1219, 305, 832, 168)]
const CAMP_FOOTING_BOUNDS := [Rect2(213,489,119,31), Rect2(966,470,116,31)]
const CAMP_FOOTING_TINT := Color(0.86,0.94,0.90,0.76)
enum GateView { SHALLOW, DEEP }
const GATE_TEXTURES: Array[Texture2D] = [
	preload("res://素材/运行/场景/林间回响遗迹/forest-gate-shallow.png"),
	preload("res://素材/运行/场景/林间回响遗迹/forest-gate-deep.png"),
]
const GATE_REGIONS := [
	[Rect2(232, 41, 504, 736), Rect2(1161, 41, 504, 736)],
	[Rect2(116, 23, 654, 841), Rect2(1003, 23, 654, 841)],
]
const WAYSTONE_VARIANTS: Texture2D = preload("res://素材/运行/场景/林间回响遗迹/forest-waystone-variants.png")
const WAYSTONE_REGIONS := [
	Rect2(292, 43, 292, 810),
	Rect2(398, 19, 293, 682),
	Rect2(1492, 77, 299, 624),
]
const WAYSTONE_HEIGHTS := [206.5, 196.0, 178.0]
# The stones remain on the two arena footprints as the room artwork changes.
const WAYSTONES_BY_ROOM := [[0, 1], [1, 2], [2, 0], [0, 2]]
const WAYSTONE_LIGHT := [
	Color(0.99, 1.0, 0.94),
	Color(0.92, 0.97, 0.99),
	Color(0.88, 0.95, 0.96),
	Color(1.0, 0.96, 0.89),
]
const SCREEN := Rect2(0, 0, 1280, 720)
# Each room pairs its arrival route with a separately authored view of its onward gate.
const ROOM_GATE_VIEWS := [
	[GateView.SHALLOW, GateView.DEEP],
	[GateView.DEEP, GateView.SHALLOW],
	[GateView.SHALLOW, GateView.DEEP],
	[GateView.DEEP, GateView.SHALLOW],
]
const ENTRY_GATE_BOUNDS := [Rect2(54, 367, 109, 160), Rect2(46, 367, 124, 160)]
const EXIT_GATE_BOUNDS := [Rect2(1127, 404, 115, 169), Rect2(1117, 404, 130, 169)]
const OPENING_CENTER := [0.48, 0.58]

static var _shadow_texture: GradientTexture2D
var _room_index: int = 0


func show_room(index: int) -> void:
	var next_index := clampi(index, 0, BACKDROPS.size() - 1)
	if next_index != _room_index:
		_room_index = next_index
		queue_redraw()


func _draw() -> void:
	draw_texture_rect(BACKDROPS[_room_index], SCREEN, false)
	var entry: int = ROOM_GATE_VIEWS[_room_index][0]
	_draw_gate(self, entry, ENTRY_GATE_BOUNDS[entry], true, Color(0.69, 0.75, 0.70))
	if _room_index == CAMP_ROOM:
		for index in range(CAMP_FOOTING_REGIONS.size()):
			draw_texture_rect_region(CAMP_FOOTINGS, CAMP_FOOTING_BOUNDS[index], CAMP_FOOTING_REGIONS[index], CAMP_FOOTING_TINT)


static func make_waystone() -> Sprite2D:
	var stone := Sprite2D.new()
	stone.region_enabled = true
	stone.region_filter_clip_enabled = true
	var shadow := Sprite2D.new()
	shadow.name = "GroundContact"
	shadow.texture = contact_shadow_texture()
	shadow.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR
	shadow.show_behind_parent = true
	stone.add_child(shadow)
	return stone


static func style_waystone(stone: Sprite2D, room: int, slot: int) -> void:
	var palette_index := clampi(room, 0, WAYSTONES_BY_ROOM.size() - 1)
	var variant: int = WAYSTONES_BY_ROOM[palette_index][slot]
	var region: Rect2 = WAYSTONE_REGIONS[variant]
	stone.texture = PROPS if variant == 0 else WAYSTONE_VARIANTS
	stone.region_rect = region
	var height: float = WAYSTONE_HEIGHTS[variant]
	var width := minf(region.size.x * height / region.size.y, 83.0)
	stone.scale = Vector2(width, height) / region.size
	stone.offset = Vector2(0.0, -region.size.y * 0.5 + 5.0 / stone.scale.y)
	stone.self_modulate = WAYSTONE_LIGHT[palette_index]
	var shadow := stone.get_node("GroundContact") as Sprite2D
	shadow.position = Vector2(2.0, 3.0) / stone.scale
	shadow.scale = Vector2(72.0, 24.0) / (shadow.texture.get_size() * stone.scale)


static func contact_shadow_texture() -> GradientTexture2D:
	if _shadow_texture == null:
		var gradient := Gradient.new()
		gradient.offsets = PackedFloat32Array([0.0, 0.42, 1.0])
		gradient.colors = PackedColorArray([
			Color(0.105, 0.093, 0.061, 0.48),
			Color(0.105, 0.093, 0.061, 0.34),
			Color(0.105, 0.093, 0.061, 0.0),
		])
		_shadow_texture = GradientTexture2D.new()
		_shadow_texture.gradient = gradient
		_shadow_texture.width = 96
		_shadow_texture.height = 32
		_shadow_texture.fill = GradientTexture2D.FILL_RADIAL
		_shadow_texture.fill_from = Vector2(0.5, 0.5)
		_shadow_texture.fill_to = Vector2(1.0, 0.5)
	return _shadow_texture


static func _exit_view(room_index: int) -> int:
	return ROOM_GATE_VIEWS[clampi(room_index, 0, ROOM_GATE_VIEWS.size() - 1)][1]


static func exit_threshold(room_index: int) -> Vector2:
	var view := _exit_view(room_index)
	var bounds: Rect2 = EXIT_GATE_BOUNDS[view]
	return Vector2(bounds.position.x + bounds.size.x * float(OPENING_CENTER[view]), bounds.end.y)


static func draw_exit(target: CanvasItem, room_index: int, open: bool, show_guidance: bool = true) -> void:
	var view := _exit_view(room_index)
	var bounds: Rect2 = EXIT_GATE_BOUNDS[view]
	_draw_gate(target, view, bounds, open, Color.WHITE)
	if not open or not show_guidance:
		return
	# The cue points through the aperture, from its visible ground approach.
	var tip := exit_threshold(room_index) + Vector2(0.0, 8.0)
	var forward := Vector2(-0.26 if view == GateView.DEEP else 0.26, -1.0).normalized()
	var shoulder := tip - forward * 9.0
	var side := Vector2(-forward.y, forward.x) * 17.0
	target.draw_polyline(PackedVector2Array([shoulder - side, tip, shoulder + side]), Color("9cd2bb"), 2.0, true)


static func _draw_gate(target: CanvasItem, view: int, bounds: Rect2, open: bool, tint: Color) -> void:
	var shadow_width := bounds.size.x * 0.93
	var contact := Rect2(bounds.get_center().x - shadow_width * 0.5, bounds.end.y - 15.0, shadow_width, 28.0)
	target.draw_texture_rect(contact_shadow_texture(), contact, false)
	var frame: Rect2 = GATE_REGIONS[view][1 if open else 0]
	target.draw_texture_rect_region(GATE_TEXTURES[view], bounds, frame, tint)
