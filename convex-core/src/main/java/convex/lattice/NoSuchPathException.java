package convex.lattice;

import java.util.Arrays;

import convex.core.data.AString;
import convex.core.data.Path;

/**
 * A segment of a caller's path names no place in a component's namespace. It is
 * thrown only by resolution, while the caller's segments are in hand. Unlike
 * {@link convex.lattice.cursor.MissingValueException}, where the place exists but
 * holds no value, the path itself does not fit. A write through a resolved
 * component that cannot be made throws {@link PathWriteException} instead.
 *
 * <p>It carries the path from the first segment that does not fit. That path keeps
 * its position in the caller's request, and {@link Path#request()} gives the whole
 * request.</p>
 */
public class NoSuchPathException extends LatticePathException {
	private static final long serialVersionUID = 1L;

	private final transient Path path;

	/**
	 * @param path Path from the first segment that does not fit
	 * @throws IllegalArgumentException If the path is empty, so names no segment
	 */
	public NoSuchPathException(Path path) {
		super(message(path),path.position());
		this.path=path;
	}

	/**
	 * Reports segment {@code index} of a request held as an array, as resolution
	 * receives it. The segments are copied, since the array is the caller's.
	 *
	 * @param segments Segments of the request
	 * @param index Index of the first segment that does not fit
	 * @param end End of the request's segments in the array
	 * @throws IllegalArgumentException If {@code index} is not before {@code end}
	 */
	public NoSuchPathException(AString[] segments,int index,int end) {
		this(Path.of(Arrays.copyOf(segments,end)).slice(index));
	}

	private static String message(Path path) {
		if ((path==null)||path.isEmpty()) throw new IllegalArgumentException("Path must name the segment that does not fit");
		return "No such path at segment "+path.position()+": "+path.get(0);
	}

	/** The path from the first segment that does not fit, positioned in the caller's request. */
	public Path getPath() {
		return path;
	}

	/** The first segment that does not fit. */
	public AString getSegment() {
		return path.get(0);
	}
}
