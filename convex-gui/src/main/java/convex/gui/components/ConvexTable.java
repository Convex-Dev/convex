package convex.gui.components;

import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;

@SuppressWarnings("serial")
public class ConvexTable extends JTable {

	public ConvexTable(TableModel tableModel) {
		super( tableModel);
		
		setCellSelectionEnabled(true);
		setIntercellSpacing(new Dimension(1,1));
		setAutoCreateColumnsFromModel(false);
		
		//table.setFont(Toolkit.SMALL_MONO_FONT);
		//table.getTableHeader().setFont(Toolkit.SMALL_MONO_FONT);
	

		((DefaultTableCellRenderer) getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);
	}

	public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
        Component c = super.prepareRenderer(renderer, row, column);

        //  Set row colour
        if (isRowSelected(row)) {
        	if (isColumnSelected(column)) {
        	} else {
        		
        	}
        } else {
            // c.setBackground(row % 2 == 0 ? getBackground() : Color.BLACK);
        }
        
        return c;
	}
}
