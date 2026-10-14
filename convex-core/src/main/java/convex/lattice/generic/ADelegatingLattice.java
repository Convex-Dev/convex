package convex.lattice.generic;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Base for lattice layers that wrap an inner lattice and add exactly <b>one</b>
 * concern, delegating the other method-group to the inner lattice:
 *
 * <ul>
 *   <li><b>merge</b> — {@code merge}, {@code merge(context,…)}</li>
 *   <li><b>navigation / structure</b> — {@code path}, {@code zero},
 *       {@code resolveKey}, {@code isStructural}, {@code checkForeign}</li>
 * </ul>
 *
 * <p>A concrete layer overrides only the group it owns and inherits the rest as
 * transparent delegation, so concerns compose without becoming entangled. A layer
 * which also needs to intercept writes, such as stamping, exposes what a cursor
 * wrapper needs (see {@link StampingLattice}) rather than constructing cursors:
 * lattices know nothing about cursors. When {@code inner} is {@code null} the
 * delegations fall back to terminal defaults (no navigation), so a merge layer can
 * still be a terminal register by overriding merge alone.</p>
 *
 * @param <V> Type of lattice values
 */
public abstract class ADelegatingLattice<V extends ACell> extends ALattice<V> {

	/** Wrapped inner lattice; may be null (terminal defaults). */
	protected final ALattice<V> inner;

	protected ADelegatingLattice(ALattice<V> inner) {
		this.inner = inner;
	}

	/** Returns the wrapped inner lattice, or null for a terminal layer. */
	public ALattice<V> getInner() {
		return inner;
	}

	// ===== merge =====

	@Override
	public V merge(V own, V other) {
		return (inner != null) ? inner.merge(own, other) : own;
	}

	@Override
	public V merge(LatticeContext context, V own, V other) {
		return (inner != null) ? inner.merge(context, own, other) : merge(own, other);
	}

	// ===== navigation / structure =====

	@Override
	public V zero() { return (inner != null) ? inner.zero() : null; }

	@Override
	public boolean checkForeign(V value) { return (inner != null) ? inner.checkForeign(value) : true; }

	@Override
	public boolean isStructural() { return (inner != null) && inner.isStructural(); }

	@Override
	public ACell resolveKey(ACell key) { return (inner != null) ? inner.resolveKey(key) : super.resolveKey(key); }

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) { return (inner != null) ? inner.path(childKey) : null; }
}
