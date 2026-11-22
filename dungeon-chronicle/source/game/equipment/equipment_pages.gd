extends RefCounted

## Bounded, side-effect-free pages. Browsing never changes the selected item.
static func describe(ids: Array,requested_page: int,per_page: int) -> Dictionary:
	if ids.size()>64 or per_page not in [2,7]:return {"ok":false}
	var seen: Dictionary={}
	for id in ids:
		if not id is String or id.is_empty() or id.length()>48 or seen.has(id):return {"ok":false}
		seen[id]=true
	var pages: int=maxi(1,ceili(float(ids.size())/float(per_page)))
	var page: int=clampi(requested_page,0,pages-1)
	var start: int=page*per_page
	return {"ok":true,"page":page,"pages":pages,"total":ids.size(),"ids":ids.slice(start,mini(start+per_page,ids.size()))}


## Preserve ownership order and reject invalid input atomically; never alias inputs.
static func matching(ids: Array,catalog: Dictionary,slot: String="all") -> Dictionary:
	if slot not in ["all","weapon","armor","charm"] or not describe(ids,0,7)["ok"]: return {"ok":false}
	var matches: Array = []
	for id: String in ids:
		var item: Variant = catalog.get(id)
		if not item is Dictionary or item.get("slot") not in ["weapon","armor","charm"]: return {"ok":false}
		if slot == "all" or item["slot"] == slot: matches.append(id)
	return {"ok":true,"ids":matches}
