extends Control

const Actions = preload("res://game/input/actions.gd")
var _navigation_hint: String
var _retreat_note: String
var town: Node2D
var mode: String = "inventory"
var selected: String = "rust_sword"
var result_note: String = ""
var action_buttons: Dictionary = {}
var selected_difficulty: String = "normal"
var dungeon_preview: Dictionary = {}
var portal_failure: Label
var departure_quest_hint: Label
var _quest_guide: RefCounted
var difficulty_options: Dictionary = {}
var _entry_pending: bool = false
var preview_operation: String="equip"
var equipment_preview: Dictionary={}
var preview_rebuilds: int=0
var _preview_model: RefCounted
var _equip_preview: Dictionary={}
var _upgrade_preview: Dictionary={}
var _view_profile: Dictionary={}
var _focus_key: String=""
var item_buttons: Dictionary={}
var upgrade_cost_preview: Dictionary={}
var effects_dialog: AcceptDialog
var _dialog_focus_key: String = "action:effects"
const EquipmentEffects = preload("res://game/equipment/equipment_effects.gd")
const Pages=preload("res://game/equipment/equipment_pages.gd")
const InventoryIcons = preload("res://game/ui/inventory_icons.gd")
const FocusPlan=preload("res://game/ui/panel_focus.gd")
const QuestGuide=preload("res://game/quests/quest_guide.gd")
const Practice = preload("res://game/training/guided_practice.gd")
var _button_theme := Theme.new()
var close_button: Button
var focus_plan: Dictionary={}
var _focus_buttons: Dictionary={}
var _last_focus_key: String=""
var _view_generation: int=0
var _view_revision: int=-1
var _view_locked: bool=false
var _view_tracked_quest: String = ""
const INVENTORY_PAGE_SIZE: int=7
const SHOP_PAGE_SIZE: int=2
const QUEST_ROW_Y: int = 224
const QUEST_ROW_STEP: int = 124
var inventory_filter: String = "all"
var inventory_ids: Array = []
var inventory_page: int=0
var shop_page: int=0
var inventory_view: Dictionary={}
var shop_view: Dictionary={}
var shop_ids: Array[String]=[]
var _purchase_blockers: Dictionary = {}
const SLOT_NAMES := {"weapon":"武器","armor":"护甲","charm":"护符"}


func _ready() -> void:
	size = Vector2(1280,720)
	_prepare_button_theme()
	_preview_model=preload("res://game/equipment/equipment_preview.gd").new(Session.rules)
	_quest_guide=QuestGuide.new(Session.rules)
	if mode in ["inventory","forge"]:
		texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
		effects_dialog=preload("res://game/ui/equipment_effects_dialog.gd").new()
		effects_dialog.ui_font=town.ui_font;add_child(effects_dialog)
		effects_dialog.visibility_changed.connect(_effects_visibility_changed)
	refresh()


func _button(caption: String,point: Vector2,width: float,callback: Callable,enabled: bool=true) -> Button:
	var button := Button.new()
	button.clip_text=true
	button.text_overrun_behavior=TextServer.OVERRUN_TRIM_ELLIPSIS
	button.position = point
	button.size = Vector2(width,39)
	button.text = caption
	button.disabled = not enabled
	button.theme = _button_theme
	button.pressed.connect(_invoke_button.bind(button,callback,_view_generation))
	add_child(button)
	return button


func _prepare_button_theme() -> void:
	_button_theme.set_font("font","Button",town.ui_font)
	_button_theme.set_font_size("font_size","Button",17)
	for state in ["normal","hover","pressed","focus","disabled"]:
		var style := StyleBoxFlat.new()
		style.bg_color = Color("334453") if state in ["hover","focus"] else Color("223140")
		style.border_color = Color("b4aa88") if state == "focus" else Color("5b6873")
		style.set_border_width_all(1)
		_button_theme.set_stylebox(state,"Button",style)
	if mode in ["inventory","forge"]:
		_set_inventory_styles(&"InventoryRow", false)
		_set_inventory_styles(&"SelectedInventoryRow", true)


func _label(caption: String,point: Vector2,width: float,font_size: int=15) -> Label:
	var label:=Label.new()
	label.clip_text=true;label.text_overrun_behavior=TextServer.OVERRUN_TRIM_ELLIPSIS
	label.position=point;label.size=Vector2(width,32 if font_size>=24 else 24);label.text=caption;label.tooltip_text=caption
	label.mouse_filter=Control.MOUSE_FILTER_PASS
	label.add_theme_font_override("font",town.ui_font);label.add_theme_font_size_override("font_size",font_size)
	add_child(label)
	return label


