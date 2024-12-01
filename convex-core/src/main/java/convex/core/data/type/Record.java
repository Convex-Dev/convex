package convex.core.data.type;

import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.ARecord;

/**
 * Type that represents any CVM collection
 */
@SuppressWarnings("rawtypes")
public class Record extends AStandardType<ARecord> {

	public static final Record INSTANCE = new Record();
	
	@SuppressWarnings("unused")
	private static final RecordFormat DUMMY_FORMAT=RecordFormat.of(Keywords.FOO);
	
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
