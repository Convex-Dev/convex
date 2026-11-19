package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;

/**
 * Compatibility fixtures for low-level tests of context-based signing and lattice
 * APIs. Application examples use explicit signers through {@link ASignedComponent}
 * and {@link SignedComponent}; retaining these helpers tests the legacy API too.
 */
public final class ComponentTestSupport {
	private ComponentTestSupport() {}
	public static <V extends ACell> SignedCursor<V> signed(AForkableCursor<SignedData<V>> slot,
			convex.lattice.generic.AOwnerLattice<?,V,?> owners, ACell owner, java.util.function.Supplier<LatticeContext> context) {
		convex.lattice.generic.SignedLattice<V> rule=owners.signingFor(owner);
		if (rule==null) throw new IllegalArgumentException("Invalid owner: "+owner);
		return SignedCursor.create(slot,v -> rule.sign(context.get(),v));
	}
	public static <V extends ACell> V merge(AForkableCursor<V> cursor, ALattice<V> lattice, V other) {
		return cursor.updateAndGet(own -> lattice.merge(LatticeContext.EMPTY,own,other));
	}
	public static <V extends ACell> SignedCursor<V> signed(AForkableCursor<SignedData<V>> slot,
			convex.lattice.generic.AOwnerLattice<?,V,?> owners, ACell owner, LatticeContext context) {
		convex.lattice.generic.SignedLattice<V> rule=owners.signingFor(owner);
		if (rule==null) throw new IllegalArgumentException("Invalid owner: "+owner);
		return SignedCursor.create(slot,v -> rule.sign(context,v));
	}

	/**
	 * The entry view at a key of the map a cursor holds, of the map kind its lattice's
	 * empty value has: an index for an {@link convex.core.data.Index}, otherwise a hash map.
	 * A changed write creates the entry, and a missing map of that kind.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	public static <T extends ACell> AForkableCursor<T> entry(AForkableCursor<?> map, ACell key, ACell empty) {
		if (empty instanceof convex.core.data.Index) {
			return new IndexComponent((AForkableCursor)map).entry((convex.core.data.ABlobLike<?>)key);
		}
		return new MapComponent((AForkableCursor)map).entry(key);
	}
}
