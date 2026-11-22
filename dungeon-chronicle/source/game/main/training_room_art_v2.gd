extends "res://game/main/training_room_art.gd"

const RevisedFrames: SpriteFrames = preload("res://素材/运行/角色/绵羊剑士/v2/frames-v2.tres")
const SheepArt = preload("res://game/presentation/sheep_swordsman_art.gd")


func _attach_player() -> void:
	super._attach_player()
	var body := player.get_node("CombatArt") as AnimatedSprite2D
	body.frame_changed.connect(_scale_player_sprite.bind(body))
	body.sprite_frames = RevisedFrames
	_landing_bridge.refresh_animations()
	_landing_bridge.synchronize()
	_scale_player_sprite(body)


func _scale_player_sprite(sprite: AnimatedSprite2D) -> void:
	if not SheepArt.apply_scale(sprite):
		super._scale_player_sprite(sprite)
