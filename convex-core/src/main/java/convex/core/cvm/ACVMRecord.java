package convex.core.cvm;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;

public abstract class ACVMRecord extends ARecord<Keyword,ACell> {

	protected ACVMRecord(byte tag, long n) {
		super(tag, n);
	}

	@Override
	public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	public final AVector<Keyword> getKeys() {
		return getFormat().getKeys();
	}
	
	@Override
	public final ACell get(ACell key) {
		if (key instanceof Keyword) return get((Keyword)key);
		return null;
	}
	
	@Override
	public abstract ACell get(Keyword key);
	
	@Override
	public java.util.Set<Keyword> keySet() {
		return getFormat().keySet();
	}
	
	@Override
	public MapEntry<Keyword, ACell> entryAt(long i) {
		if ((i<0)||(i>=count)) throw new IndexOutOfBoundsException("Index:"+i);
		Keyword k=getFormat().getKeys().get(i);
		return getEntry(k);
	}
}
