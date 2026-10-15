package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.generic.JSONLattice;

/**
 * Laws for {@link ALattice#resolvePath}, the translation of external (JSON) keys to
 * canonical lattice keys. Cursors know nothing of lattices, so a component resolves
 * a path against its lattice and then navigates its cursor with the result:
 * {@code cursor.path(lattice.resolvePath(keys))}.
 *
 * <ul>
 *   <li><b>canonicalisation</b> — resolved keys reach data that raw external keys miss;</li>
 *   <li><b>composition</b> — resolving a path equals resolving each level in turn;</li>
 *   <li><b>degeneration</b> — with an identity resolver, resolution is the identity and
 *       allocates nothing;</li>
 *   <li><b>partiality</b> — an unresolvable key yields null rather than a silent miss.</li>
 * </ul>
 */
public class ResolvePathTest {

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

	static final MapResolveLattice LATTICE = new MapResolveLattice(Maps.of(A, X, B, Y));

	static Root<ACell> mapRoot() {
		ACell data = Maps.of(X, Maps.of(Y, LEAF)); // canonical keyword-keyed structure
		return Cursors.root(data, LATTICE);
	}

	/** Resolution canonicalises: the resolved path reaches the leaf, raw external keys miss. */
	@Test public void testResolveCanonicalises() {
		Root<ACell> root = mapRoot();
		ACell[] resolved = LATTICE.resolvePath(A, B);
		assertEquals(X, resolved[0]);
		assertEquals(Y, resolved[1]);
		assertEquals(LEAF, root.path(resolved).get());   // "a","b" → :x,:y → leaf
		assertNull(root.path(A, B).get());                // raw external keys don't match :x,:y
	}

	/** Composition: resolving a whole path equals resolving one level at a time. */
	@Test public void testResolveComposes() {
		ACell[] whole = LATTICE.resolvePath(A, B);
		ACell[] first = LATTICE.resolvePath(A);
		ACell[] second = LATTICE.path(first[0]).resolvePath(B);
		assertEquals(whole[0], first[0]);
		assertEquals(whole[1], second[0]);

		Root<ACell> r1 = mapRoot();
		r1.path(whole).set(Strings.create("v"));
		Root<ACell> r2 = mapRoot();
		r2.path(first).path(second).set(Strings.create("v"));
		assertEquals(r1.get(), r2.get()); // same resulting structure
	}

	/** Identity: an empty path resolves to an empty path and navigates to the cursor itself. */
	@Test public void testResolveIdentity() {
		Root<ACell> root = mapRoot();
		ACell[] none = LATTICE.resolvePath();
		assertEquals(0, none.length);
		assertSame(root, root.path(none));
	}

	/** Degeneration: with an identity resolver (JSONLattice), resolution changes nothing. */
	@Test public void testResolveReducesToIdentity() {
		ACell data = Maps.of(A, Maps.of(B, LEAF)); // string-keyed
		Root<ACell> root = Cursors.root(data, JSONLattice.INSTANCE);
		ACell[] keys = new ACell[]{A, B};
		assertSame(keys, JSONLattice.INSTANCE.resolvePath(keys)); // identity resolver — no copy
		assertEquals(LEAF, root.path(JSONLattice.INSTANCE.resolvePath(keys)).get());
	}

	/** Partiality: an unresolvable key yields null rather than silently missing. */
	@Test public void testResolveReturnsNullOnUnresolvable() {
		assertNull(LATTICE.resolvePath(Strings.create("nope")));
		assertNull(LATTICE.resolvePath(A, Strings.create("nope")));
	}

	/** Copy-on-change: a key that resolves to a different canonical key produces a new array. */
	@Test public void testResolvePathCopiesOnChange() {
		ACell[] keys = new ACell[]{A, B};
		ACell[] resolved = LATTICE.resolvePath(keys);
		assertNotSame(keys, resolved); // changed → new array
		assertEquals(X, resolved[0]);
		assertEquals(Y, resolved[1]);
		assertEquals(A, keys[0]);      // input untouched
	}
}
