package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ARecord;

/**
 * Type that represents any CVM collection
 */
public class Record extends AType {

	public static final Record INSTANCE = new Record();
	
	private Record() {
		
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ARecord);
	}

	@Override
	public boolean allowsNull() {
		return false;
	}

	@Override
	public String toString() {
		return "Record";
	}

	@Override
	protected ARecord defaultValue() {
		return ARecord.DEFAULT_VALUE;
	}

	@Override
	protected ARecord implicitCast(ACell a) {
		if (a instanceof ARecord) return (ARecord)a;
		return null;
	}
	
	@Override
	protected Class<? extends ACell> getJavaClass() {
		return ARecord.class;
	}

}