func refresh(reuse_equipment: bool=false) -> void:
	if not _owns_panel():return
	var reuse_preview := reuse_equipment and _view_matches()
	_view_profile=Session.profile
	if _focus_key.is_empty():_focus_key=_last_focus_key
	_view_generation+=1
	_view_revision=int(Session.profile["revision"]);_view_locked=_growth_locked()
	if mode in ["quests","portal"]: _view_tracked_quest = Session.tracked_quest_id
	_focus_buttons.clear();focus_plan.clear()
	for child in get_children():
		if child==effects_dialog:continue
		remove_child(child)
		child.queue_free()
	action_buttons.clear(); item_buttons.clear(); portal_failure = null
	departure_quest_hint = null
	close_button=_button("关闭",Vector2(1040,127),135,town.close_panel)
	if mode == "quests":
		var near: bool = town.player.position.distance_to(town.places["quests"]) < 120
		var revision_full: bool = Session.rules.at_revision_limit(Session.profile)
		var row: int = 0
		for id: String in Session.rules.quest_defs:
			var state: int = int(Session.profile["quests"][id]["state"])
			var supplemental: bool = Session.rules.elite_armor_pending(Session.profile,id)
			var claimable: bool = state == 2 or supplemental
			var command := "claim" if claimable else "accept"
			var caption: String = "补领装备" if supplemental else {0:"接受委托",1:"进行中",2:"领取奖励",3:"已完成"}[state]
			var blocker := ""
			if revision_full and (state == 0 or claimable): blocker = "存档修订号已达上限"
			elif state == 2: blocker = Session.rules.quest_reward_capacity_error(Session.profile,Session.rules.quest_defs[id])
			var enabled := near and (state == 0 or claimable) and not _view_locked and blocker.is_empty()
			action_buttons[id] = _button(caption if blocker.is_empty() else blocker,Vector2(941,QUEST_ROW_Y+4+row*QUEST_ROW_STEP),198,func(): _act(command,id),enabled)
			if not blocker.is_empty(): action_buttons[id].tooltip_text = caption+" · "+blocker
			if state in [1,2]:
				var tracking: bool = _view_tracked_quest == id
				var track_caption: String = "显示全部目标" if tracking else "跟踪此委托"
				var track_button := _button(track_caption,Vector2(941,QUEST_ROW_Y+50+row*QUEST_ROW_STEP),198,_toggle_tracked_quest.bind(id))
				track_button.tooltip_text = "取消跟踪，恢复显示全部委托目标。" if tracking else "目标栏仅显示此委托。"
				action_buttons["track_"+id] = track_button
			var reward_item: String = _claimed_reward_item(id)
			if not reward_item.is_empty():
				var reward_button := _button("查看奖励装备",Vector2(941,QUEST_ROW_Y+50+row*QUEST_ROW_STEP),198,_open_quest_reward.bind(id))
				reward_button.tooltip_text = "打开背包并选中「%s」查看对比；装备需另行确认。" % Session.rules.catalog[reward_item]["name"]
				action_buttons["reward_"+id] = reward_button
			row += 1
	elif mode in ["inventory","forge"]:
		if not reuse_preview:
			shop_ids=Session.rules.shop_ids()
			if mode=="forge":
				_purchase_blockers=Session.rules.purchase_blockers(Session.profile,shop_ids)
		inventory_ids = Pages.matching(Session.profile["owned"],Session.rules.catalog,inventory_filter)["ids"]
		inventory_view=Pages.describe(inventory_ids,inventory_page,INVENTORY_PAGE_SIZE)
		shop_view=Pages.describe(shop_ids,shop_page,SHOP_PAGE_SIZE)
		inventory_page=int(inventory_view["page"]);shop_page=int(shop_view["page"])
		if not Session.rules.catalog.has(selected) or (not Session.profile["owned"].has(selected) and (mode!="forge" or selected not in shop_ids)):selected="rust_sword"
		if mode!="forge":preview_operation="equip"
		if not reuse_preview or _equip_preview.get("item")!=selected:
			_equip_preview=_preview_model.describe(Session.profile,selected,"equip")
			if mode=="forge":_upgrade_preview=_preview_model.describe(Session.profile,selected,"upgrade")
			upgrade_cost_preview=Session.rules.upgrade_cost(int(_equip_preview["rank"]))
			preview_rebuilds+=1
		equipment_preview=_upgrade_preview if preview_operation=="upgrade" else _equip_preview
		action_buttons["effects"]=_button("查看数值作用与代价",Vector2(935,410 if mode=="forge" else 280),220,_open_effects)
		action_buttons["effects"].tooltip_text="查看各招伤害、技能冷却、给定承伤和行走速度；不执行购买、装备或强化。"
		var selected_item: Dictionary=Session.rules.catalog[selected]
		var can_locate: bool = _selected_is_outside_inventory_page()
		var title_width: float = 190.0 if can_locate else 365.0
		_label(str(selected_item["name"])+" +%d" % int(equipment_preview["rank"])+(" · 未获得" if not equipment_preview["owned"] else ""),Vector2(547,205),title_width,23)
		_label(str(SLOT_NAMES[selected_item["slot"]])+" / "+str(selected_item["source"]),Vector2(547,238),365,15)
		if can_locate:
			var locate := _button("定位所选装备",Vector2(744,212),167,_locate_selected_equipment)
			locate.tooltip_text = "在背包中找到当前对比的装备；跨部位时切回全部。"
			action_buttons["locate_selected"] = locate
		var equipped_id: String = Session.profile["equipped"][selected_item["slot"]]
		var equipped_item: Dictionary = Session.rules.catalog[equipped_id]
		var equipped_rank: int = int(Session.profile["upgrades"].get(equipped_id,0))
		var equipped_heading := _label("当前：%s +%d" % [equipped_item["name"],equipped_rank],Vector2(510,320),184,16)
		equipped_heading.name = "EquippedComparison"
		equipped_heading.add_theme_color_override("font_color",Color("a7cbc3"))
		var filters: Array[String] = ["all","weapon","armor","charm"]
		for index in range(filters.size()):
			var slot: String = filters[index]
			var name: String = "全部" if slot == "all" else str(SLOT_NAMES[slot])
			var active := inventory_filter == slot
			var filter_button := _button(name+(" ·" if active else ""),Vector2(109+83*index,205),77,_set_inventory_filter.bind(slot))
			filter_button.theme_type_variation = &"SelectedInventoryRow" if active else &"InventoryRow"
			filter_button.tooltip_text = "背包分类：%s%s。切换只筛选列表，保留当前对比。" % [name,"（当前）" if active else ""]
			action_buttons["filter_"+slot] = filter_button
		if inventory_ids.is_empty(): _label("此分类暂无装备",Vector2(109,272),326,17)
		var row: int=0
		for id: String in inventory_view["ids"]:
			var item: Dictionary=Session.rules.catalog[id];var rank: int=int(Session.profile["upgrades"].get(id,0))
			var caption: String=str(item["name"])+(" +%d" % rank if rank>0 else "")
			if Session.profile["equipped"][item["slot"]]==id:caption+=" · 已装备"
			if id == selected: caption += " · 对比中"
			var row_button := _button(caption,Vector2(109,249+row*42),326,_select_equipment.bind(id))
			row_button.theme_type_variation = &"SelectedInventoryRow" if id == selected else &"InventoryRow"
			row_button.alignment = HORIZONTAL_ALIGNMENT_LEFT
			row_button.icon = InventoryIcons.texture_for(id)
			row_button.add_theme_constant_override("icon_max_width", 34)
			row_button.add_theme_constant_override("h_separation", 9)
			row_button.tooltip_text = caption
			item_buttons[id] = row_button
			row+=1
		action_buttons["inventory_previous"]=_button("上一页",Vector2(109,552),100,func(): _change_inventory_page(-1),inventory_page>0)
		action_buttons["inventory_next"]=_button("下一页",Vector2(335,552),100,func(): _change_inventory_page(1),inventory_page+1<int(inventory_view["pages"]))
		var filter_name: String = "全部" if inventory_filter == "all" else str(SLOT_NAMES[inventory_filter])
		var page_label := _label("%d 件 · %d/%d" % [inventory_view["total"],inventory_page+1,inventory_view["pages"]],Vector2(209,560),126)
		page_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		page_label.tooltip_text = "%s分类 · %d件 · 第%d/%d页" % [filter_name,inventory_view["total"],inventory_page+1,inventory_view["pages"]]
		action_buttons["compare_equip"]=_button("装备对比"+(" · 当前" if preview_operation=="equip" else ""),Vector2(475,280),210,func(): _compare("equip"))
		var equip: Dictionary=_equip_preview
		action_buttons["equip"]=_button("装备到"+str(SLOT_NAMES[equip["slot"]]),Vector2(475,521),210,func(): _act("equip",selected),bool(equip["eligible"]) and not _view_locked)
		action_buttons["equip"].tooltip_text=equip["reason"]
		if mode=="forge":
			action_buttons["compare_upgrade"]=_button("强化对比"+(" · 当前" if preview_operation=="upgrade" else ""),Vector2(701,280),210,func(): _compare("upgrade"))
			var upgrade: Dictionary=_upgrade_preview
			action_buttons["upgrade"]=_button("强化所选装备",Vector2(701,521),210,func(): _act("upgrade",selected),bool(upgrade["eligible"]) and not _view_locked)
			action_buttons["upgrade"].tooltip_text=upgrade["reason"]
			for index in range(shop_view["ids"].size()):
				var id: String=shop_view["ids"][index];var item: Dictionary=Session.rules.catalog[id]
				_label(str(item["name"]),Vector2(966,210+index*75),189,17)
				action_buttons["preview_"+id]=_button("对比",Vector2(935,239+index*75),70,func(): _select_equipment(id))
				action_buttons["preview_"+id].tooltip_text="查看"+str(item["name"])+"，不会购买。"
				var blocker: String = _purchase_blockers[id]
				action_buttons[id]=_button("购买 · %d" % int(item["price"]),Vector2(1015,239+index*75),140,func(): _act("buy",id),blocker.is_empty() and not _view_locked)
				action_buttons[id].tooltip_text="购买"+str(item["name"])+" · %d金币" % int(item["price"])+(": "+blocker if not blocker.is_empty() else "")
			action_buttons["shop_previous"]=_button("上页",Vector2(935,363),68,func(): _change_shop_page(-1),shop_page>0)
			action_buttons["shop_next"]=_button("下页",Vector2(1087,363),68,func(): _change_shop_page(1),shop_page+1<int(shop_view["pages"]))
			_label("暂无商品" if shop_ids.is_empty() else "%d/%d" % [shop_page+1,shop_view["pages"]],Vector2(1008,371),76)
		var can_practice: bool = preview_operation == "equip" and not equip["worn"] and (mode == "forge" or equip["owned"])
		can_practice = can_practice and not Session.party_save_pending() and Session.active_run_id.is_empty()
		var practice := _button("训练试用所选装备",Vector2(935,561 if mode == "forge" else 332),220,_start_equipment_practice,can_practice)
		if mode == "inventory":
			practice.tooltip_text = "在训练房比较两种配装。返回背包后继续查看这件装备。" if not equip["worn"] else "当前已穿戴此装备，请选择另一件来对比。"
		else:
			practice.tooltip_text = "货架装备可临时试用。返回铁匠铺后可查看本次读数。" if preview_operation=="equip" else "请先切换到装备对比。"
		action_buttons["practice"] = practice
		if town.has_equipment_trials(selected):
			var results_y: int = 459 if mode == "forge" else 381
			action_buttons["practice_results"] = _button("查看本次训练结果",Vector2(935,results_y),220,_open_practice_results)
	elif mode == "portal":
		difficulty_options.clear()
		for id in ["normal","veteran"]: difficulty_options[id] = Session.preview_dungeon(id)
		if not difficulty_options.has(selected_difficulty) or not difficulty_options[selected_difficulty]["unlocked"]:
			selected_difficulty = "normal"
		dungeon_preview = difficulty_options[selected_difficulty]
		for index in range(2):
			var id: String = ["normal","veteran"][index]
			var preview: Dictionary = difficulty_options[id]
			var name: String = preview.get("name",{"normal":"普通","veteran":"精英"}[id])
			var caption := name+(" · 已选择" if id == selected_difficulty else "")
			if not preview.get("available",false): caption += " · 配置不可用"
			elif not preview["unlocked"]: caption += " · 未解锁"
			action_buttons["difficulty_"+id] = _button(caption,Vector2(145+index*248,265),230,func(): selected_difficulty=id; refresh(),bool(preview["unlocked"]))
		var revision_full: bool = Session.rules.at_revision_limit(Session.profile)
		var can_enter: bool = dungeon_preview["unlocked"] and not revision_full and not _view_locked and not _entry_pending
		action_buttons["enter"] = _button("出发 · "+str(dungeon_preview.get("name","普通"))+"挑战",Vector2(465,526),340,_enter_selected,can_enter)
		if not dungeon_preview.get("available",false):
			portal_failure = Label.new(); portal_failure.position = Vector2(145,318); portal_failure.size = Vector2(990,115)
			portal_failure.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; portal_failure.mouse_filter = Control.MOUSE_FILTER_IGNORE
			portal_failure.add_theme_font_override("font",town.ui_font); portal_failure.add_theme_font_size_override("font_size",20)
			portal_failure.add_theme_color_override("font_color",Color("e0b38e")); portal_failure.text = str(dungeon_preview["message"])+"请修复游戏文件后重新启动。"
			add_child(portal_failure)
		else:
			var practice_enabled: bool = bool(dungeon_preview["unlocked"]) and not _entry_pending and not Session.party_save_pending() and Session.active_run_id.is_empty()
			var warden := _button("练习守门人双招",Vector2(840,526),310,
				_start_portal_practice.bind(Practice.Lesson.WARDEN),practice_enabled)
			action_buttons["practice_warden"] = warden
			var formation_rooms: int = int(dungeon_preview.get("formation_rooms",0))
			var formation_note: String
			if formation_rooms > 0:
				formation_note = "精英布阵 · %d 个房间采用不同站位" % formation_rooms
				var practice := _button("练习箭矢错位",Vector2(145,526),260,
					_start_portal_practice.bind(Practice.Lesson.ARROW_DODGE),practice_enabled)
				action_buttons["practice_arrows"] = practice
			else:
				var veteran: Dictionary = difficulty_options["veteran"]
				formation_note = "精英挑战需 Lv.%d 且通关 %d 次，出征时可自由切回普通。" % [veteran["unlock_level"],veteran["unlock_clears"]]
			var briefing := _label(formation_note,Vector2(145,401),990,18)
			briefing.name = "FormationNote"
			briefing.add_theme_color_override("font_color",Color("b4c3ca"))
		if dungeon_preview["unlocked"]:
			var hint: String = _quest_guide.departure_hint(Session.profile,selected_difficulty,_view_tracked_quest)
			if not hint.is_empty():
				departure_quest_hint = _label(hint,Vector2(145,432),990,18)
				departure_quest_hint.add_theme_color_override("font_color",Color("e2ceaa"))
		for id: String in ["normal","veteran"]:
			action_buttons["difficulty_"+id].tooltip_text = str(difficulty_options[id]["message"])
		action_buttons["enter"].tooltip_text = Session.rules.REVISION_LIMIT_MESSAGE if revision_full else str(dungeon_preview["message"])
	_refresh_bindings()
	_wire_focus()
	_restore_focus.call_deferred(_view_generation)
	queue_redraw()

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_IN and is_node_ready() and _owns_panel():
		_refresh_bindings()
		queue_redraw()


