package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.RecordTest;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Tests for the State data structure
 */
public class StateTest {

	@Test
	public void testEmptyState() {
		State s = State.EMPTY;
		AVector<AccountStatus> accts = s.getAccounts();
		assertEquals(0, accts.count());
		assertEquals(Sets.empty(), s.getStore());
		
		RecordTest.doRecordTests(s);
	}

	@Test
	public void testInitialState() throws InvalidDataException {
		State s = Init.STATE;
		assertSame(s, s.withStore(s.getStore()));
		assertSame(s, s.withAccounts(s.getAccounts()));
		assertSame(s, s.withPeers(s.getPeers()));

		s.validate();
		
		RecordTest.doRecordTests(s);
	}

	@Test
	public void testRoundTrip() throws BadFormatException {
		State s = Init.STATE;
		// TODO: fix this
		// s=s.store(Keywords.STATE);
		// assertEquals(1,s.getStore().size());
		
		assertEquals(0,s.getRef().getStatus());

		Ref<State> rs = ACell.createPersisted(s);
		assertEquals(Ref.PERSISTED, rs.getStatus());
		
		// Initial ref should not have changed status
		assertEquals(0,s.getRef().getStatus());

		Blob b = Format.encodedBlob(s);
		State s2 = Format.read(b);
		assertEquals(s, s2);
		
		AccountStatus as=s2.getAccount(Init.HERO);
		assertNotNull(as);
	}
}
