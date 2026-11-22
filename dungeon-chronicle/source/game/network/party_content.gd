extends RefCounted

## Fixed paths only. Personal profile data and presentation assets are excluded.
const Trace = preload("res://game/diagnostics/frame_trace.gd")
const FILES: Array[String] = [
	"res://data/dungeons/difficulties.json","res://data/dungeons/echo_ruins.json",
	"res://data/enemies/ruins_roster.json","res://data/equipment/items.json",
	"res://data/quests/town_quests.json","res://data/skills/training_moves.json",
	"res://game/actors/fighter.gd","res://game/combat/arrow.gd","res://game/combat/battle_review.gd",
	"res://game/combat/combat_rules.gd","res://game/combat/combat_values.gd","res://game/combat/ground_motion.gd",
	"res://game/combat/move_rules.gd","res://game/combat/skill_catalog.gd",
	"res://game/configuration/configuration_issue.gd","res://game/configuration/bounded_json.gd","res://game/input/action_intent.gd",
	"res://game/equipment/profile_rules.gd","res://game/equipment/progression_catalog.gd","res://game/levels/run_progress.gd",
	"res://game/levels/dungeon_catalog.gd","res://game/levels/dungeon_exit.gd","res://game/main/dungeon.gd",
	"res://game/main/combat_room.gd","res://game/navigation/scene_entry.gd","res://game/services/session.gd","res://project.godot",
	"res://game/network/party_character.gd","res://game/network/party_protocol.gd",
	"res://game/network/party_state.gd","res://game/network/enet_party.gd","res://game/network/party_content.gd",
	"res://game/network/battle_frame.gd","res://game/network/party_hit_review.gd","res://game/network/party_run.gd","res://game/network/party_service.gd",
	"res://game/main/party_lobby.gd","res://game/main/party_dungeon.gd","res://game/ui/party_hud.gd",
	"res://game/diagnostics/frame_trace.gd"]
const MAX_FILE_BYTES: int = 262144
const PACKED_STAMP: String = "res://data/party-content.sha256"


static func fingerprint() -> String:
	var measured: int = Trace.begin()
	# Exported packs contain compiled scripts and project.binary.
	var result: String = _packed_fingerprint() if FileAccess.file_exists("res://project.binary") else source_fingerprint()
	Trace.end("content_fingerprint",measured)
	return result


static func _packed_fingerprint() -> String:
	var file := FileAccess.open(PACKED_STAMP,FileAccess.READ)
	if file == null: return ""
	var bytes := file.get_buffer(32) if file.get_length() == 32 else PackedByteArray()
	file.close()
	return bytes.hex_encode() if bytes.size() == 32 else ""


static func source_fingerprint() -> String:
	var context := HashingContext.new()
	context.start(HashingContext.HASH_SHA256)
	for path in FILES:
		var file := FileAccess.open(path,FileAccess.READ)
		if file == null: return ""
		var length := file.get_length()
		if length > MAX_FILE_BYTES: file.close(); return ""
		var bytes := file.get_buffer(length)
		var complete := bytes.size() == length and file.get_length() == length
		file.close()
		if not complete: return ""
		context.update((path+"\n"+str(length)+"\n").to_utf8_buffer())
		context.update(bytes)
	return context.finish().hex_encode()
