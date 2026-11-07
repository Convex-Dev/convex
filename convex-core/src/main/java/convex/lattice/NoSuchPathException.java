package convex.lattice;

import convex.core.data.AString;
import convex.core.data.Path;

/**
 * A component path is well formed but incompatible with what it addresses. In
 * resolution, a segment names no place in a component's namespace. In a write
 * through a resolved path, a segment cannot be written: its container is absent,
 * or cannot hold its key. Unlike {@link convex.lattice.cursor.MissingValueException},
 * where the place exists but holds no value, the path itself does not fit.
 *
 * <p>It carries the path from the first segment that does not fit. That path keeps
 * its position in the caller's request, and {@link Path#request()} gives the whole
 * request. For an absent container, the container is the request before that
 * segment, or the component's own value when nothing precedes it.</p>
 */
public class NoSuchPathException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	private final transient Path path;

	/**
	 * @param path Path from the first segment that does not fit
	 * @throws IllegalArgumentException If the path is empty, so names no segment
	 */
	public NoSuchPathException(Path path) {
		super(message(path));
		this.path=path;
	}

	private static String message(Path path) {
		if (path.isEmpty()) throw new IllegalArgumentException("Path must name the segment that does not fit");
		return "No such path at segment "+path.position()+": "+path.get(0);
	}

	/** The path from the first segment that does not fit, positioned in the caller's request. */
	public Path getPath() {
		return path;
	}

	/** Index in the caller's request of the first segment that does not fit. */
	public int getIndex() {
		return path.position();
	}

	/** The first segment that does not fit. */
	public AString getSegment() {
		return path.get(0);
	}
}
