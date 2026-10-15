package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.MaxLattice;

class TransactionalRootCursorTest {

	private static Arguments writer(String name,Consumer<Root<AInteger>> action) {
		return Arguments.of(name,action);
	}

	static Stream<Arguments> writers() {
		return Stream.of(
			writer("set",r -> r.set(CVMLong.TWO)),
			writer("getAndSet",r -> r.getAndSet(CVMLong.TWO)),
			writer("compareAndSet",r -> r.compareAndSet(CVMLong.ZERO,CVMLong.TWO)),
			writer("updateAndGet",r -> r.updateAndGet(v -> CVMLong.TWO)),
			writer("getAndUpdate",r -> r.getAndUpdate(v -> CVMLong.TWO)),
			writer("accumulateAndGet",r -> r.accumulateAndGet(CVMLong.TWO,(a,b) -> b)),
			writer("getAndAccumulate",r -> r.getAndAccumulate(CVMLong.TWO,(a,b) -> b)),
			writer("view write",r -> r.view().set(CVMLong.TWO)),
			writer("incoming merge",r -> convex.lattice.ComponentTestSupport.merge(r,MaxLattice.INSTANCE,CVMLong.TWO)),
			writer("publication write-back",r -> r.sync(v -> CVMLong.TWO)));
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("writers")
	void everyWriterHonoursRootExclusion(String name,Consumer<Root<AInteger>> action) throws Exception {
		var root=new TransactionalRootCursor<AInteger>(CVMLong.ZERO, MaxLattice.INSTANCE);
		CountDownLatch inCallback=new CountDownLatch(1), writerStarted=new CountDownLatch(1);
		AtomicBoolean callbackRunning=new AtomicBoolean();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var writer=executor.submit(() -> {
				await(inCallback);
				writerStarted.countDown();
				action.accept(root);
				assertFalse(callbackRunning.get(),name+" completed during the exclusive callback");
			});
			Transactions.transactLocked(root.view(),draft -> {
				callbackRunning.set(true);
				try {
					inCallback.countDown();
					await(writerStarted);
					draft.set(CVMLong.ONE);
					assertEquals(CVMLong.ZERO,root.get(),"Staged changes and competing writes stay invisible");
				} finally { callbackRunning.set(false); }
			});
			writer.get(10,TimeUnit.SECONDS);
		}
		assertEquals(name.equals("compareAndSet")?CVMLong.ONE:CVMLong.TWO,root.get());
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
