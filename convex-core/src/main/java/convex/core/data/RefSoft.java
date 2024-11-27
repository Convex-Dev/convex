package convex.core.data;

import java.lang.ref.SoftReference;

import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.store.AStore;
import convex.core.store.Stores;

/**
 * Reference class implemented via a soft reference and store lookup.
 * 
 * Ref makes use of a soft reference to values, allowing memory to be reclaimed
 * by the garbage collector when not required. A MissingDataException will occur
 * with any attempt to deference this Ref when the value is not present
 * and not stored in the current store.
 * 
 * Instances of this class should usually be be STORED, otherwise data loss
 * may occur due to garbage collection. However UNKNOWN RefSoft may exist temporarily 
 * (e.g. reading Refs from external messages)
 * 
 * RefSoft must always have a non-null hash, to ensure lookup capability in
 * store.
 * 
 * RefSoft must always store a canonical value, if any
 * 
 * @param <T> Type of referenced Cell
 */
public class RefSoft<T extends ACell> extends Ref<T> {
	
	/**
	 * SoftReference to value. Might get updated to a fresh instance.
	 */
	protected SoftReference<T> softRef;
	
	/**
	 * SoftReference to value. Might get updated to a fresh instance.
	 */
	protected final AStore store;
	
	protected RefSoft(AStore store, SoftReference<T> ref, Hash hash, int flags) {
		super(hash, flags);
		this.softRef = ref;
		this.store=store;
	}

	protected RefSoft(AStore store, T value, Hash hash, int flags) {
		this(store,createSoftReference(value), hash, flags);
	}

	protected RefSoft(AStore store, Hash hash) {
		// We don't know anything about this Ref.
		this(store,new SoftReference<T>(null), hash, UNKNOWN);
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends ACell> SoftReference<T> createSoftReference(T value) {
		if (!value.isCanonical()) {
			value=(T) value.toCanonical();
		}
		return new SoftReference<T>(value);
	}
	

	@Override
	public RefSoft<T> withFlags(int newFlags) {
		if (flags==newFlags) return this;
		return new RefSoft<T>(store,softRef,hash,newFlags);
	}

	public static <T extends ACell> RefSoft<T> create(AStore store,T value, int flags) {
		Hash hash=Hash.get(value);
		return new RefSoft<T>(store,value, hash, flags);
	}

	/**
	 * Create a RefSoft with a Hash reference.
	 * 
	 * Attempts to get the value will trigger a store lookup, which may in turn
	 * cause a MissingDataException if not found.
	 * 
	 * @param <T>  Type of value
	 * @param hash Hash ID of value.
	 * @return New RefSoft instance
	 */
	public static <T extends ACell> RefSoft<T> createForHash(Hash hash) {
		return new RefSoft<T>(Stores.current(),hash);
	}

	@Override
	public T getValue() {
		T result = softRef.get();
		if (result == null) {
			Ref<T> storeRef = store.refForHash(hash);
			if (storeRef == null) {
				throw new MissingDataException(store,hash);
			}
			this.flags=Ref.mergeFlags(this.flags, storeRef.flags);
			result = storeRef.getValue();

			if (storeRef instanceof RefSoft) {
				// Update soft reference to the fresh version. No point keeping old one....
				this.softRef = ((RefSoft<T>) storeRef).softRef;
			} else {
				// Create a new soft reference
				this.softRef = new SoftReference<T>(result);
			}
		}
		return result;
	}
	
	@Override
	public boolean isMissing() {
		T result = softRef.get();
		if (result != null) return false; // still in memory, so not missing
		
		// check store
		Ref<T> storeRef = store.refForHash(hash);
		if (storeRef == null) return true; // must be missing, couldn't find in store

		// We know we have in store.
		// Update soft reference to the fresh version. No point keeping old one....
		if (storeRef instanceof RefSoft) {
			this.softRef = ((RefSoft<T>) storeRef).softRef;
		} else {
			this.softRef = new SoftReference<T>(storeRef.getValue());
		}
		this.flags=Ref.mergeFlags(this.flags, storeRef.flags);
		return false;
	}

	@Override
	public boolean equals(Ref<T> a) {
		if (a.hash!=null) {
			// prefer hash comparison, this avoid potential store lookups
			return hash.equals(a.hash);
		}
		// compare by value
		return Cells.equals(getValue(),a.getValue());
	}

	@Override
	public boolean isDirect() {
		return false;
	}
	
	@Override
	public RefDirect<T> toDirect() {
		return RefDirect.create(getValue(), hash, flags);
	}
	
	@Override
	public RefSoft<T> toSoft(AStore store) {
		return withStore(store);
	}

	@Override
	public Hash getHash() {
		return hash;
	}


	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (hash == null) throw new InvalidDataException("Hash should never be null in soft ref", this);
		ACell val = softRef.get();
		boolean embedded=isEmbedded();
		if (embedded!=Cells.isEmbedded(val)) {
			throw new InvalidDataException("Embedded flag ["+embedded+"] inconsistent with value", this);
		}
	}

	@Override
	public Ref<T> withValue(T newValue) {
		if (softRef.get()!=newValue) return new RefSoft<T>(store,newValue,hash,flags);
		return this;
	}
	
	public RefSoft<T> withStore(AStore store) {
		if (this.store==store) return this;
		return new RefSoft<T>(store,softRef,hash,flags);
	}

	@Override
	public int estimatedEncodingSize() {
		return isEmbedded()?Format.MAX_EMBEDDED_LENGTH: INDIRECT_ENCODING_LENGTH;
	}

	@Override
	public Ref<T> ensureCanonical() {
		return this;
	}

	public AStore getStore() {
		return store;
	}

	/**
	 * Checks if this Ref still has a local reference
	 * @return True if an in memory reference exists
	 */
	public boolean hasReference() {
		return softRef.get()!=null;
	}


}
