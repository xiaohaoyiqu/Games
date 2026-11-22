extends RefCounted

const LocalFileAccess = preload("res://game/persistence/local_file_access.gd")


static func failed_open(path: String,error: Error) -> Dictionary:
	match LocalFileAccess.classify_failed_open(path,error):
		ERR_FILE_NOT_FOUND: return {"kind":"missing"}
		ERR_FILE_BAD_PATH: return {"kind":"blocked","issue":"path"}
		_: return {"kind":"blocked","issue":"open"}


static func change_issue(observed: Dictionary,expected: Dictionary) -> String:
	if observed.get("issue") in ["open","read","path"]: return observed["issue"]
	if observed["kind"] == expected["kind"] and observed.get("hash","") == expected.get("hash",""): return ""
	return "changed"


static func blocked_message(issue: String) -> String:
	match issue:
		"future":
			return "设置文件版本较新，原文件保留。请用支持该版本的游戏读取。"
		"path":
			return "设置路径被其他文件或目录占用。请检查设置文件的位置后重新读取。"
		"open", "read":
			return "暂时无法读取设置，原文件保留。请检查文件访问权限后重新读取。"
		"size", "json", "schema":
			return "设置文件格式无效，原文件保留。修复文件后请重新读取。"
		"unconfirmed":
			return "上次保存结果尚未确认。请重新读取，核对文件中的设置。"
		"changed":
			return "设置文件已变化。请重新读取，核对文件中的设置。"
		_:
			return "请重新读取，核对文件中的设置。"
