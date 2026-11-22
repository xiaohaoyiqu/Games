extends RefCounted

const Failure = preload("res://game/ui/configuration_failure.gd")
const FAILURE_NAME: String = "ConfigurationFailure"

static func allow(scene: Node,session: Node,requires_progression: bool=true) -> bool:
	# A failed instance stays failed even if an internal caller swaps rules later.
	# Recovery creates another scene; it never resumes a half-built one.
	if scene.get_node_or_null(FAILURE_NAME)!=null: return false
	if not requires_progression or session.configuration_ready(): return true
	block(scene,session,str(session.configuration_error()) if session.has_method("configuration_error") else "")
	return false

static func block(scene: Node,session: Node,reason: String="",allow_training: bool=true) -> void:
	if scene.get_node_or_null(FAILURE_NAME)!=null: return
	scene.set_process(false); scene.set_physics_process(false)
	scene.set_process_input(false); scene.set_process_unhandled_input(false)
	scene.set_process_unhandled_key_input(false); scene.set_process_shortcut_input(false)
	scene.process_mode = Node.PROCESS_MODE_DISABLED
	var failure := Failure.new(session,scene,reason,allow_training)
	failure.name = FAILURE_NAME
	scene.add_child(failure)
