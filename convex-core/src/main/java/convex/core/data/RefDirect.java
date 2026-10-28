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
	/**
	 * Ref for null value. Important because we can't persist this, since null
	 * collides with the result of an empty soft reference.
	 *
	 * <p>Held here rather than on {@link Ref} because creating it constructs a
	 * {@code RefDirect}, and {@code Ref} initialisation building its own subclass is a
	 * cycle. {@code Ref.get} reads this, so it must also stay clear of anything that
	 * reads {@code CVMBool} — see {@link Refs}.</p>
	 */
	public static final RefDirect<?> NULL_VALUE = create(null, Hash.NULL_HASH, Ref.INTERNAL_FLAGS);

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
		if (value==null) return (RefDirect<T>) NULL_VALUE;
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
			if ((value==null)||(va==null)) return false; // only one is null
			Hash h=this.hash;
			if (h != null) {
				Hash ha=a.hash;
				if (ha != null) return h.equals(ha); // both already hashed
			}
			// Embedded values compare by value: the walk is bounded by the embedded
			// limit, and no encoding ever needs their hash. Equal values have the same
			// canonical encoding, so a value embedded on one side only is unequal.
			boolean em=isEmbedded();
			if (em!=a.isEmbedded()) return false;
			if (em) return value.equals(va);
		}
		// Branch values compare by hash, computed and cached on the refs, so shared
		// subtrees are hashed once and a deep comparison stays linear.
		return getHash().equals(a.getHash());
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (isEmbedded() != Cells.isEmbedded(value)) throw new InvalidDataException("Embedded flag is wrong!", this);
		if (value == null) {
			if (this != NULL_VALUE) throw new InvalidDataException("Null Ref not singleton!", this);
		}
	}

	@Override
	public Ref<T> withValue(T newValue) {
		if (newValue!=value) return new RefDirect<T>(newValue,hash,flags);
		return this;
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
