package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * CAD3 Sparse Record type
 */
public class SparseRecord extends ASparseRecord {

	protected SparseRecord(byte tag, long mask) {
		super(tag,mask);
		
	}

	@Override
	public ACell get(Keyword key) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RecordFormat getFormat() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Auto-generated method stub
		
	}



	@Override
	public boolean equals(ACell a) {
		// TODO Auto-generated method stub
		return false;
	}



}