func _refresh_bindings() -> void:
	_navigation_hint = Actions.panel_navigation_hint()
	close_button.text = "关闭  "+Actions.binding_text("pause_game")
	close_button.tooltip_text = close_button.text
	if mode != "portal": return
	var return_key := Actions.binding_text("return_menu")
	_retreat_note = "死亡、撤退（%s）或正常关闭窗口，都会结算已取得的收益。" % return_key
	if action_buttons.has("practice_warden"):
		action_buttons["practice_warden"].tooltip_text = "练习起跳躲震地、上下走位躲横扫。返回出征选择（%s）。" % return_key
	if action_buttons.has("practice_arrows"):
		action_buttons["practice_arrows"].tooltip_text = "先练离弦后错位，完成或随时返回出征选择（%s）。" % return_key


func _owns_panel() -> bool:
	return is_inside_tree() and not is_queued_for_deletion() and is_instance_valid(town) and town.is_inside_tree() and not town.is_queued_for_deletion() and town.panel==self and get_tree().current_scene==town and is_visible_in_tree()

func _view_matches() -> bool:
	return (
		_view_revision == int(Session.profile["revision"])
		and is_same(_view_profile,Session.profile)
		and _view_locked == _growth_locked()
		and (mode not in ["quests","portal"] or _view_tracked_quest == Session.tracked_quest_id)
	)

