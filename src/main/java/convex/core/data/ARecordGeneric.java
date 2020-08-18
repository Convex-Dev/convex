package convex.core.data;

import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.RecordFormat;

/**
 * Abstract base class for generic records.
 * 
 * Generic records are backed by a vector
 */
public abstract class ARecordGeneric extends ARecord {

	protected AVector<Object> values;

	protected ARecordGeneric(RecordFormat format, AVector<Object> values) {
		super(format);
		this.values=values;
	}

	@Override
	protected String ednTag() {
		// TODO Auto-generated method stub
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <V> V get(Keyword key) {
		Long ix=format.indexFor(key);
		if (ix==null) return null;
		return (V) values.get((long)ix);
	}

	@Override
	public byte getRecordTag() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	protected ARecord updateAll(Object[] newVals) {
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
		AVector<Object> newVector=Vectors.create(newVals);
		return withValues(newVector);
	}

	/**
	 * Updates the record with a new set of values.
	 * @param newVector
	 * @return
	 */
	protected abstract ARecord withValues(AVector<Object> newValues);

	@Override
	public void validateCell() throws InvalidDataException {
		values.validateCell();
	}

}
