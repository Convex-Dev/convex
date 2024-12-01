package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.State;
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
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.init.InitTest;

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


		RecordTest.doRecordTests(s);
	}

	@Test
	public void testInitialState() throws InvalidDataException {
		State s = INIT_STATE;
		assertSame(s, s.withAccounts(s.getAccounts()));
		assertSame(s, s.withPeers(s.getPeers()));

		s.validate();
		
		assertEquals(s.getEncodingLength(),s.getEncoding().size());

		RecordTest.doRecordTests(s);
	}

	@Test
	public void testRoundTrip() throws BadFormatException, IOException {
		State s = INIT_STATE;

		assertEquals(0,s.getRef().getStatus());

		Ref<State> rs = Cells.persist(s).getRef();
		assertEquals(Ref.PERSISTED, rs.getStatus());

		// TODO: consider if cached ref in state should now have persisted status?
		// assertTrue(s.getRef().isPersisted());

		Blob b = Cells.encode(s);
		State s2 = Format.read(b);
		assertEquals(s, s2);

		AccountStatus as=s2.getAccount(InitTest.HERO);
		assertNotNull(as);
		
		RecordTest.doRecordTests(s2);
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
		
		State s2=Format.decodeMultiCell(b);
		// System.err.println(Refs.printMissingTree(s2));
		assertEquals(s,s2);
		
		RefTreeStats rstats2  = Refs.getRefTreeStats(s2.getRef());
		assertEquals(rstats.total,rstats2.total);
	}
	
	@SuppressWarnings("unused")
	@Test public void testStateRefs() throws IOException {
		AKeyPair kp=AKeyPair.createSeeded(578587);
		State s=Init.createState(Lists.of(kp.getAccountKey()));
		
		Ref<State> r1=Cells.persist(s).getRef();
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
}
