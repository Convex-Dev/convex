package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/** Tests cursor-controlled argument ordering during fork sync. */
public class ForkedContextSyncTest {

	private static final AKeyPair ALICE_KEY = AKeyPair.createSeeded(4101);
	private static final AKeyPair BOB_KEY = AKeyPair.createSeeded(4102);
	private static final AccountKey BOB = BOB_KEY.getAccountKey();
	private static final Keyword TIMESTAMP = Keyword.intern("timestamp");

	@Test
	public void syncUsesCurrentParentContextForSynthesisedSignedValue() {
		SignedLattice<ASet<CVMLong>> lattice = SignedLattice.create(SetLattice.create());
		RootLatticeCursor<SignedData<ASet<CVMLong>>> root = Cursors.createLattice(
			lattice, ALICE_KEY.signData(Sets.of(CVMLong.ONE)));
		root.setContext(LatticeContext.create(null, ALICE_KEY));

		ALatticeCursor<SignedData<ASet<CVMLong>>> fork = root.fork();
		fork.set(ALICE_KEY.signData(Sets.of(CVMLong.ONE, CVMLong.create(2))));

		root.set(ALICE_KEY.signData(Sets.of(CVMLong.ONE, CVMLong.create(3))));
		root.setContext(LatticeContext.create(null, BOB_KEY));

		SignedData<ASet<CVMLong>> result = fork.sync();
		assertEquals(BOB, result.getAccountKey());
		assertEquals(Sets.of(CVMLong.ONE, CVMLong.create(2), CVMLong.create(3)), result.getValue());
		assertEquals(result, root.get());
	}

	@Test
	public void syncUsesLwwWrapperRatherThanItsInnerLattice() {
		LWWLattice<ACell> lattice = LWWLattice.create(JSONLattice.INSTANCE,
			ForkedContextSyncTest::timestamp);
		AHashMap<Keyword, ACell> initial = value(1, "initial");
		RootLatticeCursor<ACell> root = Cursors.createLattice(lattice, initial);
		ALatticeCursor<ACell> fork = root.fork();

		fork.set(value(2, "fork"));
		AHashMap<Keyword, ACell> parentValue = value(3, "parent");
		root.set(parentValue);

		assertSame(parentValue, fork.sync());
		assertSame(parentValue, root.get());
	}

	private static AHashMap<Keyword, ACell> value(long timestamp, String value) {
		return Maps.of(TIMESTAMP, CVMLong.create(timestamp), Keyword.intern("value"), Strings.create(value));
	}

	private static long timestamp(ACell value) {
		return ((CVMLong) ((AHashMap<?, ?>) value).get(TIMESTAMP)).longValue();
	}
}
