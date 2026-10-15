package convex.lattice.fs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;

/**
 * A component's logical location in one working lattice. The anchor identifies
 * mutable storage, not its current value; equal snapshots and forks are distinct.
 * Components retain this information when constructing projected cursors and NIO
 * views. Arbitrary cursor projections cannot infer aliases automatically.
 */
public final class DLFSLocation {
	private final AForkableCursor<?> anchor;
	private final List<ACell> path;

	private DLFSLocation(AForkableCursor<?> anchor,List<ACell> path) {
		this.anchor=Objects.requireNonNull(anchor);
		this.path=path;
	}

	/** Identifies a working cursor and a logical path below it. */
	public static DLFSLocation at(AForkableCursor<?> anchor,ACell... path) {
		return new DLFSLocation(anchor,List.of(path));
	}

	/** Returns a child location without constructing or navigating a cursor. */
	public DLFSLocation child(ACell key) {
		var childPath=new ArrayList<ACell>(path.size()+1);
		childPath.addAll(path);
		childPath.add(Objects.requireNonNull(key));
		return new DLFSLocation(anchor,List.copyOf(childPath));
	}

	/** Logical path, including region, owner and drive when known by the component. */
	public List<ACell> path() { return path; }

	@Override public boolean equals(Object other) {
		return other instanceof DLFSLocation location && anchor==location.anchor && path.equals(location.path);
	}

	@Override public int hashCode() {
		return 31*System.identityHashCode(anchor)+path.hashCode();
	}
}
