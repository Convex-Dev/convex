package convex.gui.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.ListModel;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.gui.components.account.KeyPairCombo.KeyPairModel;

/**
 * Tests for the key ring and its Swing views. These need no display.
 */
public class KeyRingTest {

	private static AWalletEntry entry(String name) {
		return HotWalletEntry.create(AKeyPair.generate(), name);
	}

	/** Waits until the EDT has applied every key ring change posted so far */
	private static void flushEDT() throws Exception {
		EventQueue.invokeAndWait(()->{});
	}

	/** Listener recording, for each event, whether it arrived on the EDT */
	private static ListDataListener recorder(List<Boolean> onEDT) {
		return new ListDataListener() {
			@Override
			public void intervalAdded(ListDataEvent e) {
				onEDT.add(EventQueue.isDispatchThread());
			}

			@Override
			public void intervalRemoved(ListDataEvent e) {
				onEDT.add(EventQueue.isDispatchThread());
			}

			@Override
			public void contentsChanged(ListDataEvent e) {
				onEDT.add(EventQueue.isDispatchThread());
			}
		};
	}

	@Test
	public void testAddFindRemove() {
		KeyRing ring=new KeyRing();
		AWalletEntry a=entry("a");
		assertTrue(ring.add(a));
		assertSame(a, ring.find(a.getPublicKey()));

		// A second entry for the same key is ignored
		assertFalse(ring.add(HotWalletEntry.create(a.getKeyPair(), "duplicate")));
		assertEquals(List.of(a), ring.getEntries());

		assertTrue(ring.remove(a));
		assertFalse(ring.remove(a));
		assertNull(ring.find(a.getPublicKey()));
		assertNull(ring.find(null));
	}

	@Test
	public void testViewFollowsOnEDT() throws Exception {
		KeyRing ring=new KeyRing();
		List<Boolean> onEDT=Collections.synchronizedList(new ArrayList<>());
		ring.listModel().addListDataListener(recorder(onEDT));

		// Changed from the test thread, applied on the EDT in key ring order
		AWalletEntry a=entry("a");
		AWalletEntry b=entry("b");
		ring.add(a);
		ring.add(b);
		ring.remove(a);
		flushEDT();

		List<AWalletEntry> shown=new ArrayList<>();
		EventQueue.invokeAndWait(()->{
			ListModel<AWalletEntry> view=ring.listModel();
			for (int i=0; i<view.getSize(); i++) shown.add(view.getElementAt(i));
		});
		assertEquals(List.of(b), shown);
		assertEquals(List.of(true,true,true), onEDT);
	}

	@Test
	public void testKeyPairModels() throws Exception {
		KeyRing ring=new KeyRing();
		AWalletEntry a=entry("a");
		ring.add(a);
		flushEDT();

		KeyPairModel m1=new KeyPairModel(ring.listModel());
		KeyPairModel m2=new KeyPairModel(ring.listModel());
		AtomicInteger events=new AtomicInteger();
		m2.addListDataListener(new ListDataListener() {
			@Override public void intervalAdded(ListDataEvent e) { events.incrementAndGet(); }
			@Override public void intervalRemoved(ListDataEvent e) { events.incrementAndGet(); }
			@Override public void contentsChanged(ListDataEvent e) { events.incrementAndGet(); }
		});

		// Selection belongs to one model and notifies only its own listeners
		EventQueue.invokeAndWait(()->m1.setSelectedItem(a));
		assertSame(a, m1.getSelectedItem());
		assertNull(m2.getSelectedItem());
		assertEquals(0, events.get());

		// Key ring changes are forwarded, until the model is closed
		ring.add(entry("b"));
		flushEDT();
		assertEquals(1, events.get());
		assertEquals(3, m2.getSize()); // two entries plus "no key pair"

		m2.close();
		ring.add(entry("c"));
		flushEDT();
		assertEquals(1, events.get());

		m1.close();
	}
}
