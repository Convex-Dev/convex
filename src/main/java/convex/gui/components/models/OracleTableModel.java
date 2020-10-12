package convex.gui.components.models;

import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;

import convex.core.State;
import convex.core.data.AMap;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.util.Utils;

/**
 * Model for the Oracle table
 */
@SuppressWarnings("serial")
public class OracleTableModel extends AbstractTableModel implements TableModel {

	private State state;
	private Address oracle;
	
	Symbol LIST_S=Symbol.create("full-list");
	Symbol RESULTS_S=Symbol.create("results");
	
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
		AMap<Object,Object> list=as.getEnvironmentValue(LIST_S); 
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
	
	public AMap<Object,Object> getList() {
		AMap<Symbol,Syntax> env=state.getAccount(oracle).getEnvironment();
		AMap<Object,Object> list=env.get(LIST_S).getValue(); 
		return list;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		AMap<Symbol,Syntax> env=state.getAccount(oracle).getEnvironment();
		AMap<Object,Object> list=env.get(LIST_S).getValue(); 
		MapEntry<Object,Object> me=list.entryAt(rowIndex);
		Object key=me.getKey();
		switch (columnIndex) {
			case 0: return key.toString();
			case 1: {
				AMap<Keyword, Object> data=(AMap<Keyword, Object>) me.getValue();
				return data.get(DESC_K);
			}
			case 2: {
				boolean done=((AMap<Object, Object>) env.get(RESULTS_S).getValue()).containsKey(key);
				return done?"Yes":"No";
			}
			case 3: {
				AMap<Object, Object> results=((AMap<Object, Object>) env.get(RESULTS_S).getValue());
				MapEntry<Object,Object> rme=results.getEntry(key);
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
