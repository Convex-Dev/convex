package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.RecordTest;
import convex.core.data.Ref;
import convex.core.init.Init;
import convex.core.init.InitConfigTest;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Tests for the State data structure
 */
public class StateTest {
	State INIT_STATE=Init.createState(InitConfigTest.create());

	@Test
	public void testEmptyState() {
		State s = State.EMPTY;
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

		RecordTest.doRecordTests(s);
	}

	@Test
	public void testRoundTrip() throws BadFormatException {
		State s = INIT_STATE;
		// TODO: fix this
		// s=s.store(Keywords.STATE);
		// assertEquals(1,s.getStore().size());

		assertEquals(0,s.getRef().getStatus());

		Ref<State> rs = ACell.createPersisted(s);
		assertEquals(Ref.PERSISTED, rs.getStatus());

		// Initial ref should now have persisted status
		assertTrue(s.getRef().isPersisted());

		Blob b = Format.encodedBlob(s);
		State s2 = Format.read(b);
		assertEquals(s, s2);

		AccountStatus as=s2.getAccount(InitConfigTest.HERO_ADDRESS);
		assertNotNull(as);
	}
}
