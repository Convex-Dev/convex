package convex.gui.binding;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.EventQueue;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.gui.models.StateModel;
import convex.lattice.LatticeComponent;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;

/** Headless tests exercise actual lattice state and Swing models without opening windows. */
public class BindingsTest {

	@Test void followsLatticeDraftAndPublicationWithoutWritingOnRead() throws Exception {
		var published=new LatticeComponent<AInteger>(MaxLattice.INSTANCE,Root.create(CVMLong.ZERO));
		var draft=published.fork();
		JLabel live=edt(JLabel::new);
		JLabel working=edt(JLabel::new);
		View owner=edt(View::new);
		try (var bindings=edt(() -> {
			var scope=Bindings.on(owner);
			scope.text(live,published::value);
			scope.text(working,() -> {
				assertFalse(EventQueue.isDispatchThread());
				return draft.value();
			});
			owner.showing(true);
			return scope;
		})) {
			await(() -> "0".equals(working.getText()));
			draft.cursor().set(CVMLong.create(7));
			// No explicit refresh: background domain edits must be found by sampling.
			await(() -> "7".equals(working.getText()));
			assertEquals(CVMLong.ZERO,published.value());
			assertEquals("0",edt(live::getText));
			draft.sync();
			await(() -> "7".equals(live.getText()));
			// An incoming merge also reaches the same display without a widget setter.
			published.merge(CVMLong.create(12));
			await(() -> "12".equals(live.getText()));
		}
	}

	@Test void equalTextPreservesSelectionAndNullClearsDisplay() throws Exception {
		AtomicReference<String> source=new AtomicReference<>("hello");
		AtomicInteger documents=new AtomicInteger();
		AtomicInteger rounds=new AtomicInteger();
		JTextArea text=edt(JTextArea::new);
		JButton button=edt(JButton::new);
		JLabel completed=edt(JLabel::new);
		try (var scope=edt(() -> {
			var bindings=Bindings.on(new View());
			text.getDocument().addDocumentListener(new DocumentListener() {
				public void insertUpdate(DocumentEvent e) { changed(); }
				public void removeUpdate(DocumentEvent e) { changed(); }
				public void changedUpdate(DocumentEvent e) { changed(); }
				private void changed() {
					assertTrue(EventQueue.isDispatchThread());
					documents.incrementAndGet();
				}
			});
			bindings.text(text,source::get);
			bindings.enabled(button,() -> source.get()!=null);
			bindings.text(completed,rounds::incrementAndGet);
			return bindings;
		})) {
			await(() -> "1".equals(completed.getText()));
			int before=documents.get();
			edt(() -> { text.select(1,4); return null; });
			source.set(new String("hello"));
			edt(() -> { scope.refresh(); return null; });
			await(() -> "2".equals(completed.getText()));
			assertEquals(before,documents.get());
			assertEquals("ell",edt(text::getSelectedText));
			source.set(null);
			edt(() -> { scope.refresh(); return null; });
			await(() -> "3".equals(completed.getText()));
			assertEquals("",edt(text::getText));
			assertFalse(edt(button::isEnabled));
		}
	}

	@Test void existingModelSubscribesOnlyWhileShowingAndCatchesUp() throws Exception {
		CountingModel model=new CountingModel();
		View owner=edt(View::new);
		JLabel label=edt(JLabel::new);
		Bindings scope=edt(() -> {
			var bindings=Bindings.on(owner,model);
			bindings.text(label,model::getValue);
			return bindings;
		});
		try {
			await(() -> "initial".equals(label.getText()));
			assertEquals(0,model.listeners.get());
			edt(() -> { owner.showing(true); return null; });
			assertEquals(1,model.listeners.get());
			model.setValue("changed");
			await(() -> "changed".equals(label.getText()));
			edt(() -> { owner.showing(false); return null; });
			assertEquals(0,model.listeners.get());
			model.setValue("hidden");
			edt(() -> { owner.showing(true); return null; });
			await(() -> "hidden".equals(label.getText()));
		} finally {
			scope.close(); // Same off-EDT cleanup path used by AbstractGUI.
		}
		edt(() -> {
			assertEquals(0,model.listeners.get());
			assertEquals(0,owner.getHierarchyListeners().length);
			assertThrows(IllegalStateException.class,() -> scope.text(label,model::getValue));
			return null;
		});
	}

