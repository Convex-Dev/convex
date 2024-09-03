package convex.gui.components;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.util.function.Function;

import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ListModel;
import javax.swing.Scrollable;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import convex.gui.utils.Toolkit;
import net.miginfocom.swing.MigLayout;

/**
 * Component that represents a convenient Scrollable list of child components,
 * based on a List model.
 * 
 * @param <E> Type of list model elements
 */
@SuppressWarnings("serial")
public class ScrollyList<E> extends JScrollPane {
	public static final int VIEWPORT_HEIGHT = 660;
	
	private final Function<E, Component> builder;
	private final ListModel<E> model;
	private final ScrollablePanel listPanel = new ScrollablePanel();

	private final MigLayout listLayout;
	
	public void refreshList() {
		boolean bottom=isAtBottom();
		EventQueue.invokeLater(()->{;
			listPanel.removeAll();
			int n = model.getSize();
			for (int i = 0; i < n; i++) {
				E we = model.getElementAt(i);
				listPanel.add(builder.apply(we),"span");
			}
			this.revalidate();
			if (bottom) {
				EventQueue.invokeLater(()->Toolkit.scrollToBottom(this));
			}
		});
	}

	private boolean isAtBottom() {
		JScrollBar bar = this.getVerticalScrollBar();
		int pos= bar.getValue();
		if (pos==0) return false; // we are the top
		return pos==bar.getMaximum();
	}

	/**
	 * Internal panel that contains the list components
	 */
	private static class ScrollablePanel extends JPanel implements Scrollable {

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			Dimension d = getPreferredSize();
			if (d.getHeight()>VIEWPORT_HEIGHT) {
				d=new Dimension(d.width,VIEWPORT_HEIGHT);
			}
			return d;
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 60;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 180;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}

	public ScrollyList(ListModel<E> model, Function<E, Component> builder) {
		super();
		this.builder = builder;
		this.model = model;
		// this.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

		listLayout=new MigLayout("wrap");
		listPanel.setLayout(listLayout);
		setViewportView(listPanel);
		getViewport().setBackground(null);

		model.addListDataListener(new ListDataListener() {
			@Override
			public void intervalAdded(ListDataEvent e) {
				int start=e.getIndex0();
				int last=e.getIndex1();
				for (int i=start; i<=last; i++) {
					listPanel.add(builder.apply(model.getElementAt(i)),"wrap");
				}
			}

			@Override
			public void intervalRemoved(ListDataEvent e) {
				int start=e.getIndex0();
				int last=e.getIndex1();
				for (int i=start; i<=last; i++) {
					listPanel.remove(start);;
				}
			}

			@Override
			public void contentsChanged(ListDataEvent e) {
				refreshList();
			}
		});
		
		refreshList();
	}

	public Component[] getListComponents() {
		return listPanel.getComponents();
	}
}
