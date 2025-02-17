package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.PeerStatus;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

@TestInstance(Lifecycle.PER_CLASS)
public class PeerStatusTest {
	
	@Test public void testEmpty() {
		PeerStatus ps=PeerStatus.create(null, 0);
		
		assertEquals(CVMLong.ZERO,ps.get(Keywords.DELEGATED_STAKE));
		doPeerStatusTest(ps);
	}
	
	@Test public void testFull() {
		PeerStatus ps=PeerStatus.create(Address.create(111), 7000);
		ps=ps.withDelegatedStake(Address.create(10000), 8000);
		ps=ps.withPeerData(Maps.of(Keywords.URL,"www.foobar.com"));
		doPeerStatusTest(ps);
	}
	
	private void doPeerStatusTest(PeerStatus ps)  {
		assertTrue(ps.isCanonical());
		
		try {
			ps.validateCell();
		} catch (InvalidDataException e) {
			throw Utils.sneakyThrow(e);
		}
		
		RecordTest.doRecordTests(ps);
	}

}
