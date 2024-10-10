package convex.gui.components.renderer;

import java.awt.Component;

import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.gui.components.BalanceLabel;

@SuppressWarnings("serial")
public class BalanceRenderer extends BalanceLabel implements TableCellRenderer {

	@Override
	public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
			int row, int column) {
		this.setAlignmentX(RIGHT_ALIGNMENT);
		this.setSize(table.getColumnModel().getColumn(column).getWidth(), getHeight());
		if (value==null) {
			setBalance(0L);
		} else {
			CVMLong lv=RT.ensureLong(RT.cvm(value));
			this.setBalance(lv);
		}
		return this;
	}
}