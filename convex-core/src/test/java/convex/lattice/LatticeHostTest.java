package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;

/** Host delegation and publication policy, independent of persistence. */
public class LatticeHostTest {
	@Test
	public void testUnconfiguredHostDoesNotAdmitIncomingValues() {
		var host=LatticeHost.create();
		var initial=host.cursor().get();
		var key=Keyword.intern("unknown");
		host.merge(initial.assoc(key,CVMLong.ONE));
		host.mergeAt(CVMLong.TWO,key);
		assertSame(initial,host.cursor().get());
	}

	@Test
	public void testPublicationKeepsConcurrentWritesWithoutAMergeRule() {
		var host=LatticeHost.create(Sets.of(1),null);
		host.setPublicationPolicy(snapshot -> {
			host.cursor().set(Sets.of(2));
			return snapshot;
		});
		assertEquals(Sets.of(1),host.sync());
		assertEquals(Sets.of(2),host.cursor().get());
	}

	@Test
	public void testPublicationPolicyCanBeConfiguredAndFrozen() {
		var host=LatticeHost.create();
		host.setPublicationPolicy(value -> value);
		host.freezePublicationPolicy();
		assertThrows(IllegalStateException.class,() -> host.setPublicationPolicy(value -> value));
	}
}
