package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Tests for {@link ReservedLattice} — a declared but undesigned lattice region.
 */
public class ReservedLatticeTest {

	private static final Keyword RESERVED = Keyword.intern("reserved");

	private static ReservedLattice lattice() {
		return ReservedLattice.create("not yet designed");
	}

	@Test
	public void testZeroIsNull() {
		assertNull(lattice().zero());
	}

	@Test
	public void testNoForeignValueAccepted() {
		assertFalse(lattice().checkForeign(CVMLong.create(1)));
		assertFalse(lattice().checkForeign(null));
	}

	@Test
	public void testNotNavigable() {
		// Null (rather than throwing) matches AKeyedLattice's convention for an
		// unregistered key, so navigating in fails as an unknown path.
		assertNull(lattice().path(Keywords.VALUE));
		assertNull(lattice().path(CVMLong.create(0)));
	}

	@Test
	public void testMergeDiscardsIncomingValue() {
		ReservedLattice l = lattice();
		assertNull(l.merge(null, Vectors.of(1, 2, 3)));
		assertEquals(Strings.create("own"), l.merge(Strings.create("own"), Vectors.of(1, 2, 3)));
		assertNull(l.merge(null, null));
	}

	@Test
	public void testReasonIsRetained() {
		ReservedLattice l = ReservedLattice.create("Kademlia routing");
		assertEquals("Kademlia routing", l.getReason());
		assertNotNull(l.toString());
	}

	/**
	 * The point of a reserved region over an unimplemented (throwing) lattice: a value
	 * sent to it must not abort the merge of its siblings. A throwing child would take
	 * the whole enclosing merge down with it.
	 */
	@Test
	public void testDoesNotAbortSiblingMerge() {
		KeyedLattice root = KeyedLattice.create(
			Keywords.DATA, MaxLattice.create(),
			RESERVED, lattice());

		@SuppressWarnings("unchecked")
		Index<Keyword, ACell> incoming = (Index<Keyword, ACell>) Index.EMPTY
			.assoc(Keywords.DATA, CVMLong.create(42))
			.assoc(RESERVED, Vectors.of(1, 2, 3));

		Index<Keyword, ACell> merged = root.merge(root.zero(), incoming);

		assertEquals(CVMLong.create(42), merged.get(Keywords.DATA), "Sibling merged normally");
		assertNull(merged.get(RESERVED), "Reserved region absorbed nothing");
	}
}
