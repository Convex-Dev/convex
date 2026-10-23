package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.Keyword;
import convex.core.data.ASet;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.SetLattice;

/** Host delegation and publication policy, independent of persistence. */
public class LatticeHostTest {
	/** Registration retains typed instances and pending state; delivery does not publish. */
	@Test
	public void testAddedApplicationsKeepTheirStateAndPublicationBoundaries() {
		var host=LatticeHost.<ASet<CVMLong>>create(Sets.empty(),null);
		var first=new LatticeApplication<>(SetLattice.<CVMLong>create(),host);
		var context=new LatticeContext() {};
		first.setContext(context);
		first.cursor().set(Sets.of(1));
		assertSame(first,host.addApplication(first));
		var second=host.addApplication(new LatticeApplication<>(SetLattice.<CVMLong>create(),host));
		assertSame(context,first.getContext());
		assertEquals(Sets.of(1),first.cursor().get());
		assertTrue(host.cursor().get().isEmpty());
		host.merge(Sets.of(2));
		assertEquals(Sets.of(1,2),first.cursor().get());
		assertEquals(Sets.of(2),second.cursor().get());
		assertTrue(host.cursor().get().isEmpty());
		first.sync();
		second.sync();
		assertEquals(Sets.of(1,2),host.cursor().get());
	}

	/** Incremental additions preserve delivery order; failed registration changes nothing. */
	@Test
	public void testAddAfterDeliveryPreservesDeliveryOrder() {
		var host=LatticeHost.create(CVMLong.ZERO,null);
		var received=new ArrayList<Integer>();
		class Receiver extends ALatticeComponent<CVMLong> {
			private final int id;
			Receiver(int id) { super(host.cursor()); this.id=id; }
			@Override public CVMLong merge(CVMLong incoming) {
				received.add(id);
				return cursor().get();
			}
		}
		host.addApplication(new Receiver(1));
		assertThrows(NullPointerException.class,() -> host.addApplication(null));
		host.addApplication(new Receiver(2));
		host.addApplication(new Receiver(3));
		host.mergeAt(CVMLong.ONE);
		assertEquals(List.of(1,2,3),received);
		assertSame(CVMLong.ZERO,host.cursor().get());
		host.addApplication(new Receiver(4));
		assertEquals(List.of(1,2,3),received,"Registration does not replay input");
		received.clear();
		host.merge(CVMLong.TWO);
		assertEquals(List.of(1,2,3,4),received);
	}

	/** Root and path deliveries retain their snapshot while registrations and other deliveries proceed. */
	@Test
	public void testConcurrentDeliveryUsesStableRegistrationSnapshots() throws Exception {
		var host=LatticeHost.create(CVMLong.ZERO,null);
		var entered=new CountDownLatch(2);
		var release=new CountDownLatch(1);
		host.addApplication(new ALatticeComponent<CVMLong>(host.cursor()) {
			@Override public CVMLong merge(CVMLong incoming) {
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError(e);
				}
				return cursor().get();
			}
		});
		var lateDeliveries=new AtomicInteger();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var first=executor.submit(() -> host.merge(CVMLong.ONE));
			var second=executor.submit(() -> host.mergeAt(CVMLong.TWO));
			try {
				assertTrue(entered.await(5,TimeUnit.SECONDS),"Incoming callbacks can run concurrently");
				host.addApplication(new ALatticeComponent<CVMLong>(host.cursor()) {
					@Override public CVMLong merge(CVMLong incoming) {
						lateDeliveries.incrementAndGet();
						return cursor().get();
					}
				});
			} finally {
				release.countDown();
			}
			assertSame(CVMLong.ZERO,first.get(5,TimeUnit.SECONDS));
			assertSame(CVMLong.ZERO,second.get(5,TimeUnit.SECONDS));
		}
		assertEquals(0,lateDeliveries.get(),"In-flight deliveries keep their original registrations");
		host.merge(CVMLong.ONE);
		host.mergeAt(CVMLong.TWO);
		assertEquals(2,lateDeliveries.get(),"Subsequent root and path deliveries include the addition");
	}

	/** An empty delivery leaves the host open for additions, without replaying discarded input. */
	@Test
	public void testAddAfterEmptyDelivery() {
		for (boolean path:new boolean[] {false,true}) {
			var host=LatticeHost.<ASet<CVMLong>>create(Sets.empty(),null);
			if (path) host.mergeAt(Sets.of(1));
			else host.merge(Sets.of(1));
			assertTrue(host.cursor().get().isEmpty());
			var app=host.addApplication(new LatticeApplication<>(SetLattice.<CVMLong>create(),host));
			assertTrue(app.cursor().get().isEmpty());
			host.merge(Sets.of(2));
			assertEquals(Sets.of(2),app.cursor().get());
			assertTrue(host.cursor().get().isEmpty(),"Registration and delivery do not publish staged state");
			app.sync();
			assertEquals(Sets.of(2),host.cursor().get());
		}
	}

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
