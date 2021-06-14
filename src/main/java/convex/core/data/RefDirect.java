package convex.core.data;

import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Ref subclass for direct in-memory references.
 * 
 * Direct Refs store the underlying value directly. 
 * 
 * <p>Care must be taken to ensure recursive structures do not exceed reasonable memory bounds. 
 * In smart contract execution, juice limits serve this purpose. </p>
 * 
 * @param <T>
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
     * @param <T>
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
	 * Creates a direct Ref to the given value
	 * @param <T>
	 * @param value Any value (may be embedded or otherwise)
	 * @param hash Hash of value, or null if not known
	 * @return
	 */
	public static <T extends ACell> RefDirect<T> create(T value, Hash hash) {
		return create(value, hash, UNKNOWN);
	}

	/**
	 * Creates a new Direct ref to the given value. Does not compute hash.
	 * @param <T>
	 * @param value
	 * @return
	 */
	public static <T extends ACell> RefDirect<T> create(T value) {
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
		Hash newHash=(value==null)?Hash.NULL_HASH:value.getHash();
		hash=newHash;
		return newHash;
	}

	@Override
	public Ref<T> toDirect() {
		return this;
	}

	@Override
	public boolean equalsValue(Ref<T> a) {
		if (a == this) return true;
		if (this.hash != null) {
			// use hash if available
			if (a.hash != null) return this.hash.equals(a.hash);
		}
		if (a instanceof RefDirect) {
			// faster, potentially non-hashing check for direct objects
			return Utils.equals(this.value, a.getValue());
		}
		// fallback to computing hashes
		return getHash().equals(a.getHash());
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (isEmbedded() != Format.isEmbedded(value)) throw new InvalidDataException("Embedded flag is wrong!", this);
		if (value == null) {
			if (this != Ref.NULL_VALUE) throw new InvalidDataException("Null ref not singleton!", this);
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
	protected RefDirect<T> withFlags(int newFlags) {
		return new RefDirect<T>(value,hash,newFlags);
	}



}
