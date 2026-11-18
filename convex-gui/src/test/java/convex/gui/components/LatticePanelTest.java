package convex.gui.components;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.EventQueue;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.gui.models.StateModel;
import convex.gui.utils.UpdateTrigger;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeComponent;
import convex.lattice.ValueComponent;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;

/** Headless tests of whole-snapshot delivery, trigger lifetimes and domain boundaries. */
public class LatticePanelTest {

	@Test void pollingFollowsWorkingAndPublishedComponentsIndependently() throws Exception {
		var published=new LatticeComponent<AInteger>(MaxLattice.INSTANCE,Root.create(CVMLong.ZERO));
		var draft=published.fork();
		try (var live=edt(() -> new Probe<>(published)); var working=edt(() -> new Probe<>(draft))) {
			edt(() -> { live.showing(true); working.showing(true); return null; });
			assertEquals(CVMLong.ZERO,take(live.renders).value());
			assertEquals(CVMLong.ZERO,take(working.renders).value());
			draft.cursor().set(CVMLong.create(7));
			assertEquals(CVMLong.create(7),take(working.renders).value());
			assertEquals(CVMLong.ZERO,published.value());
			draft.sync();
			assertEquals(CVMLong.create(7),take(live.renders).value());
			published.merge(CVMLong.create(12));
			assertEquals(CVMLong.create(12),take(live.renders).value());
			// Syncing a draft that fell behind brings the greater published value into its view
			draft.sync();
			assertEquals(CVMLong.create(12),take(working.renders).value());
		}
	}

	@Test void readsOneSnapshotOffEDTAndSkipsEqualValues() throws Exception {
		Root<ACell> root=Root.create(Maps.of("name","initial","count",1));
		BlockingQueue<Thread> readers=new LinkedBlockingQueue<>();
		try (var view=edt(() -> new Probe<>(() -> {
			readers.add(Thread.currentThread());
			return root.get();
		},refresh -> () -> {}))) {
			edt(() -> { view.showing(true); return null; });
			var first=take(view.renders);
			assertEquals(root.get(),first.value());
			assertFalse(take(readers).getName().startsWith("AWT-EventQueue"));
			assertTrue(first.edt());
			root.set(Maps.of("name","initial","count",1));
			view.refresh();
			finish(take(readers));
			assertTrue(view.renders.isEmpty(),"An equal value must not rerender the view");
			root.set(Maps.of("name","changed","count",2));
			view.refresh();
			var second=take(view.renders);
			assertEquals(Maps.of("name","changed","count",2),second.value());
			assertEquals(Maps.of("name","initial","count",1),first.value());
		}
	}

	@Test void legacyModelSubscribesOnlyWhileShowingAndCatchesUp() throws Exception {
		CountingModel model=new CountingModel();
		Probe<ACell> view=edt(() -> new Probe<>(model::getValue,UpdateTrigger.changes(model)));
		try {
			edt(() -> { assertTrue(view.renders.isEmpty()); return null; });
			assertEquals(0,model.listeners.get());
			edt(() -> { view.showing(true); return null; });
			assertEquals(Strings.create("initial"),take(view.renders).value());
			assertEquals(1,model.listeners.get());
			model.setValue(Strings.create("changed"));
			assertEquals(Strings.create("changed"),take(view.renders).value());
			edt(() -> { view.showing(false); return null; });
			assertEquals(0,model.listeners.get());
			model.setValue(Strings.create("hidden"));
			edt(() -> { view.showing(true); return null; });
			assertEquals(Strings.create("hidden"),take(view.renders).value());
		} finally { view.close(); }
		edt(() -> {
			assertEquals(0,model.listeners.get());
			assertEquals(0,view.getHierarchyListeners().length);
			return null;
		});
	}

	@Test void backgroundSignalsCoalesceWithoutLosingAnUpdateDuringRead() throws Exception {
		AtomicInteger value=new AtomicInteger(),reads=new AtomicInteger();
		AtomicReference<Runnable> signal=new AtomicReference<>();
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		try (var view=edt(() -> new Probe<>(() -> {
			int snapshot=value.get();
			if (reads.incrementAndGet()==1) { entered.countDown(); waitFor(release); }
			return CVMLong.create(snapshot);
		},refresh -> { signal.set(refresh); return () -> signal.set(null); }))) {
			edt(() -> { view.showing(true); return null; });
			assertTrue(entered.await(10,TimeUnit.SECONDS));
			for (int i=1;i<=100;i++) { value.set(i); signal.get().run(); }
			edt(() -> null); // All signals reached the EDT while the read was blocked.
			release.countDown();
			assertEquals(CVMLong.ZERO,take(view.renders).value());
			assertEquals(CVMLong.create(100),take(view.renders).value());
			assertEquals(2,reads.get());
		} finally { release.countDown(); }
	}

	@Test void hidingDiscardsOldSnapshotAndReshowReadsAgain() throws Exception {
		AtomicInteger value=new AtomicInteger();
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		try (var view=edt(() -> new Probe<>(() -> {
			int snapshot=value.get();
			if (snapshot==0) { entered.countDown(); waitFor(release); }
			return CVMLong.create(snapshot);
		},refresh -> () -> {}))) {
			edt(() -> { view.showing(true); return null; });
			assertTrue(entered.await(10,TimeUnit.SECONDS));
			edt(() -> { view.showing(false); value.set(1); view.showing(true); return null; });
			release.countDown();
			assertEquals(CVMLong.ONE,take(view.renders).value());
		} finally { release.countDown(); }
	}

