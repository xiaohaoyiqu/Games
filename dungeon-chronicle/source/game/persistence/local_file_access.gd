extends RefCounted

# A child may report "not found" when an ancestor is a file instead of a directory.
static func classify_failed_open(path: String,error: Error) -> Error:
	if DirAccess.dir_exists_absolute(path): return ERR_FILE_BAD_PATH
	var parent := path.get_base_dir()
	while not parent.is_empty() and not DirAccess.dir_exists_absolute(parent):
		if FileAccess.file_exists(parent): return ERR_FILE_BAD_PATH
		var ancestor := parent.get_base_dir()
		if ancestor == parent: break
		parent = ancestor
	if error in [ERR_FILE_NOT_FOUND,ERR_DOES_NOT_EXIST] and not FileAccess.file_exists(path):
		return ERR_FILE_NOT_FOUND
	return ERR_FILE_CANT_OPEN
