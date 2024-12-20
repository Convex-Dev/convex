package convex.core.cvm;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.exceptions.InvalidDataException;

/**
 * Abstract base class for generic records.
 * 
 * Generic records are backed by a Vector of values
 */
public abstract class ARecordGeneric extends ACVMRecord {

	protected final AVector<ACell> values;
	
	protected final RecordFormat format;
	
	protected ARecordGeneric(byte tag,RecordFormat format, AVector<ACell> values) {
		super(tag,format.count());
		if (values.count()!=format.count()) throw new IllegalArgumentException("Wrong number of field values for record: "+values.count());
		this.format=format;
		this.values=values;
	}
	
	@Override
	public MapEntry<Keyword, ACell> entryAt(long i) {
		if ((i<0)||(i>values.count())) throw new IndexOutOfBoundsException(i);
		return MapEntry.create(format.getKey((int)i), values.get(i));
	}

	@Override
	public ACell get(Keyword key) {
		Long ix=format.indexFor(key);
		if (ix==null) return null;
		return values.get((long)ix);
	}
	
	@Override
	public final RecordFormat getFormat() {
		return format;
	}

	@Override
	public final int getRefCount() {
		return values.getRefCount();
	}
	
	@Override
	public int estimatedEncodingSize() {
		return values.estimatedEncodingSize();
	}
	
	@Override
	public final int encode(byte[] bs, int pos) {
		bs[pos++]=tag;
		return encodeRaw(bs,pos);
	}
	
	/**
	 * Writes the raw fields of this record in declared order
	 * @param bs Array to write to
	 */
	@Override
	public final int encodeRaw(byte[] bs, int pos) {
		return values.encodeRaw(bs, pos);
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (a instanceof ARecordGeneric) {
			return equals((ARecordGeneric)a);
		} else {
			return Cells.equalsGeneric(this, a);
		}
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
	public final <R extends ACell> Ref<R> getRef(int index) {
		return values.getRef(index);
	}

	@Override
	public final ARecordGeneric updateRefs(IRefFunction func) {
		AVector<ACell> newValues=values.updateRefs(func); // ensure values are canonical via toVector
		if (newValues==values) return this;
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
	 * @return Updated Record (or null if values not valid)
	 */
	protected abstract ARecordGeneric withValues(AVector<ACell> newValues);

	@Override
	protected void validateCell() throws InvalidDataException {
		Cells.validateCell(values);
	}
	
	public void validateStructure() throws InvalidDataException {
		super.validateStructure();
		if (values.count()!=format.count()) {
			throw new InvalidDataException("Expected "+format.count()+ "Record values but was: "+values.count(),this);
		}
	}


}
