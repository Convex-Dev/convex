package convex.gui.peer.stake;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.lang.Reader;
import convex.gui.components.ConvexTable;
import convex.gui.components.renderer.AccountKeyRenderer;
import convex.gui.components.renderer.AddressRenderer;
import convex.gui.components.renderer.BalanceRenderer;
import convex.gui.components.renderer.StringRenderer;

@SuppressWarnings("serial")
public class PeerStakeTable extends ConvexTable {

	protected Convex convex;
	
	DefaultTableModel tm=new DefaultTableModel();

	public PeerStakeTable(Convex convex) {
		super (new DefaultTableModel());
		this.tm=(DefaultTableModel) getModel();
		this.convex=convex;
		this.setModel(tm);
		setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		
		{ // peer key
			String colName="Peer";
			tm.addColumn(colName);
			TableColumn col=new TableColumn(0,300,new AccountKeyRenderer(),null);
			col.setHeaderValue(colName);
			this.getColumnModel().addColumn(col);
		}
		
		{ // URL
			String colName="Controller";
			tm.addColumn(colName);
			TableColumn col=new TableColumn(1,80,new AddressRenderer(JLabel.LEFT),null);
			col.setHeaderValue(colName);
			this.getColumnModel().addColumn(col);
		}
		
		{ // Stake
			String colName="Staked Coins";
			tm.addColumn(colName);
			TableColumn col=new TableColumn(2,100,new BalanceRenderer(),null);
			col.setHeaderValue(colName);
			this.getColumnModel().addColumn(col);
		}
		
		{ // Timestamp
			String colName="Timestamp";
			tm.addColumn(colName);
			TableColumn col=new TableColumn(3,80,new StringRenderer(),null);
			col.setHeaderValue(colName);
			this.getColumnModel().addColumn(col);
		}
		
		{ // Stake
			String colName="Peer Stake";
			tm.addColumn(colName);
			TableColumn col=new TableColumn(4,100,new BalanceRenderer(),null);
			col.setHeaderValue(colName);
			this.getColumnModel().addColumn(col);
		}
		
		{ // Delegated Stake
			String colName="Del. Stake";
			tm.addColumn(colName);
			TableColumn col=new TableColumn(5,100,new BalanceRenderer(),null);
			col.setHeaderValue(colName);
			this.getColumnModel().addColumn(col);
		}
		
		TableRowSorter<TableModel> sorter = new TableRowSorter<TableModel>(tm);
		setRowSorter(sorter);

		List<RowSorter.SortKey> sortKeys = new ArrayList<>();
		sortKeys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
		sortKeys.add(new RowSorter.SortKey(1, SortOrder.ASCENDING));
		sortKeys.add(new RowSorter.SortKey(2, SortOrder.ASCENDING));
		sorter.setSortKeys(sortKeys);

		SwingUtilities.invokeLater(()->{
			refresh();
		});
	}
	
	public boolean isCellEditable(int row, int column) {
        return false;
    }

	void refresh() {
		tm.setRowCount(0);
		String cs=("(mapv (fn [[pk p]] "
				+"[pk "
				+" (:controller p)"
				+" (:balance p) "
				+" (:timestamp p) "
				+" (get-peer-stake pk) "
				+" (get-stake pk *address*) "
				+" :FOO"
				+"])"
				+"(:peers *state*))");
		ACell cmd=Reader.read(cs);
		convex.query(cmd).thenAcceptAsync(r->{
			//if (r.isError()) {
				System.err.println(r);
			//	return;
			//}

			AVector<AVector<?>> rows=r.getValue();
			for (AVector<?> row: rows) {
				tm.addRow(row.toArray());
			}
			
		});
	}
	
	
}
