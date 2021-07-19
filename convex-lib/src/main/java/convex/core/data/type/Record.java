package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.ARecord;

/**
 * Type that represents any CVM collection
 */
public class Record extends AStandardType<ARecord> {

	public static final Record INSTANCE = new Record();
	
	private Record() {
		super(ARecord.class);
	}

	@Override
	public boolean check(ACell value) {
		return (value instanceof ARecord);
	}

	@Override
	public String toString() {
		return "Record";
	}

	@Override
	public ARecord defaultValue() {
		return ARecord.DEFAULT_VALUE;
	}

	@Override
	public ARecord implicitCast(ACell a) {
		if (a instanceof ARecord) return (ARecord)a;
		return null;
	}

}