func _can_use_view() -> bool:
	if not _owns_panel() or Session.local_scene_pending() or (is_instance_valid(effects_dialog) and effects_dialog.visible):return false
	if _view_matches():return true
	result_note="角色状态已变化，已刷新；请重新选择操作。"
	refresh()
	return false

func _invoke_button(button: Button, callback: Callable, generation: int) -> void:
	if not _owns_panel() or generation!=_view_generation or not is_instance_valid(button):return
	if is_instance_valid(effects_dialog) and effects_dialog.visible:return
	if button.get_parent()!=self or button.disabled or not button.is_visible_in_tree():return
	if button==close_button:callback.call();return
	if _can_use_view():callback.call()

func _wire_focus() -> void:
	_focus_buttons["close"]=close_button
	for id: String in item_buttons:_focus_buttons["item:"+id]=item_buttons[id]
	for id: String in action_buttons:_focus_buttons["action:"+id]=action_buttons[id]
	var entries: Array=[]
	for id: String in _focus_buttons:
		var button: Button=_focus_buttons[id]
		entries.append({"id":id,"rect":button.get_rect(),"enabled":not button.disabled and button.visible})
	focus_plan=FocusPlan.describe(entries)
	if focus_plan.is_empty():focus_plan=FocusPlan.describe([{"id":"close","rect":close_button.get_rect(),"enabled":true}])
	for id: String in _focus_buttons:
		var button: Button=_focus_buttons[id]
		button.focus_mode=Control.FOCUS_ALL if focus_plan.get("links",{}).has(id) else Control.FOCUS_NONE
		if button.focus_mode==Control.FOCUS_NONE:continue
		var links: Dictionary=focus_plan["links"][id]
		button.focus_next=button.get_path_to(_focus_buttons[links["next"]])
		button.focus_previous=button.get_path_to(_focus_buttons[links["previous"]])
		for side: int in [SIDE_LEFT,SIDE_TOP,SIDE_RIGHT,SIDE_BOTTOM]:
			button.set_focus_neighbor(side,button.get_path_to(_focus_buttons[links[side]]))
		button.focus_entered.connect(_remember_focus.bind(id,_view_generation))

