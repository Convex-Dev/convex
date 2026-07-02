package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Lists;
import convex.core.data.RecordTest;
import convex.core.data.Ref;
import convex.core.data.Refs;
import convex.core.data.Refs.RefTreeStats;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.init.InitTest;
import convex.test.Samples;

/**
 * Tests for the State data structure
 */
@TestInstance(Lifecycle.PER_CLASS)
public class StateTest {
	State INIT_STATE=InitTest.createState();

	@Test
	public void testEmptyState() {
		State s = State.EMPTY;
		assertSame(s,s.updateRefs(rf->rf));

		AVector<AccountStatus> accts = s.getAccounts();
		assertEquals(0, accts.count());

		doStateTests(s);
	}

	@Test
	public void testInitialState() throws InvalidDataException {
		State s = INIT_STATE;
		assertSame(s, s.withAccounts(s.getAccounts()));
		assertSame(s, s.withPeers(s.getPeers()));

		s.validate();

		assertEquals(s.getEncodingLength(),s.getEncoding().size());

		doStateTests(s);
	}

	public static void doStateTests(State s) {
		RecordTest.doRecordTests(s);
		doProtocolInvariantTests(s);
	}

	/**
	 * Checks the protocol globals invariants that must hold in every valid State.
	 * See UPGRADE.md "Invariants".
	 * @param s State to check
	 */
	public static void doProtocolInvariantTests(State s) {
		AVector<ACell> glbs = s.getGlobals();
		long gc = glbs.count();

		// Globals either predate the protocol globals entirely, or contain both
		assertTrue((gc <= State.GLOBAL_PROTOCOL) || (gc > State.GLOBAL_UPGRADES),
				"Globals must contain both protocol globals or neither: count=" + gc);

		long version = s.getProtocolVersion();
		AVector<CVMLong> upgrades = s.getUpgradeVector();
		long n = upgrades.count();

		// Watermark bounds: 0 <= version <= count(upgrades)
		assertTrue(version >= 0, "Protocol version must be non-negative");
		assertTrue(version <= n, "Protocol version " + version + " exceeds upgrade vector count " + n);

		long ts = s.getTimestamp().longValue();
		long prev = Long.MIN_VALUE;
		for (long i = 0; i < n; i++) {
			CVMLong entry = upgrades.get(i);
			assertNotNull(entry, "Upgrade vector entry must be a Long at index " + i);
			long activation = entry.longValue();

			// Activations non-decreasing along the vector
			assertTrue(activation >= prev, "Upgrade activations must be non-decreasing at index " + i);

			if (i < version) {
				// Applied entries fired at or before the current state timestamp
				assertTrue(activation <= ts, "Applied activation at index " + i + " must not be after state timestamp");
			} else {
				// Pending entries are strictly in the future
				assertTrue(activation > ts, "Pending activation at index " + i + " must be after state timestamp");
			}
			prev = activation;
		}
	}

	@Test
	public void testRoundTrip() throws BadFormatException, IOException {
		State s = INIT_STATE;

		assertEquals(0,s.getRef().getStatus());

		Ref<State> rs = Cells.persist(s, Samples.TEST_STORE).getRef();
		assertEquals(Ref.PERSISTED, rs.getStatus());

		// TODO: consider if cached ref in state should now have persisted status?
		// assertTrue(s.getRef().isPersisted());

		Blob b = Cells.encode(s);
		State s2 = Samples.TEST_STORE.decode(b);
		assertEquals(s, s2);

		AccountStatus as=s2.getAccount(InitTest.HERO);
		assertNotNull(as);

		doStateTests(s2);
		RecordTest.doRecordTests(as);
	}
	
	@Test
	public void testMultiCellTrip() throws BadFormatException {
		State s = INIT_STATE;
		RefTreeStats rstats  = Refs.getRefTreeStats(s.getRef());

		//Refs.visitAllRefs(s.getRef(), r->{
		//	if (r.getHash().equals(check)) {
		//		System.out.println(r.getValue());
		//	}
		//});
		
		Blob b=Format.encodeMultiCell(s,true);
		
		State s2;
		s2=Samples.TEST_STORE.decodeMultiCell(b);
		// System.err.println(Refs.printMissingTree(s2));
		assertEquals(s,s2);
		
		RefTreeStats rstats2  = Refs.getRefTreeStats(s2.getRef());
		assertEquals(rstats.total,rstats2.total);
	}
	
	@SuppressWarnings("unused")
	@Test public void testStateRefs() throws IOException {
		AKeyPair kp=AKeyPair.createSeeded(578587);
		State s=Init.createState(Lists.of(kp.getAccountKey()));
		
		Ref<State> r1=Cells.persist(s, Samples.TEST_STORE).getRef();
		RefTreeStats rs1=Refs.getRefTreeStats(r1);
		
		assertTrue(r1.isPersisted());
		
		final long[] cnt=new long[1];
		Refs.visitAllRefs(r1, r->{
			ACell cell=r.getValue();
			//assertTrue(r.isPersisted(),()->"Not persisted: "+cell);
			//assertSame(r,cell.getRef(),()->"Inconsistent value: "+cell.getClass()+" = "+cell+" with ref "+r);
			cnt[0]++;
		});
		
		assertEquals(cnt[0],rs1.total);
		
		// TODO: figure out why not see #453
		//assertEquals(rs1.stored,rs1.persisted);
		//assertEquals(rs1.total,rs1.persisted);
	}
	
	@Test 
	public void testState() {
		State s=INIT_STATE;
		
		assertNotNull(s.lookupCNS("convex.core"));
		assertNull(s.lookupCNS("random.non.existent.namespace.56785"));
		
		assertNull(s.lookupCNS(""));
	
	}
}
