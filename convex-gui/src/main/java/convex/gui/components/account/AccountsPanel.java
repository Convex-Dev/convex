package convex.gui.components.account;


import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.State;
import convex.core.data.AArrayBlob;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.util.Utils;
import convex.gui.actor.AccountWindow;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.components.BalanceLabel;
import convex.gui.components.Identicon;
import convex.gui.models.AccountsTableModel;
import convex.gui.models.StateModel;
import convex.gui.utils.Toolkit;
import convex.gui.wallet.WalletApp;

/**
 * Panel showing account information
 */
@SuppressWarnings({ "serial"})
public class AccountsPanel extends JPanel {
	AccountsTableModel tableModel;
	JTable table;

	static class CellRenderer extends DefaultTableCellRenderer {
		public CellRenderer(int alignment) {
			super();
			this.setHorizontalAlignment(alignment);
		}

		public void setValue(Object value) {
			setText(Utils.toString(value));
		}
	}
	
	static class AccountKeyRenderer extends DefaultTableCellRenderer {
		public AccountKeyRenderer() {
			super();
			this.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
		}

		public void setValue(Object value) {
			setText((value==null)?"":value.toString());
			setIcon(Identicon.createIcon((AArrayBlob) value,21));
		}
	}
	
	static class BalanceRenderer extends BalanceLabel implements TableCellRenderer {

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
				int row, int column) {
			this.setAlignmentX(RIGHT_ALIGNMENT);
			this.setSize(table.getColumnModel().getColumn(column).getWidth(), getHeight());
			if (value==null) {
				setBalance(0L);
			} else {
				this.setBalance((Long)value);
			}
			return this;
		}
	}

	public AccountsPanel(ConvexLocal convex,StateModel<State> model) {
		setLayout(new BorderLayout());

		
		tableModel = new AccountsTableModel(model.getValue());
		table = new JTable(tableModel);
		
		table.setCellSelectionEnabled(true);
		table.setIntercellSpacing(new Dimension(1,1));
		//table.setFont(Toolkit.SMALL_MONO_FONT);
		//table.getTableHeader().setFont(Toolkit.SMALL_MONO_FONT);
		((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);
		
		model.addPropertyChangeListener(pc -> {
			State newState = (State) pc.getNewValue();
			tableModel.setState(newState);
		});
		
		{	
			CellRenderer cr=new CellRenderer(JLabel.LEFT);
			cr.setToolTipText("Address of the Convex account. This is the unique ID for the account");
			cr.setForeground(Color.WHITE);
			table.getColumnModel().getColumn(0).setCellRenderer(cr);
			table.getColumnModel().getColumn(0).setPreferredWidth(80);
		}
		
		{	
			CellRenderer actorRenderer = new CellRenderer(JLabel.LEFT);
			actorRenderer.setToolTipText("An Actor account is an autonomous agent or code library on the CVM. A User account can be controlled by a user with the correct key pair.");
			table.getColumnModel().getColumn(1).setPreferredWidth(70);
			table.getColumnModel().getColumn(1).setCellRenderer(actorRenderer);
		}

		{	
			CellRenderer cr=new CellRenderer(JLabel.RIGHT); 
			cr.setToolTipText("Sequence number of the account. This is the total number of user transactions executed.");
			table.getColumnModel().getColumn(2).setCellRenderer(cr);
			table.getColumnModel().getColumn(2).setPreferredWidth(60);
		}
		
		{
			BalanceRenderer cr=new BalanceRenderer(); 
			cr.setToolTipText("Balance of the account in Convex Coins");
			table.getColumnModel().getColumn(3).setCellRenderer(cr);
			table.getColumnModel().getColumn(3).setPreferredWidth(180);
		}
		
		{	
			CellRenderer cr=new CellRenderer(JLabel.LEFT); 
			cr.setToolTipText("Name of the account in the Convex Registry");
			table.getColumnModel().getColumn(4).setPreferredWidth(200);
			table.getColumnModel().getColumn(4).setCellRenderer(cr);
		}
		{	
			CellRenderer cr=new CellRenderer(JLabel.RIGHT); 
			cr.setToolTipText("Size of the account environment");
			table.getColumnModel().getColumn(5).setPreferredWidth(100);
			table.getColumnModel().getColumn(5).setCellRenderer(cr);
		}
		
		{	// Memory allowance
			CellRenderer cr=new CellRenderer(JLabel.RIGHT); 
			cr.setToolTipText("Unused memory allowance of the account");
			table.getColumnModel().getColumn(6).setPreferredWidth(100);
			table.getColumnModel().getColumn(6).setCellRenderer(cr);
		}
		
		{	// Account Controller
			CellRenderer cr=new CellRenderer(JLabel.LEFT);
			cr.setToolTipText("Account Controller. May recover, modify or update the controlled account.");
			cr.setForeground(Color.WHITE);
			table.getColumnModel().getColumn(7).setPreferredWidth(80);
			table.getColumnModel().getColumn(7).setCellRenderer(cr);
		}


		
		{	// Account public key
			AccountKeyRenderer cr=new AccountKeyRenderer(); 
			cr.setToolTipText("Public key of the account. Used to validate transactions from users.");
			table.getColumnModel().getColumn(8).setPreferredWidth(150);
			table.getColumnModel().getColumn(8).setCellRenderer(cr);
		}

		final JPopupMenu popupMenu = new JPopupMenu();
		JMenuItem copyItem = new JMenuItem("Copy Value");
		copyItem.setIcon(Toolkit.menuIcon(0xe14d));
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

		// ============================================
		// Actionbuttons at bottom
		
		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		ActionButton btnActor = new ActionButton("Examine Account...",0xf5e1,e -> {
			long ix=table.getSelectedRow();
			if (ix<0) return;
			AccountStatus as = tableModel.getEntry(ix);
			if (as == null) return;
			Address addr = Address.create(ix);
			AccountWindow pw = new AccountWindow(convex, model, addr);
			pw.run();
		});
		btnActor.setToolTipText("Opens a windows with utilities for examining an account");
		actionPanel.add(btnActor);
		
		ActionButton btnWallet = new ActionButton("Launch Wallet",0xe89e,e -> {
			Convex cv=Convex.connect(convex.getLocalServer());
			long ix=table.getSelectedRow();
			if (ix<0) return;
			cv.setAddress(Address.create(ix));
			new WalletApp(cv).run();
		});
		btnWallet.setToolTipText("Opens a Wallet interface for this account");
		actionPanel.add(btnWallet);

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
