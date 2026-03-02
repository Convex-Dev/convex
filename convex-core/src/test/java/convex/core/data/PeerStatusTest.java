package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
import convex.core.cvm.Keywords;
import convex.core.cvm.PeerStatus;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

@TestInstance(Lifecycle.PER_CLASS)
public class PeerStatusTest {

	private static final Address CONTROLLER = Address.create(111);
	private static final Address DELEGATOR1 = Address.create(10000);
	private static final Address DELEGATOR2 = Address.create(20000);

	@Test public void testEmpty() {
		PeerStatus ps=PeerStatus.create(null, 0);

		assertEquals(CVMLong.ZERO,ps.get(Keywords.DELEGATED_STAKE));
		doPeerStatusTest(ps);
	}

	@Test public void testFull() {
		PeerStatus ps=PeerStatus.create(CONTROLLER, 7000);
		ps=ps.withDelegatedStake(DELEGATOR1, 8000);
		ps=ps.withPeerData(Maps.of(Keywords.URL,"www.foobar.com"));
		doPeerStatusTest(ps);
	}

	// ---- Encode/decode round-trip tests ----

	@Test public void testRoundTripEmpty() throws BadFormatException {
		PeerStatus ps = PeerStatus.create(CONTROLLER, 0);
		PeerStatus decoded = roundTrip(ps);

		assertEquals(CONTROLLER, decoded.getController());
		assertEquals(0, decoded.getBalance());
		assertEquals(0, decoded.getTotalStakeShares());
		assertNotNull(decoded.getStakes());
		assertEquals(0, decoded.getStakes().count());

		doPeerStatusTest(decoded);
	}

	@Test public void testRoundTripWithStakesAndMetadata() throws BadFormatException {
		PeerStatus ps = PeerStatus.create(CONTROLLER, 5000);
		ps = ps.withDelegatedStake(DELEGATOR1, 3000);
		ps = ps.withDelegatedStake(DELEGATOR2, 2000);
		ps = ps.withPeerData(Maps.of(Keywords.URL, "peer.example.com"));

		PeerStatus decoded = roundTrip(ps);

		// Verify all fields survive round-trip
		assertEquals(CONTROLLER, decoded.getController());
		assertEquals(ps.getBalance(), decoded.getBalance());
		assertEquals(ps.getTotalStakeShares(), decoded.getTotalStakeShares());
		assertEquals(ps.getTimestamp(), decoded.getTimestamp());

		// Stakes must be accessible after decode
		Index<Address, CVMLong> stakes = decoded.getStakes();
		assertNotNull(stakes, "getStakes() should not return null after decode");
		assertEquals(2, stakes.count());
		assertEquals(CVMLong.create(3000), stakes.get(DELEGATOR1));
		assertEquals(CVMLong.create(2000), stakes.get(DELEGATOR2));

		// Metadata must be accessible after decode
		AHashMap<ACell, ACell> meta = decoded.getMetadata();
		assertNotNull(meta, "getMetadata() should not return null after decode");
		assertEquals(Strings.create("peer.example.com"), meta.get(Keywords.URL));
		assertEquals(Strings.create("peer.example.com"), decoded.getHostname());

		doPeerStatusTest(decoded);
	}

	@Test public void testRoundTripNoMetadata() throws BadFormatException {
		PeerStatus ps = PeerStatus.create(CONTROLLER, 1000, null);
		PeerStatus decoded = roundTrip(ps);

		// Metadata can be null when not set — that's fine as long as getHostname handles it
		AHashMap<ACell, ACell> meta = decoded.getMetadata();
		assertEquals(null, decoded.getHostname());

		doPeerStatusTest(decoded);
	}

	// ---- Mutation after decode tests ----

	@Test public void testWithDelegatedStakeAfterDecode() throws BadFormatException {
		// Create a PeerStatus with metadata, encode/decode, then mutate stakes
		// This tests whether the lazy null sentinel for metadata causes data loss
		PeerStatus ps = PeerStatus.create(CONTROLLER, 5000);
		ps = ps.withPeerData(Maps.of(Keywords.URL, "peer.example.com"));

		PeerStatus decoded = roundTrip(ps);

		// Add delegated stake to the decoded PeerStatus
		PeerStatus mutated = decoded.withDelegatedStake(DELEGATOR1, 2000);

		// Metadata must survive the stake mutation
		assertNotNull(mutated.getMetadata(), "Metadata should survive withDelegatedStake after decode");
		assertEquals(Strings.create("peer.example.com"), mutated.getHostname(),
				"Hostname should survive withDelegatedStake after decode");

		// Stake should be set correctly
		assertEquals(2000, mutated.getDelegatedStake());
		assertEquals(CVMLong.create(2000), mutated.getStakes().get(DELEGATOR1));

		doPeerStatusTest(mutated);
	}

