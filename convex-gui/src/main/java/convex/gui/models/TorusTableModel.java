package convex.gui.models;

import java.text.DecimalFormat;
import java.text.NumberFormat;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.data.MapEntry;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMDouble;
import convex.core.cvm.Context;
import convex.core.lang.Reader;

@SuppressWarnings("serial")
public class TorusTableModel extends BaseTableModel {

	protected State state;
	protected Address torus;
	protected Address asset;

	public TorusTableModel(State state) {
		this.state = state;
		this.torus=eval("(import torus.exchange)");
		this.asset=eval("(import convex.asset)");
	}
	
	private static final String[] FIXED_COLS = new String[] { "Token", "Market", "Pool AMT", "Pool CVX","Price" };
	private static final Symbol SYM_MARKETS = Symbol.create("markets");

	NumberFormat formatter = new DecimalFormat("#0.000000");     
	
	public String getColumnName(int col) {
		if (col < FIXED_COLS.length) return FIXED_COLS[col];
		return "FOO";
	}

	@Override
	public int getRowCount() {
		AMap<ACell,ACell> markets=getMarkets();
		return (int) markets.count();
	}
	
	@SuppressWarnings("unchecked")
	public AMap<ACell,ACell> getMarkets() {
		AccountStatus as=state.getAccount(torus);
		AMap<ACell,ACell> markets=(AMap<ACell, ACell>) as.getEnvironment().get(SYM_MARKETS);
		return markets;
	}

	@Override
	public int getColumnCount() {
		return FIXED_COLS.length;
	}

	@Override
	public boolean isCellEditable(int row, int col) {
		return false;
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		AMap<ACell,ACell> markets=getMarkets();
		
		MapEntry<ACell,ACell> me=markets.entryAt(rowIndex);
		
		ACell token=me.getKey();
		Address marketAddr=(Address) me.getValue();
		
		switch (columnIndex) {
		case 0:
			return me.getKey();
		case 1:
			return marketAddr;
		case 2:
			return eval("("+asset+"/balance "+token+" "+marketAddr+")");
		case 3:
			return eval("(balance "+marketAddr+")");
		case 4:
			CVMDouble d=eval("("+torus+"/price "+token+")");
			if (d==null) return "No price";
			double p=d.doubleValue();
			return formatter.format(p);
	
		default:
			return "";
		}
	}

	@SuppressWarnings("unchecked")
	private <T extends ACell> T eval(String code) {
		Context ctx=Context.create(state);
		T result=(T) ctx.eval(Reader.read(code)).getValue();
		return result;
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
