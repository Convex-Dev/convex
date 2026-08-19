package convex.core.data.type;

import convex.core.cpos.Block;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.Vectors;

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

	/**
	 * Holder so the default record is built on first use rather than when this type
	 * descriptor is initialised. {@code Record.INSTANCE} is reached very early through
	 * {@link Types}, and constructing a record during that initialisation would put
	 * this class on the record hierarchy's initialisation path.
	 */
	private static final class Default {
		static final ARecord VALUE = Block.create(0, Vectors.empty());
	}

	@Override
	public ARecord defaultValue() {
		return Default.VALUE;
	}

	@Override
	public ARecord implicitCast(ACell a) {
		if (a instanceof ARecord) return (ARecord)a;
		return null;
	}

}
