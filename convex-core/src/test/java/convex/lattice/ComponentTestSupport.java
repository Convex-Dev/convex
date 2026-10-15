package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;

/** Explicit component operations for low-level cursor and lattice fixtures. */
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
}
