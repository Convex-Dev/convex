package convex.lattice.generic;

import convex.core.data.ACell;
import convex.lattice.ALattice;

/**
 * A lattice for a <b>reserved</b> region — a path declared in a lattice root so that its
 * address is stable and claimed, but whose merge semantics have not yet been designed.
 *
 * <p>Nothing ever merges here. {@link #merge} returns the own value unchanged, so an
 * incoming value at this region is discarded rather than incorporated. The region's
 * value therefore stays at {@link #zero} ({@code null}) for the lifetime of the node.
 *
 * <p><b>Why not just leave the key out, or use an unimplemented lattice?</b> Both
 * alternatives are worse at an untrusted network boundary:
 * <ul>
 *   <li>An <em>undeclared</em> key is dropped by {@link AKeyedLattice} merge, which is
 *       equivalent for merges, but leaves the path unclaimed — another region could take
 *       it, and the reservation is invisible to anyone reading the root.</li>
 *   <li>A lattice that <em>throws</em> (e.g. {@code convex.lattice.kad.KadLattice}, whose
 *       operations raise {@code TODOException}) aborts the whole enclosing merge, not just
 *       its own sub-value. A peer sending one combined update would lose its valid sibling
 *       regions as collateral, and accrue a consecutive-reject against the receiving
 *       node's per-connection circuit-breaker — penalising the peer for using a region
 *       that node advertises in its own root.</li>
 * </ul>
 *
 * <p>Discarding quietly keeps the failure local to the reserved sub-value: siblings merge
 * normally and honest peers are not disconnected.
 *
 * <p>{@link #path} returns null (not navigable), following the same convention
 * {@link AKeyedLattice} uses for unregistered keys, so navigating into a reserved region
 * fails as an unknown path rather than throwing.
 */
public class ReservedLattice extends ALattice<ACell> {

	private final String reason;

	private ReservedLattice(String reason) {
		this.reason = reason;
	}

	/**
	 * Creates a reserved lattice region.
	 *
	 * @param reason Human-readable note on what this region is reserved for
	 * @return New ReservedLattice instance
	 */
	public static ReservedLattice create(String reason) {
		return new ReservedLattice(reason);
	}

	/**
	 * Gets the reason this region is reserved.
	 *
	 * @return Reservation note supplied at construction
	 */
	public String getReason() {
		return reason;
	}

	/**
	 * Discards {@code otherValue} and returns {@code ownValue} unchanged. A reserved
	 * region never accepts data, but must not abort the merge of its siblings.
	 */
	@Override
	public ACell merge(ACell ownValue, ACell otherValue) {
		return ownValue;
	}

	@Override
	public ACell zero() {
		return null;
	}

	@Override
	public boolean checkForeign(ACell value) {
		// No foreign value is acceptable in a reserved region.
		return false;
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null;
	}

	@Override
	public String toString() {
		return "ReservedLattice[" + reason + "]";
	}
}
