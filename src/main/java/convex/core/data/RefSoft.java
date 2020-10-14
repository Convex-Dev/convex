package convex.core.data;

import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;

import convex.core.crypto.Hash;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.store.Stores;
import convex.core.util.Utils;

/**
 * Reference class implemented via a soft reference and store lookup.
 * 
 * Ref makes use of a soft reference to values, allowing memory to be reclaimed
 * by the garbage collector when not required. A MissingDataException will occur
 * with any attempt to deference the {@link this} when this value is not present
 * and not persisted in the current store.
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
public class RefSoft<T> extends Ref<T> {

	static final int ENCODING_LENGTH = Hash.LENGTH+1;
	
	/**
	 * SoftReference to value. Might get updated to a fresh instance.
	 */
	protected SoftReference<T> softRef;
	
	protected RefSoft(SoftReference<T> ref, Hash hash, int status) {
		super(hash, status);
		this.softRef = ref;
	}

	protected RefSoft(T value, Hash hash, int status) {
		this(new SoftReference<T>(value), hash, status);
	}

	protected RefSoft(Hash hash) {
		this(new SoftReference<T>(null), hash, UNKNOWN);
	}

	public static <T> RefSoft<T> create(T value, Hash hash, int status) {
		return new RefSoft<T>(value, hash, status);
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
	public static <T> RefSoft<T> create(Hash hash) {
		return new RefSoft<T>(hash);
	}

	@Override
	public T getValue() {
		T result = softRef.get();
		if (result == null) {
			Ref<T> storeRef = Stores.current().refForHash(hash);
			if (storeRef == null) throw Utils.sneakyThrow(new MissingDataException(hash));
			result = storeRef.getValue();

			// Update soft reference to the fresh version. No point keeping old one....
			if (storeRef instanceof RefSoft) {
				this.softRef = ((RefSoft<T>) storeRef).softRef;
			} else {
				this.softRef = new SoftReference<T>(result);
			}
		}
		return result;
	}

	@Override
	protected Ref<T> updateStatus(int newStatus) {
		if (status == newStatus) return this;
		if ((status >= VERIFIED) && (newStatus < status)) {
			throw new IllegalArgumentException("Shouldn't be able to downgrade status if already verified");
		}
		switch (newStatus) {
		case UNKNOWN:
			throw new IllegalArgumentException("Shouldn't be able to downgrade status to unknown");
		case STORED:
			return new RefSoft<T>(softRef, hash, STORED);
		case PERSISTED:
			return new RefSoft<T>(softRef, hash, PERSISTED);
		case VERIFIED:
			return new RefSoft<T>(softRef, hash, VERIFIED);
		case ANNOUNCED:
			return new RefSoft<T>(softRef, hash, ANNOUNCED);
		case INVALID:
			return new RefSoft<T>(softRef, hash, INVALID);
		default:
			throw new IllegalArgumentException("Ref status not recognised: " + newStatus);
		}
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb=bb.put(Tag.REF);
		return getHash().writeToBuffer(bb);
	}
	
	@Override
	public int write(byte[] bs, int pos) {
		bs[pos++]=Tag.REF;
		return getHash().writeRaw(bs, pos);
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
	public boolean isEmbedded() {
		// Shouldn't be able to store soft references to embedded values
		return false;
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (hash == null) throw new InvalidDataException("Hash should never be null in soft ref", this);
		Object val = softRef.get();
		if (val != null) {
			if (Format.isEmbedded(val)) {
				throw new InvalidDataException("Soft Ref should not contain embedded value", this);
			}
		}
	}

	@Override
	public Ref<T> withValue(T newValue) {
		if (softRef.get()!=newValue) return new RefSoft<T>(newValue,hash,status);
		return this;
	}

	@Override
	public int estimatedEncodingSize() {
		// TODO Is this always right?
		return Hash.LENGTH+1;
	}

	@Override
	protected Blob createEncoding() {
		byte[] bs=new byte[RefSoft.ENCODING_LENGTH];
		int pos=write(bs,0);
		return Blob.wrap(bs,0,pos);
	}
	


}
