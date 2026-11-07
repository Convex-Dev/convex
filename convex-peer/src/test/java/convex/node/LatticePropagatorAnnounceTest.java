package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.message.Message;
import convex.core.store.MemoryStore;
import convex.core.util.ThreadUtils;

/** Waiting for a group to announce a state: {@link LatticePropagator#whenAnnounced(java.util.function.Predicate)}. */
class LatticePropagatorAnnounceTest {

	/** The latest announced value satisfies a wait at once; otherwise the first later match does. */
	@Test void matchesTheLatestOrALaterAnnouncement() throws Exception {
		Blob first=Blobs.createRandom(400);
		Blob second=Blobs.createRandom(400);
		try (MemoryStore store=new MemoryStore()) {
			LatticePropagator group=new LatticePropagator(store);
			CompletableFuture<ACell> any=group.whenAnnounced();
			CompletableFuture<ACell> forSecond=group.whenAnnounced(second::equals);
			assertFalse(any.isDone(),"nothing announced yet");

			group.processSnapshot(first);
			assertEquals(first,any.getNow(null));
			assertFalse(forSecond.isDone(),"an announcement that does not match is passed over");
			assertEquals(first,group.whenAnnounced().getNow(null),"the latest announcement satisfies a new wait");

			group.processSnapshot(second);
			assertEquals(second,forSecond.getNow(null));
			assertEquals(second,group.whenAnnounced(second::equals).getNow(null));
		}
	}

	/**
	 * A value already visible in the serving view does not satisfy a wait until its
	 * delta broadcast is queued, so the broadcast count includes it (#778 follow-up).
	 */
	@Test void completesOnlyOnceTheBroadcastIsQueued() throws Exception {
		Blob value=Blobs.createRandom(400);
		CountDownLatch sending=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		try (MemoryStore store=new MemoryStore()) {
			LatticeConnectionManager routes=new LatticeConnectionManager(store) {
				@Override public boolean hasPropagationRoutes() { return true; }
				@Override public int broadcastSequence(List<Message> messages) {
					sending.countDown();
					try {
						release.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return 0;
				}
			};
			LatticePropagator group=new LatticePropagator(store,routes);
			try {
				CompletableFuture<ACell> announcing=CompletableFuture.supplyAsync(() -> {
					try {
						return group.processSnapshot(value);
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				},ThreadUtils.getVirtualExecutor());
				assertTrue(sending.await(10,TimeUnit.SECONDS));

				assertEquals(value,group.getLastAnnouncedValue(),"the served view already holds it");
				CompletableFuture<ACell> announced=group.whenAnnounced(value::equals);
				assertFalse(announced.isDone(),"its broadcast is not yet queued");
				assertEquals(0L,group.getBroadcastCount());

				release.countDown();
				assertEquals(value,announced.get(10,TimeUnit.SECONDS));
				assertEquals(1L,group.getBroadcastCount());
				announcing.get(10,TimeUnit.SECONDS);
			} finally {
				release.countDown();
			}
		}
	}

	/** A condition that throws fails its own wait; publication and other waits carry on. */
	@Test void failingConditionFailsOnlyItsWait() throws Exception {
		Blob value=Blobs.createRandom(400);
		try (MemoryStore store=new MemoryStore()) {
			LatticePropagator group=new LatticePropagator(store);
			CompletableFuture<ACell> broken=group.whenAnnounced(v -> {
				throw new IllegalStateException("broken condition");
			});
			CompletableFuture<ACell> healthy=group.whenAnnounced(value::equals);

			assertEquals(value,group.processSnapshot(value));
			ExecutionException failure=assertThrows(ExecutionException.class,() -> broken.get(10,TimeUnit.SECONDS));
			assertInstanceOf(IllegalStateException.class,failure.getCause());
			assertEquals(value,healthy.getNow(null));
		}
	}

	/** A wait still pending when the group stops fails; an announcement already made still satisfies one. */
	@Test void pendingWaitFailsWhenTheGroupStops() throws Exception {
		Blob value=Blobs.createRandom(400);
		try (MemoryStore store=new MemoryStore()) {
			LatticePropagator group=new LatticePropagator(store);
			group.processSnapshot(value);
			CompletableFuture<ACell> never=group.whenAnnounced(v -> false);

			group.close();
			ExecutionException failure=assertThrows(ExecutionException.class,() -> never.get(10,TimeUnit.SECONDS));
			assertInstanceOf(IllegalStateException.class,failure.getCause());
			assertTrue(group.whenAnnounced(v -> false).isCompletedExceptionally(),
				"nothing further will be announced");
			assertEquals(value,group.whenAnnounced().getNow(null));
		}
	}
}
