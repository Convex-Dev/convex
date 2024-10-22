package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * Abstract base class for generic records.
 * 
 * Generic records are backed by a Vector of values
 */
public abstract class ARecordGeneric extends ARecord {

	protected AVector<ACell> values;
	
	protected final RecordFormat format;
	
	protected ARecordGeneric(RecordFormat format, AVector<ACell> values) {
		super(format.count());
		if (values.count()!=format.count()) throw new IllegalArgumentException("Wrong number of field values for record: "+values.count());
		this.format=format;
		this.values=values;
	}
	
	@Override
	public MapEntry<Keyword, ACell> entryAt(long i) {
		return MapEntry.create(format.getKey((int)i), values.get(i));
	}

	@Override
	public ACell get(Keyword key) {
		Long ix=format.indexFor(key);
		if (ix==null) return null;
		return values.get((long)ix);
	}
	
	@Override
	public int getRefCount() {
		return values.getRefCount();
	}
	
	/**
	 * Writes the raw fields of this record in declared order
	 * @param bs Array to write to
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		AVector<Keyword> keys=getKeys();
		for (Keyword key: keys) {
			pos=Format.write(bs,pos, get(key));
		}
		return pos;
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (!(a instanceof ARecordGeneric)) return false;
		return equals((ARecordGeneric)a);
	}
		
	protected boolean equals(ARecordGeneric a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		if (a.getTag()!=getTag()) return false;
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return h.equals(ha);
		}
		return values.equals(a.values);
	}
	
	@Override
	public <R extends ACell> Ref<R> getRef(int index) {
		return values.getRef(index);
	}

	@Override
	public ARecord updateRefs(IRefFunction func) {
		AVector<ACell> newValues=values.toVector().updateRefs(func); // ensure values are canonical via toVector
		return withValues(newValues);
	}
	
	@Override
	public AVector<ACell> values() {
		return values;
	}

	/**
	 * Updates the record with a new set of values. 
	 * 
	 * Returns this if and only if values vector is identical.
	 * 
	 * @param newValues New values to use
	 * @return Updated Record
	 */
	protected abstract ARecord withValues(AVector<ACell> newValues);

	@Override
	public void validateCell() throws InvalidDataException {
		values.validateCell();
	}

}