func _remember_focus(id: String, generation: int) -> void:
	if _owns_panel() and generation==_view_generation:_last_focus_key=id

func _default_focus_key() -> String:
	if mode in ["inventory","forge"]:
		if focus_plan.get("links",{}).has("item:"+selected):return "item:"+selected
		var shelf_choice: String = "action:preview_"+selected
		if focus_plan.get("links",{}).has(shelf_choice): return shelf_choice
		if focus_plan.get("links",{}).has("action:practice_results"): return "action:practice_results"
		for id: String in inventory_view.get("ids",[]):
			if focus_plan.get("links",{}).has("item:"+id):return "item:"+id
		if focus_plan.get("links",{}).has("action:filter_"+inventory_filter): return "action:filter_"+inventory_filter
	elif mode=="portal" and focus_plan.get("links",{}).has("action:difficulty_"+selected_difficulty):
		return "action:difficulty_"+selected_difficulty
	elif mode=="quests":
		var links: Dictionary = focus_plan.get("links",{})
		var tracked_action: String = "action:"+_view_tracked_quest
		var quests: Dictionary = Session.profile["quests"]
		if links.has(tracked_action) and int(quests[_view_tracked_quest]["state"]) == 2: return tracked_action
		for id: String in Session.rules.quest_defs:
			if links.has("action:"+id) and (int(quests[id]["state"]) == 2 or Session.rules.elite_armor_pending(Session.profile,id)):
				return "action:"+id
		for id: String in action_buttons:
			if links.has("action:"+id) and quests.has(id): return "action:"+id
		for id: String in action_buttons:
			if links.has("action:"+id): return "action:"+id
	return "close"

func _restore_focus(generation: int) -> void:
	if not _owns_panel() or generation!=_view_generation:return
	if is_instance_valid(effects_dialog) and effects_dialog.visible:return
	var key: String=_focus_key if not _focus_key.is_empty() else _last_focus_key
	_focus_key=""
	if not focus_plan.get("links",{}).has(key):key=_default_focus_key()
	var button: Button=_focus_buttons.get(key)
	if is_instance_valid(button) and not button.disabled and button.is_visible_in_tree():button.grab_focus()

func _unhandled_input(event: InputEvent) -> void:
	if not (event is InputEventKey or event is InputEventJoypadButton or event is InputEventJoypadMotion): return
	if not _owns_panel() or event.is_echo(): return
	if is_instance_valid(effects_dialog) and effects_dialog.visible:return
	var focused: Control=get_viewport().gui_get_focus_owner()
	if focused!=null and is_ancestor_of(focused):return
	for action: String in ["ui_focus_next","ui_focus_prev","ui_left","ui_right","ui_up","ui_down"]:
		if event.is_action_pressed(action):
			_restore_focus(_view_generation)
			get_viewport().set_input_as_handled()
			return

func _growth_locked() -> bool:
	return Session.store.read_only or Session.has_recovered_solo_settlement() or Session.party_save_pending() or not Session.active_run_id.is_empty()
func _process(_delta: float) -> void:
	if _owns_panel() and not _view_matches():refresh()
func _set_inventory_filter(slot: String) -> bool:
	if mode not in ["inventory","forge"] or not _can_use_view(): return false
	if slot not in ["all","weapon","armor","charm"]: return false
	if slot == inventory_filter: return true
	inventory_filter = slot
	inventory_page = 0
	_focus_key = "action:filter_"+slot
	refresh(true)
	return true


func _reveal_owned_equipment(id: String) -> void:
	var ids: Array = Pages.matching(Session.profile["owned"],Session.rules.catalog,inventory_filter).get("ids",[])
	var index: int = ids.find(id)
	if index < 0:
		inventory_filter = "all"
		index = Session.profile["owned"].find(id)
	inventory_page = int(index/INVENTORY_PAGE_SIZE)


func _selected_is_outside_inventory_page() -> bool:
	return bool(equipment_preview.get("owned",false)) and selected not in inventory_view.get("ids",[])


func _locate_selected_equipment() -> bool:
	if mode not in ["inventory","forge"] or not _can_use_view(): return false
	if not _selected_is_outside_inventory_page(): return false
	_reveal_owned_equipment(selected)
	_focus_key = "item:"+selected
	refresh(true)
	return true


func _change_inventory_page(delta: int) -> bool:
	if not _can_use_view() or mode not in ["inventory","forge"]:return false
	if delta not in [-1,1] or inventory_view.is_empty():return false
	var page: int=inventory_page+delta
	if page<0 or page>=int(inventory_view["pages"]):return false
	inventory_page=page
	var view: Dictionary=Pages.describe(inventory_ids,page,INVENTORY_PAGE_SIZE)
	_focus_key="item:"+str(view["ids"][0]) if not view["ids"].is_empty() else ""
	refresh(true)
	return true
