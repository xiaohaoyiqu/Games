extends "res://game/main/training_room.gd"

const ArtBridge = preload("res://game/presentation/fighter_animation_bridge.gd")
const LandingBridge = preload("res://game/presentation/landing_art_bridge.gd")
const PlayerFrames: SpriteFrames = preload("res://素材/运行/角色/绵羊剑士/frames.tres")
const GuardFrames: SpriteFrames = preload("res://素材/运行/角色/训练守卫/frames.tres")
const EffectFrames: SpriteFrames = preload("res://素材/运行/技能/战斗特效/frames.tres")
const PLAYER_SCALE: Dictionary = {
	&"idle": 0.1529745,
	&"walk": 0.1797005,
	&"hurt": 0.1894737,
	&"dash": 0.1907895,
	&"rise": 0.2402746,
	&"fall": 0.2402746,
	&"land": 0.2402746,
	&"dead": 0.1978022,
	&"slash_1.windup": 0.2410714,
	&"slash_1.active": 0.2410714,
	&"slash_1.recovery": 0.2410714,
	&"slash_2.windup": 0.1827411,
	&"slash_2.active": 0.1827411,
	&"slash_2.recovery": 0.1827411,
	&"slash_3.windup": 0.2446556,
	&"slash_3.active": 0.2446556,
	&"slash_3.recovery": 0.2446556,
	&"rising_slash.windup": 0.1963636,
	&"rising_slash.active": 0.1963636,
	&"rising_slash.recovery": 0.1963636
}
const GUARD_SCALE: float = 0.2631579
const RISING_SCALE: float = 0.2412281
const SPARK_SCALE: float = 0.2763158
const RISING_TAIL_DURATION: float = 0.12
var _landing_bridge: LandingBridge
var _rising_art: AnimatedSprite2D
var _sparks: Dictionary = {}


func _populate() -> void:
	super._populate()
	_attach_player()
	for fighter in fighters:
		if fighter != player:
			_attach_guard(fighter)
		_attach_spark(fighter)


func _attach_player() -> void:
	var sprite := _new_art(player, "CombatArt", PlayerFrames, 1.0)
	sprite.animation_changed.connect(_scale_player_sprite.bind(sprite))
	_landing_bridge = LandingBridge.new()
	_landing_bridge.name = "CombatArtBridge"
	player.add_child(_landing_bridge)
	_landing_bridge.bind(player, sprite)
	player.ground_landed.connect(_landing_bridge.on_ground_landed)
	_scale_player_sprite(sprite)
	_rising_art = _new_art(player, "RisingSlashArt", EffectFrames, RISING_SCALE)
	_rising_art.animation = &"rising_slash_fx"
	_rising_art.z_index = 1
	_rising_art.hide()


func _scale_player_sprite(sprite: AnimatedSprite2D) -> void:
	sprite.scale = Vector2.ONE * float(PLAYER_SCALE.get(sprite.animation, 1.0))


func _attach_guard(fighter: Node2D) -> void:
	var sprite := _new_art(fighter, "CombatArt", GuardFrames, GUARD_SCALE)
	# Sheet points to the left while the bridge's default art points right.
	sprite.flip_h = true
	var bridge := ArtBridge.new()
	bridge.name = "CombatArtBridge"
	fighter.add_child(bridge)
	bridge.bind(fighter, sprite)


func _new_art(parent: Node2D, art_name: String, frames: SpriteFrames, zoom: float) -> AnimatedSprite2D:
	var sprite := AnimatedSprite2D.new()
	sprite.name = art_name
	sprite.sprite_frames = frames
	sprite.scale = Vector2.ONE * zoom
	sprite.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR
	parent.add_child(sprite)
	return sprite


func _attach_spark(fighter: Node2D) -> void:
	var spark := _new_art(fighter, "HitSparkArt", EffectFrames, SPARK_SCALE)
	spark.z_index = 2
	spark.hide()
	spark.animation_finished.connect(spark.hide)
	_sparks[fighter] = spark


func _on_hit(source: Node2D, victim: Node2D, damage: int) -> void:
	super._on_hit(source, victim, damage)
	if entry_blocked or damage <= 0: return
	var spark: AnimatedSprite2D = _sparks.get(victim)
	if not is_instance_valid(spark): return
	# The parent appends its placeholder spark synchronously for this hit.
	effects.pop_back()
	spark.position = Vector2(0.0, -victim.height - 53.0)
	spark.stop()
	spark.show()
	spark.play(&"hit_spark")


func _physics_process(delta: float) -> void:
	super._physics_process(delta)
	if entry_blocked: return
	_update_rising_art()
	for spark: AnimatedSprite2D in _sparks.values():
		spark.speed_scale = 0.0 if paused else 1.0


func _update_rising_art() -> void:
	if not is_instance_valid(_rising_art): return
	if player.attack_name != "rising_slash" or player.hp <= 0:
		_rising_art.hide()
		return
	var elapsed_art: float = float(player.attack_time) - float(player.current_move["windup"])
	var duration: float = float(player.current_move["active"]) + RISING_TAIL_DURATION
	if elapsed_art < 0.0 or elapsed_art >= duration:
		_rising_art.hide()
		return
	var facing: float = player.facing
	_rising_art.position = Vector2(facing * 18.0, -player.height - 20.0)
	_rising_art.flip_h = facing < 0.0
	_rising_art.frame = mini(3, int(4.0 * elapsed_art / duration))
	_rising_art.show()


func reset_room() -> void:
	super.reset_room()
	if is_instance_valid(_landing_bridge):
		_landing_bridge.clear_landing()
	if is_instance_valid(_rising_art):
		_rising_art.hide()
	for spark: AnimatedSprite2D in _sparks.values():
		spark.stop()
		spark.hide()
