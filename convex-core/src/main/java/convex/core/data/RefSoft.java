package convex.core.data;

import java.lang.ref.SoftReference;

import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.store.Stores;
import convex.core.util.Utils;

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
 * SoftRef must always have a non-null hash, to ensure lookup capability in
 * store.
 * 
 * @param <T>
 */
public class RefSoft<T extends ACell> extends Ref<T> {
	
	/**
	 * SoftReference to value. Might get updated to a fresh instance.
	 */
	protected SoftReference<T> softRef;
	
	protected RefSoft(SoftReference<T> ref, Hash hash, int flags) {
		super(hash, flags);
		this.softRef = ref;
	}

	protected RefSoft(T value, Hash hash, int flags) {
		this(new SoftReference<T>(value), hash, flags);
	}

	protected RefSoft(Hash hash) {
		// We don't know anything about this Ref.
		this(new SoftReference<T>(null), hash, UNKNOWN);
	}
	

	@Override
	public RefSoft<T> withFlags(int newFlags) {
		return new RefSoft<T>(softRef,hash,newFlags);
	}

	public static <T extends ACell> RefSoft<T> create(T value, int flags) {
		Hash hash=Hash.compute(value);
		return new RefSoft<T>(value, hash, flags);
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
		return new RefSoft<T>(hash);
	}

	@Override
	public T getValue() {
		T result = softRef.get();
		if (result == null) {
			Ref<T> storeRef = Stores.current().refForHash(hash);
			if (storeRef == null) throw Utils.sneakyThrow(new MissingDataException(hash));
			result = storeRef.getValue();

			if (storeRef instanceof RefSoft) {
				// Update soft reference to the fresh version. No point keeping old one....
				this.softRef = ((RefSoft<T>) storeRef).softRef;
			} else {
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
		Ref<T> storeRef = Stores.current().refForHash(hash);
		if (storeRef == null) return true; // must be missing, couldn't find in store

		// We know we have in store.
		// Update soft reference to the fresh version. No point keeping old one....
		if (storeRef instanceof RefSoft) {
			this.softRef = ((RefSoft<T>) storeRef).softRef;
		} else {
			this.softRef = new SoftReference<T>(storeRef.getValue());
		}
		return false;
	}

	@Override
	public boolean equalsValue(Ref<T> a) {
		if (a == this) return true;
		if ((this.hash == a.hash) && (this.hash != null)) return true;
		return getHash().equals(a.getHash());
	}

	@Override
	public boolean isDirect() {
		return false;
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
		if (embedded!=Format.isEmbedded(val)) {
			throw new InvalidDataException("Embedded flag ["+embedded+"] inconsistent with value", this);
		}
	}

	@Override
	public Ref<T> withValue(T newValue) {
		if (softRef.get()!=newValue) return new RefSoft<T>(newValue,hash,flags);
		return this;
	}

	@Override
	public int estimatedEncodingSize() {
		return isEmbedded()?Format.MAX_EMBEDDED_LENGTH: INDIRECT_ENCODING_LENGTH;
	}
}
