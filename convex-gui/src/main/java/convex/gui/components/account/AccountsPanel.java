package convex.gui.components.account;


import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.border.BevelBorder;
import javax.swing.table.DefaultTableCellRenderer;

import convex.api.Convex;
import convex.core.State;
import convex.core.data.AArrayBlob;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.util.Utils;
import convex.gui.actor.AccountWindow;
import convex.gui.components.ActionPanel;
import convex.gui.components.Identicon;
import convex.gui.components.models.AccountsTableModel;
import convex.gui.components.models.StateModel;
import convex.gui.utils.Toolkit;

@SuppressWarnings({ "serial"})
public class AccountsPanel extends JPanel {
	AccountsTableModel tableModel;
	JTable table;

	static class AccountsTableRenderer extends DefaultTableCellRenderer {
		public AccountsTableRenderer() {
			super();
		}

		public void setValue(Object value) {
			setText(Utils.toString(value));
		}
	}
	
	static class AccountKeyRenderer extends DefaultTableCellRenderer {
		public AccountKeyRenderer() {
			super();
			this.setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED));
		}

		public void setValue(Object value) {
			setText((value==null)?"":value.toString());
			setIcon(Identicon.createIcon((AArrayBlob) value,24));
		}
	}

	public AccountsPanel(Convex convex,StateModel<State> model) {
		setLayout(new BorderLayout());

		
		tableModel = new AccountsTableModel(model.getValue());
		table = new JTable(tableModel);
		
		table.setCellSelectionEnabled(true);
		//table.setFont(Toolkit.SMALL_MONO_FONT);
		//table.getTableHeader().setFont(Toolkit.SMALL_MONO_FONT);
		((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);
		
		model.addPropertyChangeListener(pc -> {
			State newState = (State) pc.getNewValue();
			tableModel.setState(newState);
		});

		DefaultTableCellRenderer leftRenderer = new AccountsTableRenderer();
		leftRenderer.setHorizontalAlignment(JLabel.LEFT);
		DefaultTableCellRenderer rightRenderer = new AccountsTableRenderer();
		rightRenderer.setHorizontalAlignment(JLabel.RIGHT);
		
		table.getColumnModel().getColumn(0).setCellRenderer(leftRenderer);
		table.getColumnModel().getColumn(0).setPreferredWidth(80);
		
		AccountsTableRenderer actorRenderer = new AccountsTableRenderer();
		actorRenderer.setHorizontalAlignment(JLabel.CENTER);
		table.getColumnModel().getColumn(1).setPreferredWidth(70);
		table.getColumnModel().getColumn(1).setCellRenderer(actorRenderer);

		table.getColumnModel().getColumn(2).setCellRenderer(rightRenderer);
		table.getColumnModel().getColumn(2).setPreferredWidth(70);

		table.getColumnModel().getColumn(3).setCellRenderer(rightRenderer);
		table.getColumnModel().getColumn(3).setPreferredWidth(180);

		table.getColumnModel().getColumn(4).setPreferredWidth(200);
		table.getColumnModel().getColumn(4).setCellRenderer(leftRenderer);

		table.getColumnModel().getColumn(5).setPreferredWidth(100);
		table.getColumnModel().getColumn(5).setCellRenderer(rightRenderer);

		table.getColumnModel().getColumn(6).setPreferredWidth(100);
		table.getColumnModel().getColumn(6).setCellRenderer(rightRenderer);

		// Key
		table.getColumnModel().getColumn(7).setPreferredWidth(200);
		table.getColumnModel().getColumn(7).setCellRenderer(new AccountKeyRenderer());

		final JPopupMenu popupMenu = new JPopupMenu();
		JMenuItem copyItem = new JMenuItem("Copy Value");
		copyItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				copyValue();
			}
		});
		popupMenu.add(copyItem);
		Toolkit.addPopupMenu(table, popupMenu);
 
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				Point p=e.getPoint();
				int r = table.rowAtPoint(p);
				int c = table.columnAtPoint(p);
				if (r >= 0 && r < table.getRowCount() && c >= 0 && c < table.getColumnCount()) {
					table.setRowSelectionInterval(r, r);
					table.setColumnSelectionInterval(c, c);
				} else {
					table.clearSelection();
				}
			}
		});

		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton btnActor = new JButton("Examine Account...");
		actionPanel.add(btnActor);
		btnActor.addActionListener(e -> {
			long ix=table.getSelectedRow();
			AccountStatus as = tableModel.getEntry(ix);
			if (as == null) return;
			Address addr = Address.create(ix);
			if (!as.isActor()) return;
			AccountWindow pw = new AccountWindow(convex, model, addr);
			pw.run();
		});

		// Turn off auto-resize, since we want a scrollable table
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.getViewport().setBackground(null);
		add(scrollPane, BorderLayout.CENTER);


	}

	private void copyValue() {
		int row = table.getSelectedRow();
		if (row < 0) return;
		int col = table.getSelectedColumn();
		if (col < 0) return;

		Object o=tableModel.getValueAt(row, col);
		String s=(o==null)?"nil":o.toString();
		Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
		StringSelection stringSelection = new StringSelection(s);
		clipboard.setContents(stringSelection, null);
	}

}
