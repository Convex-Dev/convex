package convex.core.data;

import convex.core.data.prim.CVMBool;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.Panic;

public abstract class AHashSet<T extends ACell> extends ASet<T> {

	protected static final int OP_UNION=1;
	protected static final int OP_INTERSECTION=2;
	protected static final int OP_DIFF_LEFT=3;
	protected static final int OP_DIFF_RIGHT=4;
	
	protected static final int MAX_SHIFT = Hash.LENGTH*2-1;

	protected AHashSet(long count) {
		super(count);
	}

	protected abstract AHashSet<T> mergeWith(AHashSet<T> b, int setOp);

	protected abstract AHashSet<T> mergeWith(AHashSet<T> b, int setOp, int shift);

	@SuppressWarnings("unchecked")
	public AHashSet<T> includeAll(ASet<? extends T> elements) {
		return mergeWith((AHashSet<T>) elements,OP_UNION);
	};
	
	protected final int reverseOp(int setOp) {
		if (setOp>=OP_DIFF_LEFT) {
			setOp=OP_DIFF_LEFT+OP_DIFF_RIGHT-setOp;
		}
		return setOp;
	}
	
	protected final Ref<T> applyOp(int setOp, Ref<T> a, Ref<T> b) {
		switch (setOp) {
		case OP_UNION: return (a==null)?b:a;
		case OP_INTERSECTION: return (a==null)?null:((b==null)?null:a);
		case OP_DIFF_LEFT: return (a==null)?null:((b==null)?a:null);
		case OP_DIFF_RIGHT: return (b==null)?null:((a==null)?b:null);
		default: throw new Panic("Invalid setOp: "+setOp);
		}
	}
	
	protected final AHashSet<T> applySelf(int setOp) {
		switch (setOp) {
		case OP_UNION: return this;
		case OP_INTERSECTION: return this;
		case OP_DIFF_LEFT: return Sets.empty();
		case OP_DIFF_RIGHT: return Sets.empty();
		default: throw new Panic("Invalid setOp: "+setOp);
		}
	}
	
	public ASet<T> intersectAll(ASet<T> elements) {
		return mergeWith((AHashSet<T>) elements,OP_INTERSECTION);
	};

	public ASet<T> excludeAll(ASet<T> elements) {
		return mergeWith((AHashSet<T>) elements,OP_DIFF_LEFT);
	};
	
	public abstract AHashSet<T> toCanonical();
	
	@SuppressWarnings("unchecked")
	public AHashSet<T> conjAll(ACollection<? extends T> elements) {
		if (elements instanceof AHashSet) return includeAll((AHashSet<T>)elements);
		AHashSet<T> result=this;
		long n=elements.count();
		for (long i=0; i<n; i++) {
			result=result.conj(elements.get(i));
		}
		return result;
	};
	

	@Override
	public ASet<T> disjAll(ACollection<T> b) {
		if (b instanceof AHashSet) return excludeAll((AHashSet<T>) b);
		AHashSet<T> result=this;
		long n=b.count();
		for (long i=0; i<n; i++) {
			result=result.excludeHash(b.getElementRef(i).getHash());
		}
		return result;
	}
	
	public abstract AHashSet<T> excludeHash(Hash hash);
	
	public abstract AHashSet<T> includeRef(Ref<T> ref) ;

	@SuppressWarnings("unchecked")
	@Override
	public AHashSet<T> conj(ACell a) {
		return includeRef(Ref.get((T)a));
	}
	
	@Override
	public ASet<T> exclude(ACell a) {
		return excludeHash(Cells.getHash(a));
	}
	
	@Override
	public AHashSet<T> include(T a) {
		return includeRef((Ref<T>) Ref.get(a));
	}

	/**
	 * Validates the set with a given hex prefix. This is necessary to ensure that
	 * child maps are valid, in particular have the correct shift level and that all
	 * hashes start with the correct prefix of hex characters.
	 * 
	 * @param prefix Hash for earlier prefix values
	 * @param digit Hex digit expected at position [shift]
	 * @throws InvalidDataException
	 */
	protected abstract void validateWithPrefix(Hash prefix, int digit, int position) throws InvalidDataException;
	
	@Override
	public Object[] toArray() {
		int s = size();
		Object[] result = new Object[s];
		copyToArray(result, 0);
		return result;
	}
	
	@Override
	public final CVMBool get(ACell key) {
		Ref<T> me = getValueRef(key);
		if (me == null) return CVMBool.FALSE;
		return CVMBool.TRUE;
	}
	
	/**
	 * Gets the Value in the set for the given hash, or null if not found
	 * @param hash Hash of value to check in set
	 * @return The Value for the given Hash if found, null otherwise.
	 */
	public T getByHash(Hash hash) {
		Ref<T> ref=getRefByHash(hash);
		if (ref==null) return null;
		return ref.getValue();
	}
	
	/**
	 * Tests if this Set contains a given hash
	 * @param hash Hash to test for set membership
	 * @return True if set contains value for given hash, false otherwise
	 */
	public abstract boolean containsHash(Hash hash);
	
	@Override
	public boolean contains(ACell key) {
		return getValueRef(key) != null;
	}
}
