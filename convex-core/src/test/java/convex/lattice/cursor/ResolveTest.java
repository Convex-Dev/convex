package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.JSONLattice;

/**
 * Laws for {@link ALatticeCursor#resolve}, the user-facing external-key
 * navigation. {@code resolve = path ∘ resolvePath}, resolving against the
 * cursor's own lattice, so it must satisfy:
 *
 * <ul>
 *   <li><b>identity</b> — {@code resolve() == this};</li>
 *   <li><b>associativity</b> — {@code c.resolve(a).resolve(b)} ≡ {@code c.resolve(a,b)}
 *       (reads and writes);</li>
 *   <li><b>degeneration</b> — with an identity resolver, {@code resolve ≡ path};</li>
 *   <li><b>partiality</b> — an unresolvable key fails fast.</li>
 * </ul>
 */
public class ResolveTest {

	static final AString A = Strings.create("a");
	static final AString B = Strings.create("b");
	static final Keyword X = Keyword.create("x");
	static final Keyword Y = Keyword.create("y");
	static final AString LEAF = Strings.create("leaf");

	/** Recursive lattice resolving external keys via a fixed map (unmapped → null). */
	static class MapResolveLattice extends ALattice<ACell> {
		final AHashMap<ACell, ACell> mapping;
		MapResolveLattice(AHashMap<ACell, ACell> mapping) { this.mapping = mapping; }

		@Override public ACell resolveKey(ACell key) { return mapping.get(key); }
		@Override public ACell merge(ACell own, ACell other) { return (other != null) ? other : own; }
		@Override public ACell zero() { return Maps.empty(); }
		@Override public boolean checkForeign(ACell value) { return true; }

		@SuppressWarnings("unchecked")
		@Override public <T extends ACell> ALattice<T> path(ACell childKey) { return (ALattice<T>) this; }
	}

	static RootLatticeCursor<ACell> mapRoot() {
		AHashMap<ACell, ACell> mapping = Maps.of(A, X, B, Y);
		MapResolveLattice lat = new MapResolveLattice(mapping);
		ACell data = Maps.of(X, Maps.of(Y, LEAF)); // canonical keyword-keyed structure
		return Cursors.createLattice(lat, data, LatticeContext.EMPTY);
	}

	/** Resolution actually canonicalises: raw path with external keys misses. */
	@Test public void testResolveCanonicalises() {
		RootLatticeCursor<ACell> root = mapRoot();
		assertEquals(LEAF, root.resolve(A, B).get());   // "a","b" → :x,:y → leaf
		assertNull(root.path(A, B).get());              // raw external keys don't match :x,:y
	}

	/** Associativity for reads and writes: resolve(a).resolve(b) ≡ resolve(a,b). */
	@Test public void testResolveAssociative() {
		RootLatticeCursor<ACell> root = mapRoot();
		assertEquals(root.resolve(A, B).get(), root.resolve(A).resolve(B).get());
		assertEquals(LEAF, root.resolve(A).resolve(B).get());

		RootLatticeCursor<ACell> r1 = mapRoot();
		r1.resolve(A, B).set(Strings.create("v"));
		RootLatticeCursor<ACell> r2 = mapRoot();
		r2.resolve(A).resolve(B).set(Strings.create("v"));
		assertEquals(r1.get(), r2.get()); // same resulting structure — morphisms are equal actions
	}

	/** Identity: resolve() with no keys is the cursor itself. */
	@Test public void testResolveIdentity() {
		RootLatticeCursor<ACell> root = mapRoot();
		assertSame(root, root.resolve());
	}

	/** Degeneration: with an identity resolver (JSONLattice), resolve ≡ path. */
	@Test public void testResolveReducesToPath() {
		ACell data = Maps.of(A, Maps.of(B, LEAF)); // string-keyed
		RootLatticeCursor<ACell> root = Cursors.createLattice(JSONLattice.INSTANCE, data, LatticeContext.EMPTY);
		assertEquals(root.path(A, B).get(), root.resolve(A, B).get());
		assertEquals(LEAF, root.resolve(A, B).get());
	}

	/** Partiality: an unresolvable key fails fast rather than silently missing. */
	@Test public void testResolveThrowsOnUnresolvable() {
		RootLatticeCursor<ACell> root = mapRoot();
		assertThrows(IllegalArgumentException.class, () -> root.resolve(Strings.create("nope")));
	}
}
