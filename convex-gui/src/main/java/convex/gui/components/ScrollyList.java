package convex.gui.components;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.function.Function;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListModel;
import javax.swing.Scrollable;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

/**
 * Component that represents a convenient Scrollable list of child components,
 * based on a List model.
 * 
 * @param <E> Type of list model elements
 */
@SuppressWarnings("serial")
public class ScrollyList<E> extends JScrollPane {
	private final Function<E, Component> builder;
	private final ListModel<E> model;
	private final ScrollablePanel listPanel = new ScrollablePanel();

	public void refreshList() {
		EventQueue.invokeLater(()->{;
			listPanel.removeAll();
			int n = model.getSize();
			for (int i = 0; i < n; i++) {
				E we = model.getElementAt(i);
				listPanel.add(builder.apply(we));
			}
			this.revalidate();
		});
	}

	private static class ScrollablePanel extends JPanel implements Scrollable {

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return new Dimension(800, 600);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 60;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			// TODO Auto-generated method stub
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
		this.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

		listPanel.setLayout(new GridLayout(0, 1));
		setViewportView(listPanel);
		getViewport().setBackground(null);

		model.addListDataListener(new ListDataListener() {
			@Override
			public void intervalAdded(ListDataEvent e) {
				refreshList();
			}

			@Override
			public void intervalRemoved(ListDataEvent e) {
				refreshList();
			}

			@Override
			public void contentsChanged(ListDataEvent e) {
				refreshList();
			}
		});

		refreshList();
	}
}
