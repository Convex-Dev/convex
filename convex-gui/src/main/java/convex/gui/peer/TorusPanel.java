package convex.gui.peer;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;

import convex.core.cvm.State;
import convex.gui.components.ActionButton;
import convex.gui.components.ActionPanel;
import convex.gui.models.TorusTableModel;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class TorusPanel extends JPanel {
	TorusTableModel tableModel;
	JTable table;

	public TorusPanel(PeerGUI manager) {
		setLayout(new BorderLayout());

		manager.getStateModel().addPropertyChangeListener(pc -> {
			State newState = (State) pc.getNewValue();
			tableModel.setState(newState);
		});
		
		tableModel = new TorusTableModel(manager.getLatestState());
		table = new JTable(tableModel);
		table.setFont(Toolkit.MONO_FONT);
		table.getTableHeader().setFont(Toolkit.MONO_FONT.deriveFont(Font.BOLD));

		DefaultTableCellRenderer leftRenderer = new DefaultTableCellRenderer();
		leftRenderer.setHorizontalAlignment(JLabel.LEFT);
		DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
		rightRenderer.setHorizontalAlignment(JLabel.RIGHT);
		
		TableColumnModel columnMondel=table.getColumnModel();
		columnMondel.getColumn(0).setCellRenderer(leftRenderer);
		columnMondel.getColumn(0).setPreferredWidth(80);

		columnMondel.getColumn(1).setCellRenderer(leftRenderer);
		columnMondel.getColumn(1).setPreferredWidth(80);
		
		columnMondel.getColumn(2).setCellRenderer(rightRenderer);
		columnMondel.getColumn(2).setPreferredWidth(160);

		columnMondel.getColumn(3).setCellRenderer(rightRenderer);
		columnMondel.getColumn(3).setPreferredWidth(160);
	
		columnMondel.getColumn(4).setCellRenderer(rightRenderer);
		columnMondel.getColumn(4).setPreferredWidth(160);

		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				int r = table.rowAtPoint(e.getPoint());
				if (r >= 0 && r < table.getRowCount()) {
					table.setRowSelectionInterval(r, r);
				} else {
					table.clearSelection();
				}
			}
		});

		JPanel actionPanel = new ActionPanel();
		add(actionPanel, BorderLayout.SOUTH);

		JButton btnCopy = new ActionButton("New Token",0xe145,e -> {
			// TODO:;
		});
		actionPanel.add(btnCopy);

		// Turn off auto-resize, since we want a scrollable table
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.getViewport().setBackground(null);
		add(scrollPane, BorderLayout.CENTER);


		((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);

	}

}
