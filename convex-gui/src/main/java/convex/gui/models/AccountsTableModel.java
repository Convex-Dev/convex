package convex.gui.models;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.text.Text;
import convex.core.util.Utils;

@SuppressWarnings("serial")
public class AccountsTableModel extends BaseTableModel {

	private State state;

	public AccountsTableModel(State state) {
		this.state = state;
	}

	private static final String[] FIXED_COLS = new String[] { "Address", "Type", "Seq.", "Balance", "Name", "Env.Size", "Allowance","Controller","Key" };

	public String getColumnName(int col) {
		if (col < FIXED_COLS.length) return FIXED_COLS[col];
		return "FOO";
	}

	@Override
	public int getRowCount() {
		return Utils.checkedInt(state.getAccounts().count());
	}

	@Override
	public int getColumnCount() {
		// TODO token columns?
		return FIXED_COLS.length;
	}

	@Override
	public boolean isCellEditable(int row, int col) {
		return false;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		// TODO: should probably be flexible columns rather than hardcoded
		Address address = Address.create(rowIndex);
		AccountStatus as = getEntry(rowIndex);
		switch (columnIndex) {
		case 0:
			return address.toString();
		case 1:
			return as.isActor();
		case 2: {
			long seq=as.getSequence();
			return (seq>=0)?seq:"";
		}
		case 3:
			return as.getBalance();
		case 4: {
			AccountStatus registry = state.getAccount(Init.REGISTRY_ADDRESS);
			ACell o = registry.getHolding(address);
			if (o == null) return "";
			if (!(o instanceof AMap)) return "<Invalid registration, not a map!>";
			AMap<Keyword, ACell> a = (AMap<Keyword, ACell>) o;
			return a.get(Keyword.create("name"));
		}
		case 5:
			return Text.toFriendlyNumber(as.getMemorySize());
		case 6:
			return Text.toFriendlyNumber(as.getMemory());
		case 7: {
			ACell control= as.getController();
			return (control==null)?"":control;
		}
		case 8:
			return as.getAccountKey();
		default:
			return "";
		}
	}

	public void setState(State newState) {
		if (state != newState) {
			state = newState;
			fireTableDataChanged();
		}
	}

	public AccountStatus getEntry(long ix) {
		return state.getAccounts().get(ix);
	}

}
