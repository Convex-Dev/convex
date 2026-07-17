package convex.dlfs;

/**
 * Shared validation for externally supplied DLFS drive names and relative paths.
 *
 * <p>Validation happens before either filesystem lookup or capability matching. In
 * particular, dot segments are rejected rather than normalised after authorisation,
 * because {@code public/../secret} must never be covered by a grant on {@code public}.</p>
 */
final class DLFSPathValidator {

	static final int MAX_DRIVE_NAME_LENGTH = 128;
	static final int MAX_PATH_LENGTH = 4096;
	static final int MAX_COMPONENT_LENGTH = 255;
	static final int MAX_PATH_COMPONENTS = 256;

	private DLFSPathValidator() {}

	static boolean isValidDriveName(String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_DRIVE_NAME_LENGTH) return false;
		if (name.equals(".") || name.equals("..")) return false;
		if (!name.equals(name.strip())) return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '/' || c == '\\' || c == ':' || Character.isISOControl(c)) return false;
		}
		return true;
	}

	/**
	 * Validates and returns a canonical relative path. A single trailing slash is
	 * accepted for directory-oriented callers and removed.
	 */
	static String canonicalRelativePath(String path) {
		if (path == null || path.isEmpty()) return "";
		if (path.length() > MAX_PATH_LENGTH) throw new IllegalArgumentException("Path is too long");
		if (path.charAt(0) == '/') throw new IllegalArgumentException("Path must be relative to the drive");
		while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		if (path.isEmpty()) return "";

		String[] components = path.split("/", -1);
		if (components.length > MAX_PATH_COMPONENTS) throw new IllegalArgumentException("Path is too deep");
		for (String component : components) {
			if (component.isEmpty()) throw new IllegalArgumentException("Empty path component");
			if (component.equals(".") || component.equals("..")) {
				throw new IllegalArgumentException("Dot path components are not allowed");
			}
			if (component.length() > MAX_COMPONENT_LENGTH) {
				throw new IllegalArgumentException("Path component is too long");
			}
			for (int i = 0; i < component.length(); i++) {
				char c = component.charAt(i);
				if (c == '\\' || Character.isISOControl(c)) {
					throw new IllegalArgumentException("Invalid path component");
				}
			}
		}
		return String.join("/", components);
	}
}
