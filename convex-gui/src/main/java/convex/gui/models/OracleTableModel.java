package convex.gui.models;

import javax.swing.table.TableModel;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Symbol;
import convex.core.util.Utils;

/**
 * Model for the Oracle table
 */
@SuppressWarnings("serial")
public class OracleTableModel extends BaseTableModel implements TableModel {

	private State state;
	private Address oracle;
	
	Symbol LIST_S=Symbol.create("*list*");
	Symbol RESULTS_S=Symbol.create("*results*");
	
	Keyword DESC_K=Keyword.create("desc");

	public OracleTableModel(State state, Address oracle) {
		this.state = state;
		this.oracle=oracle;
	}
	
	private static final String[] FIXED_COLS=new String[] {
			"Key","Description", "Finalised?","Value"
	};

	public String getColumnName(int col) {
		if (col<FIXED_COLS.length) return FIXED_COLS[col];
		return "FOO";
	}

	@Override
	public int getRowCount() {
		AccountStatus as=state.getAccount(oracle);
		if (as==null) {
			System.err.println("Missing OracleTableModel account: "+oracle);
			return 0;
		}
		AMap<ACell,ACell> list=as.getEnvironmentValue(LIST_S); 
		if (list==null) {
			System.err.println("OracleTableModel missing oracle list? in "+oracle);
			return 0;
		}
		return Utils.checkedInt(list.count());
	}

	@Override
	public int getColumnCount() {
		return FIXED_COLS.length;
	}

	@Override
	public boolean isCellEditable(int row, int col) {
		return false;
	}
	
	@SuppressWarnings("unchecked")
	public AMap<ACell,ACell> getList() {
		AMap<Symbol,ACell> env=state.getAccount(oracle).getEnvironment();
		AMap<ACell,ACell> list=(AMap<ACell, ACell>) env.get(LIST_S); 
		return list;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		AMap<Symbol,ACell> env=state.getAccount(oracle).getEnvironment();
		AMap<ACell,ACell> list=(AMap<ACell, ACell>) env.get(LIST_S); 
		MapEntry<ACell,ACell> me=list.entryAt(rowIndex);
		ACell key=me.getKey();
		switch (columnIndex) {
			case 0: return key.toString();
			case 1: {
				AMap<Keyword, ACell> data=(AMap<Keyword, ACell>) me.getValue();
				return data.get(DESC_K);
			}
			case 2: {
				boolean done=((AMap<ACell, ACell>) env.get(RESULTS_S)).containsKey(key);
				return done?"Yes":"No";
			}
			case 3: {
				AMap<ACell, ACell> results=((AMap<ACell, ACell>) env.get(RESULTS_S));
				MapEntry<ACell,ACell> rme=results.getEntry(key);
				return (rme==null)?"":rme.getValue();
			}
			
			default: return "";
		}
	}

	public void setState(State newState) {
		if (state!=newState) {
			state=newState;
			fireTableDataChanged();
		}
	}
}
