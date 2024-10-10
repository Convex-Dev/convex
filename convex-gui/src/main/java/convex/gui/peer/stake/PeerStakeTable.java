package convex.gui.peer.stake;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.lang.Reader;
import convex.gui.components.renderer.AccountKeyRenderer;
import convex.gui.components.renderer.CellRenderer;

@SuppressWarnings("serial")
public class PeerStakeTable extends JTable {

	protected Convex convex;
	
	DefaultTableModel tm=new DefaultTableModel();

	public PeerStakeTable(Convex convex) {
		this.convex=convex;
		this.setModel(tm);
		
		{ // peer key
			tm.addColumn("Peer");
			TableColumn col = getColumn("Peer");
			col.setCellRenderer(new AccountKeyRenderer());
		}
		
		{ // URL
			tm.addColumn("Controller");
			TableColumn col = this.getColumn("Controller");
			col.setCellRenderer(new CellRenderer(JLabel.LEFT));
		}

		refresh();
	}

	private void refresh() {
		String cs=("(mapv (fn [[pk p]] [pk (:controller p)]) "
				+" (:peers *state*))");
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