func _change_shop_page(delta: int) -> bool:
	if not _can_use_view():return false
	if mode!="forge" or delta not in [-1,1] or shop_view.is_empty():return false
	var page: int=shop_page+delta
	if page<0 or page>=int(shop_view["pages"]):return false
	shop_page=page
	var view: Dictionary=Pages.describe(shop_ids,page,SHOP_PAGE_SIZE)
	_focus_key="action:preview_"+str(view["ids"][0]) if not view["ids"].is_empty() else ""
	refresh(true)
	return true
func _select_equipment(id: String) -> void:
	if not _can_use_view() or mode not in ["inventory","forge"]:return
	if not Session.rules.catalog.has(id):return
	var owned_index: int=Session.profile["owned"].find(id)
	var shelf_index: int=shop_ids.find(id)
	if owned_index<0 and (mode!="forge" or shelf_index<0):return
	_focus_key="item:"+id if owned_index>=0 else "action:preview_"+id
	if selected==id and _focus_buttons.has(_focus_key):
		_restore_focus(_view_generation)
		return
	if owned_index>=0:_reveal_owned_equipment(id)
	# Owned items remain selectable from the visible shelf without moving it.
	if owned_index<0 and shelf_index>=0:shop_page=int(shelf_index/SHOP_PAGE_SIZE)
	selected=id;refresh(true)
func _start_equipment_practice() -> void:
	if not _can_use_view():return
	if mode not in ["inventory","forge"] or preview_operation!="equip":return
	var return_view := {"kind":mode,"filter":inventory_filter,"inventory_page":inventory_page,"shop_page":shop_page}
	var status: Error = Session.start_equipment_practice(selected,town,return_view,town.practice_entry_load_failed.bind(get_instance_id()))
	if status in [ERR_CANT_OPEN,ERR_CANT_CREATE]:
		town.practice_entry_load_failed(Session.last_message,get_instance_id())
	elif status!=OK:
		result_note=Session.last_message if not Session.last_message.is_empty() else "当前无法开始装备试用。"
		town.announce(result_note);_focus_key="action:practice";refresh()

func _open_effects() -> bool:
	if not _can_use_view() or mode not in ["inventory","forge"] or not is_instance_valid(effects_dialog):return false
	if not equipment_preview.get("ok",false):return false
	# Explicit open only; opening neither the panel nor a stable frame reads skills.
	var skills:=preload("res://game/combat/skill_catalog.gd").new()
	var moves := skills.selected_moves_snapshot(EquipmentEffects.ATTACKS)
	var examples := EquipmentEffects.describe(equipment_preview["before"],equipment_preview["after"],moves)
	var item: Dictionary=Session.rules.catalog[selected]
	var context: String="%s +%d · %s" % [item["name"],equipment_preview["target_rank"],"装备所选后" if preview_operation=="equip" else "强化后（当前配装）"]
	if not equipment_preview["reason"].is_empty():context+="\n"+str(equipment_preview["reason"])
	if not effects_dialog.show_examples(examples,context): return false
	_dialog_focus_key = "action:effects"
	return true

func _open_practice_results() -> bool:
	if not _can_use_view() or mode not in ["inventory","forge"] or not is_instance_valid(effects_dialog): return false
	if not effects_dialog.show_trial_results(town.equipment_trials_for(selected)): return false
	_dialog_focus_key = "action:practice_results"
	return true

func _effects_visibility_changed() -> void:
	if not is_instance_valid(effects_dialog) or effects_dialog.visible or not _owns_panel():return
	_focus_key = _dialog_focus_key
	_restore_focus.call_deferred(_view_generation)

func _compare(operation: String) -> void:
	if not _can_use_view() or mode not in ["inventory","forge"] or operation not in ["equip","upgrade"]:return
	if mode!="forge" and operation=="upgrade":return
	_focus_key="action:compare_"+operation
	if preview_operation==operation:
		_restore_focus(_view_generation)
		return
	preview_operation=operation;refresh(true)

func _start_portal_practice(lesson: int) -> void:
	if not _can_use_view() or mode != "portal" or _entry_pending: return
	var status: Error = Session.start_portal_practice(selected_difficulty,town,lesson,town.practice_entry_load_failed.bind(get_instance_id()))
	if status in [ERR_CANT_OPEN,ERR_CANT_CREATE]:
		town.practice_entry_load_failed(Session.last_message,get_instance_id())
	elif status != OK:
		result_note = Session.last_message
		town.announce(result_note)
		queue_redraw()


func _enter_selected() -> void:
	if not _can_use_view() or mode!="portal":return
	if _entry_pending: return
	_entry_pending = true
	action_buttons["enter"].disabled = true
	if not town._start_dungeon(selected_difficulty):
		show_departure_failure(town.toast)


func show_departure_failure(message: String) -> void:
	result_note = message
	if _entry_pending:
		_entry_pending = false
		_focus_key = "action:enter"
		refresh()
	else:
		queue_redraw()


# Only a committed claim with an actually owned item offers this navigation.
func _claimed_reward_item(id: String) -> String:
	if not Session.rules.quest_defs.has(id): return ""
	var quest: Dictionary = Session.profile["quests"].get(id,{})
	if int(quest.get("state",-1)) != 3: return ""
	var item: String = str(Session.rules.quest_defs[id]["item"])
	if item.is_empty() or not Session.rules.catalog.has(item) or not Session.profile["owned"].has(item): return ""
	return item


func _open_quest_reward(id: String) -> void:
	if mode != "quests" or not _can_use_view(): return
	var item: String = _claimed_reward_item(id)
	if item.is_empty(): return
	town.open_panel("inventory",item)


func _toggle_tracked_quest(id: String) -> void:
	if mode != "quests" or not _can_use_view(): return
	var next_id: String = "" if Session.tracked_quest_id == id else id
	if not Session.set_tracked_quest(next_id): return
	result_note = "已显示全部委托目标。" if next_id.is_empty() else "正在跟踪「%s」。" % Session.rules.quest_defs[id]["name"]
	_focus_key = "action:track_"+id
	refresh()


