package convex.lattice;

import java.util.Objects;

import convex.core.data.AString;

/**
 * A component path is well formed but incompatible with what it addresses. In
 * resolution, a segment names no place in a component's namespace. In a write
 * through a resolved path, a segment cannot be written: its container is absent,
 * or cannot hold its key. Unlike {@link convex.lattice.cursor.MissingValueException},
 * where the place exists but holds no value, the path itself does not fit.
 *
 * <p>It carries a copy of the whole path array the component was given, and the
 * index in that array of the first segment that does not fit. Parents pass the
 * same array to their children, so the index locates the segment for every caller,
 * whatever slice it resolved. For an absent container, the container is the path
 * before that segment, or the component's own value when that prefix is empty.</p>
 */
public class NoSuchPathException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	private final transient AString[] path;
	private final int index;

	/**
	 * @param path Path array being resolved, as passed to the component; copied
	 * @param index Index in {@code path} of the first segment that does not fit
	 */
	public NoSuchPathException(AString[] path,int index) {
		super("No such path at segment "+index+": "+segment(path,index));
		this.path=path.clone();
		this.index=index;
	}

	private static AString segment(AString[] path,int index) {
		return Objects.requireNonNull(path[Objects.checkIndex(index,path.length)],"Path segment must not be null");
	}

	/** A copy of the path array the component was given. */
	public AString[] getPath() {
		return path.clone();
	}

	/** Index in the path array of the first segment that does not fit. */
	public int getIndex() {
		return index;
	}

	/** The first segment that does not fit. */
	public AString getSegment() {
		return path[index];
	}
}
