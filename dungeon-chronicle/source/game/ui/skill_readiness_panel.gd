extends Control

const Readiness=preload("res://game/training/skill_readiness.gd")
const MoveRules=preload("res://game/combat/move_rules.gd")
const Actions=preload("res://game/input/actions.gd")
var room: Node2D
var labels: Array[Label]=[]
var view: Dictionary={}
var rebuilds: int=0
var _skill_binding: String
var _skill_valid: bool=false
var _alive: bool=false
var _attacking: bool=false
var _hurt: bool=false
var _dashing: bool=false
var _tenths: int=-2
var _buffered: bool=false
var _starts: int=-1
var _incomplete: bool=false

func _ready() -> void:
	position=Vector2(770,606);size=Vector2(480,54);mouse_filter=Control.MOUSE_FILTER_IGNORE
	# Entry move data is fixed for this scene; no hot reload or file reads here.
	_skill_binding = Actions.binding_text("skill")
	_skill_valid=MoveRules.valid(room.player.moves.get("rising_slash"))
	for index in range(2):
		var label:=Label.new();label.position=Vector2(0,index*26);label.size=Vector2(480,24)
		label.clip_text=true;label.text_overrun_behavior=TextServer.OVERRUN_TRIM_ELLIPSIS
		label.mouse_filter=Control.MOUSE_FILTER_PASS;label.focus_mode=Control.FOCUS_NONE
		label.add_theme_font_override("font",room.ui_font);label.add_theme_font_size_override("font_size",14 if index==0 else 13)
		label.add_theme_color_override("font_color",Color("e2bc83") if index==0 else Color("b8bfca"))
		label.add_theme_color_override("font_outline_color",Color("101622"));label.add_theme_constant_override("outline_size",3)
		add_child(label);labels.append(label)
	_refresh_if_changed()

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready():
		var binding := Actions.binding_text("skill")
		if binding != _skill_binding:
			_skill_binding = binding
			_update_skill_caption()

func _update_skill_caption() -> void:
	labels[0].text = _skill_binding+" 破空斩 · "+str(view["text"])
	labels[0].tooltip_text = labels[0].text

func _process(_delta: float) -> void:
	if not is_instance_valid(room) or room.is_queued_for_deletion() or not room.is_inside_tree() or get_tree().current_scene!=room:
		visible=false;return
	visible=not room.paused and not room.fixed_trial_running()
	if visible:_refresh_if_changed()

func _refresh_if_changed() -> void:
	var player: Node2D=room.player
	var alive: bool=player.hp>0;var attacking: bool=not player.attack_name.is_empty()
	var hurt: bool=player.stun_time>0;var dashing: bool=player.dash_time>0
	var tenths: int=Readiness.cooldown_tenths(float(player.skill_cooldown))
	var buffered: bool=player.action_intent.pending()==&"skill"
	var starts: int=room.skill_start_count;var incomplete: bool=room.skill_count_incomplete
	if alive==_alive and attacking==_attacking and hurt==_hurt and dashing==_dashing and tenths==_tenths and buffered==_buffered and starts==_starts and incomplete==_incomplete:return
	_alive=alive;_attacking=attacking;_hurt=hurt;_dashing=dashing;_tenths=tenths
	_buffered=buffered;_starts=starts;_incomplete=incomplete
	view=Readiness.describe(alive,attacking,hurt,dashing,float(player.skill_cooldown),buffered,_skill_valid,starts,incomplete)
	_update_skill_caption()
	labels[1].text = str(view["note"])
	labels[1].tooltip_text = labels[1].text
	rebuilds+=1
