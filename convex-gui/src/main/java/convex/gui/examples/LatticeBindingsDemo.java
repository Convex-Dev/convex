package convex.gui.examples;

import java.awt.EventQueue;

import javax.swing.JButton;
import javax.swing.JLabel;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.gui.binding.Bindings;
import convex.gui.components.AbstractGUI;
import convex.lattice.LatticeComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.MaxLattice;
import net.miginfocom.swing.MigLayout;

/**
 * Small local example: widgets follow domain getters, and buttons call domain
 * operations. A fork keeps edits private until Apply. No network or store is used.
 */
@SuppressWarnings("serial")
public class LatticeBindingsDemo extends AbstractGUI {

	public LatticeBindingsDemo() {
		super("Lattice bindings");
		setLayout(new MigLayout("wrap 2","[][grow,fill]"));
		Counter published=new Counter(Root.create(CVMLong.ZERO));
		Counter draft=published.fork();
		Bindings bindings=own(Bindings.on(this));

		JLabel publishedValue=new JLabel();
		JLabel draftValue=new JLabel();
		add(new JLabel("Published:"));
		add(publishedValue);
		add(new JLabel("Draft:"));
		add(draftValue);
		bindings.text(publishedValue,published::value);
		bindings.text(draftValue,draft::value);

		JButton increment=new JButton("Increment draft");
		increment.addActionListener(e -> {
			draft.increment();
			bindings.refresh();
		});
		add(increment);
		JButton apply=new JButton("Apply");
		apply.addActionListener(e -> {
			draft.sync();
			bindings.refresh();
		});
		add(apply);

		// Deliberately no binding refresh: sampling also finds changes from other writers.
		JButton external=new JButton("Advance published value");
		external.addActionListener(e -> published.increment());
		add(external,"span 2");
		add(new JLabel("Apply reconciles the draft using the greater value."),"span 2");
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
		EventQueue.invokeLater(() -> new LatticeBindingsDemo().run());
	}
}
