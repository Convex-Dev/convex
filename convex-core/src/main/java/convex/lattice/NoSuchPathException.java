package convex.lattice;

import java.util.NoSuchElementException;
import java.util.Objects;

import convex.core.data.AString;

/**
 * A path segment names no place in the namespace of the component resolving it.
 * Unlike {@link convex.lattice.cursor.MissingValueException}, where the place
 * exists but holds no value, the path itself is incompatible with the component.
 *
 * <p>It carries a copy of the whole path array the component was given, and the
 * index in that array of the first segment that names no place. Parents pass the
 * same array to their children, so the index locates the segment for every caller,
 * whatever slice it resolved.</p>
 */
public class NoSuchPathException extends NoSuchElementException {
	private static final long serialVersionUID = 1L;

	private final transient AString[] path;
	private final int index;

	/**
	 * @param path Path array being resolved, as passed to the component; copied
	 * @param index Index in {@code path} of the first segment that names no place
	 */
	public NoSuchPathException(AString[] path,int index) {
		super("No component at path segment "+index+": "+segment(path,index));
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

	/** Index in the path array of the first segment that names no place. */
	public int getIndex() {
		return index;
	}

	/** The first segment that names no place. */
	public AString getSegment() {
		return path[index];
	}
}