func _act(command: String,id: String) -> void:
	if not _can_use_view():return
	if command in ["buy","upgrade"] and mode!="forge":return
	if command=="equip" and mode not in ["inventory","forge"]:return
	if command in ["accept","claim"] and (mode!="quests" or town.player.position.distance_to(town.places["quests"])>=120):return
	if command not in ["buy","upgrade","equip","accept","claim"] or _growth_locked():return
	var supplemental: bool = command == "claim" and Session.rules.elite_armor_pending(Session.profile,id)
	var result: Dictionary = Session.act(command,id)
	result_note = result["message"]
	town.announce(result_note)
	if supplemental and result["ok"]: _focus_key = "action:reward_"+id
	if command=="buy" and result["ok"]:
		selected=id;preview_operation="equip"
		_reveal_owned_equipment(id)
		_focus_key="action:equip"
	refresh()


func text(point: Vector2,value: String,size: int=18,color: Color=Color("b4c3ca")) -> void:
	draw_string(town.ui_font,point,value,HORIZONTAL_ALIGNMENT_LEFT,-1,size,color)


func stat_lines(point: Vector2,stats: Dictionary) -> void:
	text(point,"最大生命   %d" % stats["hp"])
	text(point+Vector2(0,31),"伤害系数   %.2f ×" % stats["damage"])
	text(point+Vector2(0,62),"承受伤害   %.0f%%" % (float(stats["incoming"])*100))
	text(point+Vector2(0,93),"移动速度   %.0f%%" % (float(stats["speed"])*100))
	text(point+Vector2(0,124),"技能冷却   %.0f%%" % (float(stats["cooldown"])*100))


func _set_inventory_styles(variation: StringName, active: bool) -> void:
	_button_theme.set_type_variation(variation, &"Button")
	for state in ["normal", "hover", "pressed", "focus"]:
		var style := StyleBoxFlat.new()
		style.set_corner_radius_all(3)
		style.set_border_width_all(1)
		style.content_margin_left = 10.0
		style.content_margin_right = 10.0
		style.bg_color = Color("2d3c43") if active else Color("1d2b36")
		style.border_color = Color("b38d5e") if active else Color("465b66")
		if state in ["hover", "pressed"]:
			style.bg_color = Color("364a52")
		if state == "focus":
			style.draw_center = false
			style.border_color = Color("f0d6a3")
			style.set_border_width_all(2)
		_button_theme.set_stylebox(state, variation, style)


func _draw_item_icon(id: String, bounds: Rect2) -> void:
	var icon := InventoryIcons.texture_for(id)
	if icon != null:
		draw_texture_rect(icon, bounds, false)


func _draw_equipment_panels() -> void:
	for bounds in [Rect2(98,196,348,404), Rect2(464,196,459,72), Rect2(464,320,459,174)]:
		draw_rect(bounds, Color("111e29"))
		draw_rect(bounds, Color("465b66"), false)
	draw_rect(Rect2(474,201,62,62), Color("202e37"))
	draw_rect(Rect2(474,201,62,62), Color("947b55"), false)
	_draw_item_icon(selected, Rect2(478,205,54,54))
	var slot: String = equipment_preview["slot"]
	var worn: String = Session.profile["equipped"][slot]
	_draw_item_icon(worn, Rect2(475,323,27,27))
	draw_line(Vector2(474,350), Vector2(913,350), Color("465b66"))
	draw_line(Vector2(690,355), Vector2(690,485), Color("465b66"))


func _draw_worn_equipment() -> void:
	var bounds := Rect2(935,391,220,175)
	draw_rect(bounds, Color("111e29"))
	draw_rect(bounds, Color("465b66"), false)
	text(Vector2(947,416), "当前穿戴", 16, Color("e2ceaa"))
	var y := 424
	for slot: String in SLOT_NAMES:
		var id: String = Session.profile["equipped"][slot]
		var item: Dictionary = Session.rules.catalog[id]
		var rank: int = int(Session.profile["upgrades"].get(id, 0))
		draw_line(Vector2(945,y), Vector2(1145,y), Color("354953"))
		_draw_item_icon(id, Rect2(946,y+4,32,32))
		text(Vector2(986,y+16), SLOT_NAMES[slot], 12, Color("a7cbc3"))
		text(Vector2(986,y+34), "%s +%d" % [item["name"], rank], 15)
		y += 44


