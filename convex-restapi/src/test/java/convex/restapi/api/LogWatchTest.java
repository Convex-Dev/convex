package convex.restapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.restapi.api.ConsensusLogScanner.LogEvent;
import convex.restapi.api.LogWatch.Filter;
import convex.restapi.api.LogWatch.Format;

class LogWatchTest {

	private static final Address TOKEN=Address.create(42);
	private static final ACell SCOPE=Reader.read(":USD");
	private static final ACell EVENT=Reader.read(":TRANSFER");
	private static final LogEvent LOG_EVENT=new LogEvent(12,3,4,
		Vectors.of(TOKEN,SCOPE,Vectors.of(12L,3L),Vectors.of(EVENT,100L)));

	@Test
	void filtersAddressEventAndScopeIndependently() {
		assertTrue(filter(Set.of(TOKEN),Set.of(),Set.of()).matches(LOG_EVENT));
		assertTrue(filter(Set.of(TOKEN),Set.of(EVENT),Set.of(SCOPE)).matches(LOG_EVENT));
		assertFalse(filter(Set.of(Address.create(43)),Set.of(),Set.of()).matches(LOG_EVENT));
		assertFalse(filter(Set.of(TOKEN),Set.of(Reader.read(":MINT")),Set.of()).matches(LOG_EVENT));
		assertFalse(filter(Set.of(TOKEN),Set.of(),Set.of(Reader.read(":GBP"))).matches(LOG_EVENT));
	}

	@Test
	void nilIsAValidExactScopeFilter() {
		HashSet<ACell> nilScope=new HashSet<>();
		nilScope.add(null);
		LogEvent unscoped=new LogEvent(1,0,0,
			Vectors.of(TOKEN,null,Vectors.of(1L,0L),Vectors.of(EVENT)));
		assertTrue(filter(Set.of(TOKEN),Set.of(),nilScope).matches(unscoped));
		assertFalse(filter(Set.of(TOKEN),Set.of(),nilScope).matches(LOG_EVENT));
	}

	@Test
	void encodesStableIDAndEquivalentJSONAndCVXEnvelopes() {
		assertEquals("12:3:4",LogWatch.eventID(LOG_EVENT));

		AMap<ACell,ACell> json=JSON.parse(LogWatch.encode(LOG_EVENT,Format.JSON));
		AMap<ACell,ACell> cvx=Reader.read(LogWatch.encode(LOG_EVENT,Format.CVX));
		assertEquals(CVMLong.create(12),json.getIn("block"));
		assertEquals(CVMLong.create(12),cvx.getIn(Reader.read(":block")));
		AVector<ACell> cvxEntry=cvx.getIn(Reader.read(":entry"));
		assertEquals(LOG_EVENT.entry(),cvxEntry);
	}

	private static Filter filter(Set<Address> addresses, Set<ACell> events, Set<ACell> scopes) {
		return new Filter(addresses,events,scopes);
	}
}