	@Test void refreshBurstsCoalesceAndDoNotLoseAnUpdateDuringARead() throws Exception {
		AtomicInteger value=new AtomicInteger();
		AtomicInteger reads=new AtomicInteger();
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		JLabel label=edt(JLabel::new);
		try (var scope=edt(() -> {
			var bindings=Bindings.on(new View());
			bindings.text(label,() -> {
				int snapshot=value.get();
				if (reads.incrementAndGet()==1) {
					entered.countDown();
					waitFor(release);
				}
				return snapshot;
			});
			return bindings;
		})) {
			assertTrue(entered.await(10,TimeUnit.SECONDS));
			edt(() -> {
				for (int i=1;i<=100;i++) {
					value.set(i);
					scope.refresh();
				}
				return null;
			});
			release.countDown();
			await(() -> "100".equals(label.getText()));
			assertEquals(2,reads.get());
		} finally {
			release.countDown();
		}
	}

	@Test void hidingDiscardsAnInFlightSnapshotAndReshowReadsAgain() throws Exception {
		AtomicInteger value=new AtomicInteger();
		AtomicBoolean stale=new AtomicBoolean();
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		View owner=edt(View::new);
		JLabel label=edt(() -> new JLabel("before"));
		CountingModel model=new CountingModel();
		try (var scope=edt(() -> {
			owner.showing(true);
			var bindings=Bindings.on(owner,model);
			bindings.text(label,() -> {
				int snapshot=value.get();
				if (snapshot==0) {
					entered.countDown();
					waitFor(release);
				}
				return snapshot;
			});
			label.addPropertyChangeListener("text",e -> {
				if ("0".equals(e.getNewValue())) stale.set(true);
			});
			return bindings;
		})) {
			assertTrue(entered.await(10,TimeUnit.SECONDS));
			edt(() -> {
				owner.showing(false);
				value.set(1);
				owner.showing(true);
				return null;
			});
			release.countDown();
			await(() -> "1".equals(label.getText()));
			assertFalse(stale.get());
		} finally {
			release.countDown();
		}
	}

	@Test void closeSuppressesAnInFlightDelivery() throws Exception {
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		AtomicReference<Thread> worker=new AtomicReference<>();
		JLabel label=edt(() -> new JLabel("before"));
		try (var scope=edt(() -> {
			var bindings=Bindings.on(new View());
			bindings.text(label,() -> {
				worker.set(Thread.currentThread());
				entered.countDown();
				waitFor(release);
				return "after";
			});
			return bindings;
		})) {
			assertTrue(entered.await(10,TimeUnit.SECONDS));
			scope.close();
			release.countDown();
			worker.get().join(10_000); // Async has now queued its EDT completion.
			assertFalse(worker.get().isAlive());
			assertEquals("before",edt(label::getText));
		} finally {
			release.countDown();
		}
	}

	@Test void readFailureDoesNotPreventOtherBindingsOrRecovery() throws Exception {
		AtomicReference<String> value=new AtomicReference<>();
		JLabel failing=edt(JLabel::new);
		JLabel healthy=edt(JLabel::new);
		try (var scope=edt(() -> {
			var bindings=Bindings.on(new View());
			bindings.text(failing,() -> {
				if (value.get()==null) throw new IllegalStateException("Temporarily unavailable");
				return value.get();
			});
			bindings.text(healthy,() -> "available");
			return bindings;
		})) {
			await(() -> "available".equals(healthy.getText()));
			value.set("recovered");
			edt(() -> { scope.refresh(); return null; });
			await(() -> "recovered".equals(failing.getText()));
		}
	}

	private static void waitFor(CountDownLatch latch) {
		try {
			assertTrue(latch.await(10,TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static <T> T edt(Callable<T> action) throws Exception {
		AtomicReference<T> result=new AtomicReference<>();
		EventQueue.invokeAndWait(() -> {
			try { result.set(action.call()); }
			catch (Exception e) { throw new AssertionError(e); }
		});
		return result.get();
	}

	private static void await(Callable<Boolean> condition) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while (!edt(condition)) {
			if (System.nanoTime()>deadline) fail("Timed out waiting for bound display");
			Thread.sleep(5);
		}
	}

	@SuppressWarnings("serial")
	private static class View extends JPanel {
		private boolean showing;
		@Override public boolean isShowing() { return showing; }
		void showing(boolean showing) {
			this.showing=showing;
			var event=new HierarchyEvent(this,HierarchyEvent.HIERARCHY_CHANGED,this,null,HierarchyEvent.SHOWING_CHANGED);
			for (var listener:getHierarchyListeners()) listener.hierarchyChanged(event);
		}
	}

	private static class CountingModel extends StateModel<String> {
		final AtomicInteger listeners=new AtomicInteger();
		CountingModel() { super("initial"); }
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