func _draw() -> void:
	draw_rect(Rect2(0,0,1280,720),Color(0.02,0.04,0.06,0.76))
	draw_rect(Rect2(80,115,1120,530),Color("172635"))
	draw_rect(Rect2(80,115,1120,530),Color("687a83"),false,2)
	text(Vector2(108,157),{"inventory":"角色背包","forge":"铁匠 阿岚 · 装备与强化","quests":"石桥镇委托","portal":"出征回响遗迹"}[mode],26,Color("ebd9b4"))
	if mode in ["inventory", "forge"]:
		_draw_item_icon("gold", Rect2(109,166,24,24))
		text(Vector2(139,185), "金币 %d" % Session.profile["gold"], 16, Color("d4bd8c"))
		_draw_item_icon("relic_shard", Rect2(275,166,24,24))
		text(Vector2(305,185), "遗迹碎片 %d" % Session.profile["materials"], 16, Color("d4bd8c"))
	else:
		text(Vector2(109,188),"金币 %d   /   遗迹碎片 %d" % [Session.profile["gold"],Session.profile["materials"]],16,Color("d4bd8c"))
	draw_string(town.ui_font,Vector2(520,187),_navigation_hint,HORIZONTAL_ALIGNMENT_LEFT,654,13,Color("98aab3"))
	if mode == "quests":
		var row: int = 0
		for id: String in Session.rules.quest_defs:
			var quest: Dictionary = Session.rules.quest_defs[id]
			var state: Dictionary = Session.profile["quests"][id]
			var y := QUEST_ROW_Y+row*QUEST_ROW_STEP
			text(Vector2(115,y),quest["name"],23,Color("e2ceaa"))
			text(Vector2(115,y+34),quest["description"],18)
			if Session.rules.elite_armor_pending(Session.profile,id):
				text(Vector2(115,y+65),"金币与经验已领取 · 装备待补领",17)
			else:
				text(Vector2(115,y+65),"进度 %d / %d   ·   金币 %d   经验 %d" % [state["progress"],quest["goal"],quest["gold"],quest["xp"]],17)
			if not str(quest["item"]).is_empty(): text(Vector2(115,y+94),"装备奖励："+str(Session.rules.catalog[quest["item"]]["name"]),17,Color("8ed9ce"))
			row += 1
		if town.player.position.distance_to(town.places["quests"]) >= 120: text(Vector2(115,596),"到委托板旁与巡守官交谈，接受委托或领取奖励。",17,Color("e0c28e"))
	elif mode in ["inventory","forge"]:
		if equipment_preview.is_empty() or not equipment_preview.get("ok",false):return
		var rank: int=equipment_preview["rank"]
		_draw_equipment_panels()
		text(Vector2(701,339),"装备所选后" if preview_operation=="equip" else "强化后 · 当前配装",17,Color("d9c79f"))
		comparison_lines()
		text(Vector2(475,501),"承伤与冷却越低越好；百分数差值为百分点。",13,Color("98aab3"))
		var reason: String=equipment_preview["reason"]
		if mode=="forge":
			# Cost is cached together with the active projection; no model work in draw.
			var cost: Dictionary=upgrade_cost_preview
			text(Vector2(475,601),"强化需先获得装备" if not equipment_preview["owned"] else ("已达到强化上限 +3" if rank>=3 else "强化 +%d → +%d：%d 金币 / %d 碎片" % [rank,rank+1,cost["gold"],cost["materials"]]),17,Color("d4bd8c"))
			text(Vector2(935,199),"铁匠货架 · 金币",16,Color("d4bd8c"))
			for index in range(shop_view["ids"].size()):
				_draw_item_icon(shop_view["ids"][index], Rect2(935,208+index*75,26,26))
			text(Vector2(935,473),"强化最多 +3",17,Color("d4bd8c"))
			text(Vector2(935,496),"武器：伤害系数 +0.06",14)
			text(Vector2(935,519),"护甲：生命 +8",14)
			text(Vector2(935,542),"护符：移速 +2百分点",14)
		else:
			_draw_worn_equipment()
			text(Vector2(475,601),"装备在下一次出征时生效",16)
		if not reason.is_empty():text(Vector2(475,576),reason,14,Color("b4c3ca"))
	elif mode == "portal":
		text(Vector2(145,240),"回响遗迹 · 选择挑战难度",27,Color("e2ceaa"))
		if dungeon_preview.get("available",false):
			text(Vector2(145,341),"%d 个房间 / %d 个敌人  ·  敌人生命 %.0f%% / 伤害 %.0f%%" % [dungeon_preview["rooms"],dungeon_preview["enemies"],dungeon_preview["enemy_hp_percent"],dungeon_preview["enemy_damage_percent"]],19)
			text(Vector2(145,381),"通关收益：金币 %d  ·  经验 %d  ·  遗迹碎片 %d" % [dungeon_preview["max_gold"],dungeon_preview["clear_xp"],dungeon_preview["materials"]],20,Color("d9c79f"))
		var reward_y: float = 485 if departure_quest_hint != null else 459
		var retreat_y: float = 511 if departure_quest_hint != null else 493
		text(Vector2(145,reward_y),"首次通关获得遗迹晶石；委托奖励需要回城领取。",18)
		text(Vector2(145,retreat_y),_retreat_note,16)
	if Session.store.read_only: text(Vector2(112,625),Session.store.last_error,16,Color("e0b38e"))
	elif Session.has_recovered_solo_settlement(): text(Vector2(112,625),Session.SOLO_RECOVERY_ACTION,16,Color("e0b38e"))
	elif mode == "portal" and Session.rules.at_revision_limit(Session.profile): text(Vector2(112,625),Session.rules.REVISION_LIMIT_MESSAGE,16,Color("e0b38e"))
	elif not result_note.is_empty(): text(Vector2(112,625),result_note,16,Color("a9d7b1"))


func comparison_lines() -> void:
	var names: Dictionary={"hp":"最大生命","damage":"伤害系数","incoming":"承受伤害","speed":"移动速度","cooldown":"技能冷却"}
	var index: int=0
	for row in equipment_preview["changes"]:
		var key: String=row["key"];var before: String=_stat_value(key,float(row["before"]));var after: String=_stat_value(key,float(row["after"]))
		var delta: float=float(row["delta"]);var difference: String="—"
		if not is_zero_approx(delta):
			if key=="hp":difference="%+d" % int(delta)
			elif key=="damage":difference="%+.2f×" % delta
			else:difference="%+.0f点" % (delta*100)
		var color: Color=Color("91d8b0") if row["benefit"]>0 else (Color("e6a48c") if row["benefit"]<0 else Color("b4c3ca"))
		text(Vector2(475,365+index*28),str(names[key])+"  "+before,16)
		text(Vector2(701,365+index*28),after+"  ("+difference+")",16,color)
		index+=1
func _stat_value(key: String,value: float) -> String:
	if key=="hp":return str(int(value))
	if key=="damage":return "%.2f×" % value
	return "%.0f%%" % (value*100)
