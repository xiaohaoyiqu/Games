extends RefCounted

const ATLAS_SCALE: Dictionary = {
	"res://素材/运行/角色/绵羊剑士/v2/standing-idle.png": 0.18815331,
	"res://素材/运行/角色/绵羊剑士/v2/slash-1.png": 0.23946785,
	"res://素材/运行/角色/绵羊剑士/v2/slash-2.png": 0.23632385,
	"res://素材/运行/角色/绵羊剑士/v2/slash-3.png": 0.24,
	"res://素材/运行/角色/绵羊剑士/v2/rising-slash.png": 0.24,
	"res://素材/运行/角色/绵羊剑士/walk.png": 0.1797005,
	"res://素材/运行/角色/绵羊剑士/hurt.png": 0.1894737,
	"res://素材/运行/角色/绵羊剑士/dash.png": 0.1907895,
	"res://素材/运行/角色/绵羊剑士/jump_land.png": 0.2402746,
	"res://素材/运行/角色/绵羊剑士/knockdown_death.png": 0.1978022
}


static func apply_scale(sprite: AnimatedSprite2D) -> bool:
	var frames := sprite.sprite_frames
	if frames == null or not frames.has_animation(sprite.animation) or sprite.frame >= frames.get_frame_count(sprite.animation):
		return false
	var texture := frames.get_frame_texture(sprite.animation, sprite.frame) as AtlasTexture
	if texture == null or texture.atlas == null or not ATLAS_SCALE.has(texture.atlas.resource_path):
		return false
	sprite.scale = Vector2.ONE * float(ATLAS_SCALE[texture.atlas.resource_path])
	return true
