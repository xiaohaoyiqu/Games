extends RefCounted

const SHEETS := {
	"currency-crystal": "res://素材/运行/道具/背包图标/currency-crystal.png",
	"weapons": "res://素材/运行/道具/背包图标/weapons.png",
	"armor": "res://素材/运行/道具/背包图标/armor.png",
	"charms-loot": "res://素材/运行/道具/背包图标/charms-loot.png",
}

# Regions include transparent padding. Square display bounds keep different shapes centered.
const REGIONS := {
	"rust_sword": ["weapons", Rect2(34, 103, 467, 835)],
	"echo_blade": ["weapons", Rect2(526, 78, 492, 873)],
	"light_sabre": ["weapons", Rect2(1049, 85, 462, 853)],
	"cloth_vest": ["armor", Rect2(129, 41, 393, 565)],
	"guard_vest": ["armor", Rect2(666, 46, 490, 558)],
	"scout_vest": ["armor", Rect2(61, 676, 465, 546)],
	"echo_plate": ["armor", Rect2(642, 670, 595, 562)],
	"plain_charm": ["charms-loot", Rect2(99, 23, 306, 492)],
	"wind_charm": ["charms-loot", Rect2(592, 18, 323, 503)],
	"ruin_charm": ["currency-crystal", Rect2(969, 167, 385, 687)],
	"gold": ["currency-crystal", Rect2(66, 262, 575, 515)],
	"relic_shard": ["charms-loot", Rect2(560, 578, 377, 408)],
	"healing_potion": ["charms-loot", Rect2(1098, 569, 342, 408)],
}

static var _cache: Dictionary = {}


static func texture_for(id: String) -> Texture2D:
	if not REGIONS.has(id):
		return null
	if not _cache.has(id):
		var entry: Array = REGIONS[id]
		var sheet := load(SHEETS[entry[0]]) as Texture2D
		if sheet == null:
			return null
		var texture := AtlasTexture.new()
		texture.atlas = sheet
		texture.region = entry[1]
		var side := maxf(texture.region.size.x, texture.region.size.y)
		var padding := Vector2(side, side) - texture.region.size
		texture.margin = Rect2(padding * 0.5, padding)
		texture.filter_clip = true
		_cache[id] = texture
	return _cache[id]
