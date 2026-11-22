@tool
extends EditorPlugin


class PartyContentExport:
	extends EditorExportPlugin
	const Content = preload("res://game/network/party_content.gd")

	func _get_name() -> String:
		return "DungeonPartyContent"

	func _export_begin(_features: PackedStringArray,_debug: bool,_path: String,_flags: int) -> void:
		var stamp := Content.source_fingerprint()
		if stamp.is_empty():
			get_export_platform().add_message(EditorExportPlatform.EXPORT_MESSAGE_ERROR,"Party content","Cannot read gameplay sources for the party fingerprint.")
			return
		add_file(Content.PACKED_STAMP,stamp.hex_decode(),false)


var _exporter: PartyContentExport

func _enter_tree() -> void:
	_exporter = PartyContentExport.new()
	add_export_plugin(_exporter)


func _exit_tree() -> void:
	remove_export_plugin(_exporter)
	_exporter = null
