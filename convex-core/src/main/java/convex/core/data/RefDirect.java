package convex.core.data;

import convex.core.exceptions.InvalidDataException;
import convex.core.store.AStore;

/**
 * Ref subclass for direct in-memory references.
 * 
 * Direct Refs store the underlying value directly with a regular Java strong reference. 
 * 
 * <p>Care must be taken to ensure recursive structures do not exceed reasonable memory bounds. 
 * In smart contract execution, juice limits serve this purpose. </p>
 * 
 * @param <T> Type of Value referenced
 */
public class RefDirect<T extends ACell> extends Ref<T> {
	/**
	 * Direct value of this Ref
	 */
	private final T value;
	
	private RefDirect(T value, Hash hash, int flags) {
		super(hash, flags);
		this.value = value;
	}
	 
    /**
     * Construction function for a Direct Ref
     * @param <T> Type of value
     * @param value Value for the Ref
     * @param hash Hash (may be null)
     * @param status Status for the Ref
     * @return New Direct Ref
     */
	public static <T extends ACell> RefDirect<T> create(T value, Hash hash, int status) {
		int flags=status&Ref.STATUS_MASK;
		return new RefDirect<T>(value, hash, flags);
	}

	/**
	 * Creates a new Direct ref to the given value. Does not compute hash.
	 * @param <T> Type of Value
	 * @param value Value
	 * @return Direct Ref to Value
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> RefDirect<T> create(T value) {
		if (value==null) return (RefDirect<T>) Ref.NULL_VALUE;
		return create(value, null, UNKNOWN);
	}

	public T getValue() {
		return value;
	}

	@Override
	public boolean isDirect() {
		return true;
	}

	@Override
	public Hash getHash() {
		if (hash!=null) return hash;
		if (value==null) return Hash.NULL_HASH;
		Hash newHash=value.getHash();
		hash=newHash;
		return newHash;
	}

	@Override
	public RefDirect<T> toDirect() {
		return this;
	}
	
	@Override
	public RefSoft<T> toSoft(AStore store) {
		return RefSoft.create(store, value, flags);
	}

	@Override
	public boolean equals(Ref<T> a) {
		if (a == this) return true;
		if (a instanceof RefDirect) {
			T va=((RefDirect<T>) a).value;
			if (value==va) return true; // fast path
			if (value==null) return va==null; // catch nulls
			if (this.hash != null) {
				Hash ha=a.hash;
				// use hash if available for both Refs
				if (ha != null) return this.hash.equals(ha);
			}
			return value.equals(va);
		} else {
			// Don't want to pull from store, so use hash comparison
			return getHash().equals(a.getHash());
		}
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (isEmbedded() != Format.isEmbedded(value)) throw new InvalidDataException("Embedded flag is wrong!", this);
		if (value == null) {
			if (this != Ref.NULL_VALUE) throw new InvalidDataException("Null Ref not singleton!", this);
		}
	}

	@Override
	public Ref<T> withValue(T newValue) {
		if (newValue!=value) return new RefDirect<T>(newValue,hash,flags);
		return this;
	}

	@Override
	public int estimatedEncodingSize() {
		if(value==null) return Format.NULL_ENCODING_LENGTH;
		return isEmbedded()?value.estimatedEncodingSize():Ref.INDIRECT_ENCODING_LENGTH;
	}

	@Override
	public boolean isMissing() {
		// Never missing, since we have the value at hand
		return false;
	}

	@Override
	public RefDirect<T> withFlags(int newFlags) {
		return new RefDirect<T>(value,hash,newFlags);
	}

	@SuppressWarnings("unchecked")
	@Override
	public RefDirect<T> ensureCanonical() {
		if ((value==null)||value.isCanonical()) return this;
		return new RefDirect<T>((T)value.toCanonical(), hash,flags);
	}
}
