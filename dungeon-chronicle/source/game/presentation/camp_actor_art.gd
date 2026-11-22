extends Resource

const Bridge = preload("res://game/presentation/fighter_animation_bridge.gd")
const LandingBridge = preload("res://game/presentation/landing_art_bridge.gd")
const SheepArt = preload("res://game/presentation/sheep_swordsman_art.gd")
const HERO_FRAMES := "res://素材/运行/角色/绵羊剑士/v2/frames-v2.tres"
const BULWARK_FRAMES := "res://素材/运行/角色/重甲卫士/frames.tres"
const BULWARK_SCALE: float = 0.415


var hero_frames: SpriteFrames = _load_frames(HERO_FRAMES)
var bulwark_frames: SpriteFrames = _load_frames(BULWARK_FRAMES)


static func _load_frames(path: String) -> SpriteFrames:
	if not ResourceLoader.exists(path, "SpriteFrames"):
		return null
	return ResourceLoader.load(path, "SpriteFrames") as SpriteFrames


static func clear_landing(fighter: Node2D) -> void:
	var bridge := fighter.get_node_or_null("CampArtBridge") as LandingBridge
	if bridge != null: bridge.clear_landing()


static func clear_style(fighter: Node2D) -> void:
	fighter.terrain_shadow = null
	_detach(fighter)
	fighter.queue_redraw()


func apply(fighter: Node2D, contact_shadow: Texture2D, landing_interrupted: Callable = Callable()) -> void:
	fighter.terrain_shadow = contact_shadow
	if fighter.has_node("CampArtBridge") or (not fighter.is_player and fighter.enemy_kind != "bulwark"):
		fighter.queue_redraw()
		return

	var player_art: bool = fighter.is_player
	var frames := hero_frames if player_art else bulwark_frames
	if frames == null:
		return
	var body := AnimatedSprite2D.new()
	body.name = "CampBody"
	body.sprite_frames = frames
	body.animation = &"idle"
	body.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
	body.self_modulate = Color(0.96, 1.0, 0.93) if player_art else Color(0.91, 0.96, 0.92)
	fighter.add_child(body)
	var adapter: Bridge
	if player_art:
		body.frame_changed.connect(SheepArt.apply_scale.bind(body))
		body.animation_changed.connect(SheepArt.apply_scale.bind(body))
		SheepArt.apply_scale(body)
		var landing := LandingBridge.new()
		landing.landing_interrupted = landing_interrupted
		fighter.ground_landed.connect(landing.on_ground_landed)
		adapter = landing
	else:
		body.scale = Vector2.ONE * BULWARK_SCALE
		adapter = Bridge.new()
	adapter.name = "CampArtBridge"
	fighter.add_child(adapter)
	if not adapter.bind(fighter, body):
		_detach(fighter)
		return
	fighter.sprite_health_bar_height = 145.0 if not player_art else 0.0
	fighter.sprite_attack_effect = true
	fighter.queue_redraw()


static func _detach(fighter: Node2D) -> void:
	var adapter := fighter.get_node_or_null("CampArtBridge") as Bridge
	if adapter != null:
		if adapter is LandingBridge:
			var landing := adapter as LandingBridge
			fighter.ground_landed.disconnect(landing.on_ground_landed)
		adapter.unbind()
		fighter.remove_child(adapter)
		adapter.queue_free()
	var body := fighter.get_node_or_null("CampBody") as AnimatedSprite2D
	if body != null:
		fighter.remove_child(body)
		body.queue_free()
	fighter.sprite_health_bar_height = 0.0
	fighter.sprite_attack_effect = false
