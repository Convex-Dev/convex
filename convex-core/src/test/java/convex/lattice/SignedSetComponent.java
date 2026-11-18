package convex.lattice;

import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.SignedData;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.SignedLattice;

/**
 * A signed set draft whose local changes on both sides combine by union, as a
 * grow-only set allows.
 *
 * @param <T> Element type
 */
public class SignedSetComponent<T extends ACell> extends SignedComponent<ASet<T>> {

	public SignedSetComponent(SignedLattice<ASet<T>> lattice,ACursor<SignedData<ASet<T>>> destination,
			Function<ASet<T>,SignedData<ASet<T>>> signer) {
		super(lattice,destination,signer);
	}

	/** Binds unsigned working storage with no signer. */
	protected SignedSetComponent(SignedLattice<ASet<T>> lattice,AForkableCursor<ASet<T>> cursor) {
		super(lattice,cursor);
	}

	private SignedSetComponent(SignedSetComponent<T> source,AForkableCursor<ASet<T>> cursor) {
		super(source,cursor);
	}

	@Override protected ASet<T> reconcile(ASet<T> own,ASet<T> other) {
		return union(own,other);
	}

	@Override public SignedSetComponent<T> rebind(AForkableCursor<ASet<T>> cursor) {
		return new SignedSetComponent<>(this,cursor);
	}

	@Override public SignedSetComponent<T> fork() { return rebind(storage.fork()); }

	/** Union of two sets, either of which may be absent. */
	public static <T extends ACell> ASet<T> union(ASet<T> a,ASet<T> b) {
		if (a==null) return b;
		if (b==null) return a;
		return a.includeAll(b);
	}
}
