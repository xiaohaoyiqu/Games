extends RefCounted

## One process-local UI font, acquired on the main thread by scene setup.
## Treat this shared resource as read-only; sizes/colors belong to draw calls
## or per-control themes. The initial system lookup is still synchronous.
static var _font: Font


static func acquire() -> Font:
	if _font != null:
		return _font
	if OS.has_feature("web"):
		_font = load(ProjectSettings.get_setting_with_override("gui/theme/custom_font")) as Font
	else:
		var system_font := SystemFont.new()
		system_font.font_names = PackedStringArray(["Microsoft YaHei UI","Microsoft YaHei","Arial"])
		_font = system_font
	return _font
