package convex.gui.manager.mainpanels;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;

import convex.core.State;
import convex.gui.components.ActionPanel;
import convex.gui.components.models.TorusTableModel;
import convex.gui.manager.PeerGUI;
import convex.gui.utils.Toolkit;

@SuppressWarnings("serial")
public class TorusPanel extends JPanel {
	TorusTableModel tableModel = new TorusTableModel(PeerGUI.getLatestState());
	JTable table = new JTable(tableModel);

	public TorusPanel(PeerGUI manager) {
		setLayout(new BorderLayout());

		PeerGUI.getStateModel().addPropertyChangeListener(pc -> {
			State newState = (State) pc.getNewValue();
			tableModel.setState(newState);
		});

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

		JButton btnCopy = new JButton("New Token");
		actionPanel.add(btnCopy);
		btnCopy.addActionListener(e -> {
			// TODO:;
		});

		// Turn off auto-resize, since we want a scrollable table
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.getViewport().setBackground(null);
		add(scrollPane, BorderLayout.CENTER);

		table.setFont(Toolkit.SMALL_MONO_FONT);
		table.getTableHeader().setFont(Toolkit.SMALL_MONO_BOLD);
		((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);

	}

}