	@Test public void testWithPeerStakeAfterDecode() throws BadFormatException {
		// Create with stakes and metadata, decode, then change peer stake
		PeerStatus ps = PeerStatus.create(CONTROLLER, 5000);
		ps = ps.withDelegatedStake(DELEGATOR1, 3000);
		ps = ps.withPeerData(Maps.of(Keywords.URL, "peer.example.com"));

		PeerStatus decoded = roundTrip(ps);
		PeerStatus mutated = decoded.withPeerStake(6000);

		// Both metadata and stakes must survive
		assertNotNull(mutated.getMetadata(), "Metadata should survive withPeerStake after decode");
		assertEquals(Strings.create("peer.example.com"), mutated.getHostname());
		assertNotNull(mutated.getStakes(), "Stakes should survive withPeerStake after decode");
		assertEquals(CVMLong.create(3000), mutated.getStakes().get(DELEGATOR1));

		doPeerStatusTest(mutated);
	}

	@Test public void testWithPeerDataAfterDecode() throws BadFormatException {
		// Create with stakes, decode, then set metadata
		PeerStatus ps = PeerStatus.create(CONTROLLER, 5000);
		ps = ps.withDelegatedStake(DELEGATOR1, 3000);

		PeerStatus decoded = roundTrip(ps);
		PeerStatus mutated = decoded.withPeerData(Maps.of(Keywords.URL, "new-peer.example.com"));

		// Stakes must survive the metadata mutation
		assertNotNull(mutated.getStakes(), "Stakes should survive withPeerData after decode");
		assertEquals(CVMLong.create(3000), mutated.getStakes().get(DELEGATOR1));
		assertEquals(Strings.create("new-peer.example.com"), mutated.getHostname());

		doPeerStatusTest(mutated);
	}

	// ---- Equality tests ----

	@Test public void testEquality() {
		PeerStatus ps1 = PeerStatus.create(CONTROLLER, 5000);
		ps1 = ps1.withDelegatedStake(DELEGATOR1, 3000);
		ps1 = ps1.withPeerData(Maps.of(Keywords.URL, "peer.example.com"));

		PeerStatus ps2 = PeerStatus.create(CONTROLLER, 5000);
		ps2 = ps2.withDelegatedStake(DELEGATOR1, 3000);
		ps2 = ps2.withPeerData(Maps.of(Keywords.URL, "peer.example.com"));

		assertNotSame(ps1, ps2);
		assertEquals(ps1, ps2);
	}

	@Test public void testEqualityAfterDecode() throws BadFormatException {
		PeerStatus ps = PeerStatus.create(CONTROLLER, 5000);
		ps = ps.withDelegatedStake(DELEGATOR1, 3000);
		ps = ps.withPeerData(Maps.of(Keywords.URL, "peer.example.com"));

		PeerStatus decoded = roundTrip(ps);

		assertNotSame(ps, decoded);
		assertEquals(ps, decoded);
	}

	@Test public void testInequality() {
		PeerStatus ps1 = PeerStatus.create(CONTROLLER, 5000);
		PeerStatus ps2 = PeerStatus.create(CONTROLLER, 6000);

		assertFalse(ps1.equals(ps2));
	}

	// ---- getDelegatedStake for specific delegator ----

	@Test public void testGetDelegatedStakeAfterDecode() throws BadFormatException {
		PeerStatus ps = PeerStatus.create(CONTROLLER, 5000);
		ps = ps.withDelegatedStake(DELEGATOR1, 3000);
		ps = ps.withDelegatedStake(DELEGATOR2, 2000);

		PeerStatus decoded = roundTrip(ps);

		// Individual delegator stakes should be accessible
		assertTrue(decoded.getDelegatedStake(DELEGATOR1) > 0, "Delegator 1 stake should be positive");
		assertTrue(decoded.getDelegatedStake(DELEGATOR2) > 0, "Delegator 2 stake should be positive");
		assertEquals(0, decoded.getDelegatedStake(Address.create(99999)), "Unknown delegator should have 0 stake");
	}

	// ---- Helpers ----

	/**
	 * Encode and decode a PeerStatus through CAD3, returning a fresh instance
	 * constructed via the values vector constructor (lazy loading path).
	 */
	private PeerStatus roundTrip(PeerStatus ps) throws BadFormatException {
		Blob encoded = Format.encodeMultiCell(ps, true);
		PeerStatus decoded = (PeerStatus) CVMEncoder.INSTANCE.decodeMultiCell(encoded);
		assertNotSame(ps, decoded, "Decoded PeerStatus should be a new instance");
		return decoded;
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
