package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

import convex.core.State;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.MapEntry;
import convex.gui.components.ActionPanel;
import convex.gui.components.models.AccountsTableModel;
import convex.gui.manager.PeerManager;
import convex.gui.manager.windows.actor.ActorWindow;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class AccountsPanel extends JPanel {
	AccountsTableModel tableModel = new AccountsTableModel(PeerManager.getLatestState());
	JTable table = new JTable(tableModel);

	static class ActorRenderer extends DefaultTableCellRenderer {
		public ActorRenderer() {
			super();
		}

		public void setValue(Object value) {
			setText(value.toString());
		}
	}

	public AccountsPanel(PeerManager manager) {
		setLayout(new BorderLayout());

		PeerManager.getStateModel().addPropertyChangeListener(pc -> {
			State newState = (State) pc.getNewValue();
			tableModel.setState(newState);
		});

		DefaultTableCellRenderer leftRenderer = new DefaultTableCellRenderer();
		leftRenderer.setHorizontalAlignment(JLabel.LEFT);
		DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
		rightRenderer.setHorizontalAlignment(JLabel.RIGHT);
		
		table.getColumnModel().getColumn(0).setCellRenderer(leftRenderer);
		table.getColumnModel().getColumn(0).setPreferredWidth(150);
		
		ActorRenderer actorRenderer = new ActorRenderer();
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
		
		// popup menu, not sure why this doesn't work....
		final JPopupMenu popupMenu = new JPopupMenu();
		JMenuItem copyItem = new JMenuItem("Copy Address");
		copyItem.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				copyAddress();
			}
		});

		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				int r = table.rowAtPoint(e.getPoint());
				if (r >= 0 && r < table.getRowCount()) {
					table.setRowSelectionInterval(r, r);
				} else {
					table.clearSelection();
				}

				if (e.isPopupTrigger() || (e.getButton() & MouseEvent.BUTTON3) > 0) {
					popupMenu.show(e.getComponent(), e.getX(), e.getY());
				}
			}
		});

		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton btnCopy = new JButton("Copy Address");
		actionPanel.add(btnCopy);
		btnCopy.addActionListener(e -> {
			copyAddress();
		});

		JButton btnActor = new JButton("Examine Actor...");
		actionPanel.add(btnActor);
		btnActor.addActionListener(e -> {
			MapEntry<Address, AccountStatus> as = tableModel.getEntry(table.getSelectedRow());
			if (as == null) return;
			Address addr = as.getKey();
			if (!as.getValue().isActor()) return;
			ActorWindow pw = new ActorWindow(manager, addr);
			pw.launch();
		});

		// Turn off auto-resize, since we want a scrollable table
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.getViewport().setBackground(null);
		add(scrollPane, BorderLayout.CENTER);

		table.setFont(Toolkit.SMALL_MONO_FONT);
		table.getTableHeader().setFont(Toolkit.SMALL_MONO_FONT);
		((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);

	}

	private void copyAddress() {
		int row = table.getSelectedRow();
		if (row < 0) return;

		MapEntry<Address, AccountStatus> me = tableModel.getEntry(row);
		Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
		StringSelection stringSelection = new StringSelection(me.getKey().toHexString());
		clipboard.setContents(stringSelection, null);
	}

}
