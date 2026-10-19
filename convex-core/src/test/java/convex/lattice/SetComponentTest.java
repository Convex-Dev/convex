package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

class SetComponentTest {

	/** The boolean result describes this operation's committed change, not its first attempted edit. */
	@Test void aConcurrentIdenticalEditIsReportedAsUnchanged() {
		for (boolean adding:new boolean[] {true,false}) {
			var calls=new AtomicInteger();
			var root=new Root<ASet<CVMLong>>(adding?Sets.empty():Sets.of(1)) {
				@Override public ASet<CVMLong> getAndUpdate(UnaryOperator<ASet<CVMLong>> update) {
					return super.getAndUpdate(current -> {
						var candidate=update.apply(current);
						// Another writer completes this same edit before our first CAS.
						if (calls.incrementAndGet()==1) set(candidate);
						return candidate;
					});
				}
			};
			var set=new SetComponent<>(root);
			assertFalse(adding?set.add(CVMLong.ONE):set.remove(CVMLong.ONE));
			assertEquals(adding?Sets.of(1):Sets.empty(),set.get());
			assertTrue(calls.get()>1,"the test must actually exercise a retry");
		}
	}

	@Test void emptyReadsAndNoOpEditsDoNotCreateState() {
		var root=new Root<ASet<CVMLong>>();
		var set=new SetComponent<>(root);
		assertTrue(set.isEmpty());
		assertEquals(0,set.size());
		assertEquals(Sets.empty(),set.get());
		assertFalse(set.contains(CVMLong.ONE));
		assertFalse(set.remove(CVMLong.ONE));
		set.clear();
		set.sync();
		assertNull(root.get());
	}

	@Test void componentsSharePathEditsWithoutChangingSiblings() {
		var key=Keyword.intern("items");
		var sibling=Keyword.intern("other");
		var other=Sets.of(99);
		var root=Root.create(Index.<Keyword,ACell>of(sibling,other));
		var first=new SetComponent<CVMLong>(root.path(key));
		var second=new SetComponent<CVMLong>(root.path(key));
		var initial=root.get();
		assertFalse(first.remove(CVMLong.ONE));
		second.clear();
		assertSame(initial,root.get(),"no-op edits must not introduce a null entry at an absent path");
		assertTrue(first.add(CVMLong.ONE));
		assertFalse(second.add(CVMLong.ONE));
		assertTrue(second.contains(CVMLong.ONE));
		assertEquals(1,second.size());
		assertTrue(second.remove(CVMLong.ONE));
		assertFalse(first.remove(CVMLong.ONE));
		assertTrue(first.isEmpty());
		first.add(CVMLong.TWO);
		var snapshot=first.get();
		second.clear();
		assertTrue(first.isEmpty());
		assertEquals(Sets.of(2),snapshot,"returned values are immutable snapshots");
		assertSame(other,root.get().get(sibling));
	}
}
