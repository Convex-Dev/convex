package convex.core.data;

import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.RecordFormat;
import convex.core.util.Utils;

/**
 * Abstract base class for generic records.
 * 
 * Generic records are backed by a vector
 */
public abstract class ARecordGeneric extends ARecord {

	protected AVector<ACell> values;

	protected ARecordGeneric(RecordFormat format, AVector<ACell> values) {
		super(format);
		if (values.count()!=format.count()) throw new IllegalArgumentException("Wrong number of field values for record: "+values.count());
		this.values=values;
	}

	@Override
	protected String ednTag() {
		return "result";
	}
	
	@Override
	public MapEntry<Keyword, ACell> entryAt(long i) {
		return MapEntry.create(format.getKey(Utils.checkedInt(i)), values.get(i));
	}

	@Override
	public ACell get(ACell key) {
		Long ix=format.indexFor(key);
		if (ix==null) return null;
		return values.get((long)ix);
	}

	@Override
	public abstract byte getTag();
	
	@Override
	public int getRefCount() {
		return values.getRefCount();
	}
	
	@Override
	public <R extends ACell> Ref<R> getRef(int index) {
		return values.getRef(index);
	}

	@Override
	public ARecord updateRefs(IRefFunction func) {
		AVector<ACell> newValues=values.updateRefs(func);
		return withValues(newValues);
	}

	@Override
	protected ARecord updateAll(ACell[] newVals) {
		int n=size();
		if (newVals.length!=n) throw new IllegalArgumentException("Wrong number of values: "+newVals.length);
		boolean changed = false;
		for (int i=0; i<n; i++) {
			if (values.get(i)!=newVals[i]) {
				changed=true;
				break;
			}
		}
		if (!changed) return this;
		AVector<ACell> newVector=Vectors.create(newVals);
		return withValues(newVector);
	}

	/**
	 * Updates the record with a new set of values. 
	 * 
	 * Returns this if and only if values vector is identical.
	 * 
	 * @param newVector
	 * @return
	 */
	protected abstract ARecord withValues(AVector<ACell> newValues);

	@Override
	public void validateCell() throws InvalidDataException {
		values.validateCell();
	}

}