	@Test void closeSuppressesAnInFlightDelivery() throws Exception {
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		AtomicReference<Thread> worker=new AtomicReference<>();
		try (var view=edt(() -> new Probe<>(() -> {
			worker.set(Thread.currentThread());
			entered.countDown();
			waitFor(release);
			return CVMLong.ONE;
		},refresh -> () -> {}))) {
			edt(() -> { view.showing(true); return null; });
			assertTrue(entered.await(10,TimeUnit.SECONDS));
			view.close();
			release.countDown();
			finish(worker.get());
			assertTrue(view.renders.isEmpty());
		} finally { release.countDown(); }
	}

	@Test void absenceAndStoredNilAreSeparateSnapshots() throws Exception {
		Keyword key=Keyword.intern("item");
		Root<AHashMap<Keyword,ACell>> root=Root.create(Maps.empty());
		var component=new ValueComponent<ACell>(root.path(key));
		try (var view=edt(() -> new Probe<>(component,refresh -> () -> {}))) {
			edt(() -> { view.showing(true); return null; });
			assertFalse(take(view.renders).present());
			assertTrue(root.get().isEmpty(),"Reading an absent location creates no state");
			root.set(Maps.of(key,null));
			view.refresh();
			var nil=take(view.renders);
			assertTrue(nil.present());
			assertNull(nil.value());
			root.set(Maps.empty());
			view.refresh();
			assertFalse(take(view.renders).present());
		}
	}

	@Test void readAndRenderFailuresCanRetryTheSameValue() throws Exception {
		var expected=new IllegalStateException("Expected failure");
		AtomicReference<RuntimeException> readFailure=new AtomicReference<>(expected);
		try (var view=edt(() -> new Probe<>(() -> {
			var failure=readFailure.getAndSet(null);
			if (failure!=null) throw failure;
			return CVMLong.ONE;
		},refresh -> () -> {}))) {
			edt(() -> { view.showing(true); return null; });
			assertSame(expected,take(view.failures));
			view.renderFailure.set(expected);
			view.refresh();
			assertSame(expected,take(view.failures));
			view.refresh();
			assertEquals(CVMLong.ONE,take(view.renders).value());
		}
	}

	private static void waitFor(CountDownLatch latch) {
		try { if (!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Worker timed out"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}

	private static <T> T edt(Callable<T> action) throws Exception {
		FutureTask<T> task=new FutureTask<>(action);
		EventQueue.invokeAndWait(task);
		return task.get();
	}

	private static <T> T take(BlockingQueue<T> queue) throws Exception {
		T value=queue.poll(10,TimeUnit.SECONDS);
		assertNotNull(value,"Timed out waiting for view update");
		return value;
	}

	private static void finish(Thread reader) throws Exception {
		reader.join(10_000); // Async has queued its completion before the worker ends.
		assertFalse(reader.isAlive());
		edt(() -> null);
	}

	private record Rendered<V>(boolean present,V value,boolean edt) {}

	@SuppressWarnings("serial")
	private static class Probe<V extends ACell> extends LatticePanel<V> {
		private boolean showing;
		final BlockingQueue<Rendered<V>> renders=new LinkedBlockingQueue<>();
		final BlockingQueue<Throwable> failures=new LinkedBlockingQueue<>();
		final AtomicReference<RuntimeException> renderFailure=new AtomicReference<>();
		Probe(ALatticeComponent<V> component) { super(component); }
		Probe(ALatticeComponent<V> component,UpdateTrigger trigger) { super(component,trigger); }
		Probe(Supplier<V> source,UpdateTrigger trigger) { super(source,trigger); }
		@Override protected void render(V value) {
			var failure=renderFailure.getAndSet(null);
			if (failure!=null) throw failure;
			renders.add(new Rendered<>(true,value,EventQueue.isDispatchThread()));
		}
		@Override protected void renderAbsent() { renders.add(new Rendered<>(false,null,EventQueue.isDispatchThread())); }
		@Override protected void onFailure(Throwable failure) { failures.add(failure); }
		@Override public boolean isShowing() { return showing; }
		void showing(boolean showing) {
			this.showing=showing;
			var event=new HierarchyEvent(this,HierarchyEvent.HIERARCHY_CHANGED,this,null,HierarchyEvent.SHOWING_CHANGED);
			for (var listener:getHierarchyListeners()) listener.hierarchyChanged(event);
		}
	}

	private static class CountingModel extends StateModel<ACell> {
		final AtomicInteger listeners=new AtomicInteger();
		CountingModel() { super(Strings.create("initial")); }
		@Override public void addPropertyChangeListener(PropertyChangeListener listener) {
			super.addPropertyChangeListener(listener);
			listeners.incrementAndGet();
		}
		@Override public void removePropertyChangeListener(PropertyChangeListener listener) {
			super.removePropertyChangeListener(listener);
			listeners.decrementAndGet();
		}
	}
}
