package convex.gui.examples;

import java.awt.EventQueue;

import javax.swing.JButton;
import javax.swing.JLabel;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.gui.components.AbstractGUI;
import convex.gui.components.LatticePanel;
import convex.lattice.LatticeComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;
import net.miginfocom.swing.MigLayout;

/**
 * Two instances of one view bind a published component and its private draft.
 * Buttons call domain operations. No network or store is used.
 */
@SuppressWarnings("serial")
public class LatticeViewDemo extends AbstractGUI {

	public LatticeViewDemo() {
		super("Lattice views");
		setLayout(new MigLayout("wrap 2","[][grow,fill]"));
		Counter published=new Counter(Root.create(CVMLong.ZERO));
		Counter draft=published.fork();
		CounterPanel publishedView=new CounterPanel(published);
		CounterPanel draftView=new CounterPanel(draft);
		add(new JLabel("Published:"));
		add(publishedView);
		add(new JLabel("Draft:"));
		add(draftView);

		JButton increment=new JButton("Increment draft");
		increment.addActionListener(e -> {
			draft.increment();
			draftView.refresh();
		});
		add(increment);
		JButton apply=new JButton("Apply");
		apply.addActionListener(e -> {
			draft.sync();
			publishedView.refresh();
			draftView.refresh();
		});
		add(apply);

		// No explicit refresh: the trigger also finds changes from other writers.
		JButton external=new JButton("Advance published value");
		external.addActionListener(e -> published.increment());
		add(external,"span 2");
		add(new JLabel("Apply reconciles the draft using the greater value."),"span 2");
	}

	/** One component binding and one render operation, however many widgets the view contains. */
	static final class CounterPanel extends LatticePanel<AInteger> {
		final JLabel value=new JLabel();
		final JLabel next=new JLabel();

		CounterPanel(Counter counter) {
			super(counter);
			setLayout(new MigLayout("insets 0","[grow][]"));
			add(value,"growx");
			add(next);
		}

		@Override protected void render(AInteger snapshot) {
			value.setText(snapshot.toString());
			next.setText("Next: "+snapshot.inc());
		}
	}

	/** A local high-water mark; max merge does not sum increments from different writers. */
	static final class Counter extends LatticeComponent<AInteger> {
		Counter(AForkableCursor<AInteger> cursor) {
			super(MaxLattice.INSTANCE,cursor);
		}
		void increment() { cursor().update(AInteger::inc); }
		@Override public Counter rebind(AForkableCursor<AInteger> cursor) { return new Counter(cursor); }
		@Override public Counter fork() { return rebind(storage.fork()); }
	}

	public static void main(String... args) {
		EventQueue.invokeLater(() -> new LatticeViewDemo().run());
	}
}
