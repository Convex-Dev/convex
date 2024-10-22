package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * Abstract base class for dense records CAD3 types 0xD0 - 0xDF
 */
public class ADenseRecord extends ARecord {
	protected final AVector<ACell> values;
	protected final RecordFormat format;
	protected final  byte tag;

	protected ADenseRecord (byte tag, AVector<ACell> values, RecordFormat rf) {
		super(values.count());
		this.format=rf;
		this.values=values;
		this.tag=tag;
	}
	
	@Override
	public ACell get(Keyword key) {
		Long ix=format.indexFor(key);
		if (ix==null) return null;
		return values.get((long)ix);
	}

	@Override
	public RecordFormat getFormat() {
		return format;
	}
	
	@Override
	public ARecord updateRefs(IRefFunction func) {
		AVector<ACell> newValues=values.toVector().updateRefs(func); // ensure values are canonical via toVector
		return withValues(newValues);
	}
	
	private ARecord withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new ADenseRecord(tag,newValues,format);
	}

	@Override
	public AVector<ACell> values() {
		return values;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		values.validateCell();
	}

	@Override
	public byte getTag() {
		return tag;
	}

	@Override
	public boolean equals(ACell a) {
		if (a==null) return false;
		if (tag!=a.getTag()) return false;
		if (a instanceof ADenseRecord) {
			return values.equals(((ADenseRecord)a).values);
		}
		return false;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return values.encodeRaw(bs, pos);
	}

}
