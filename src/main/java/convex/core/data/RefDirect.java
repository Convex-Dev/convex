package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.crypto.Hash;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Ref subclass for direct in-memory references.
 * 
 * Direct Refs store the underlying value directly. As such, care must be taken
 * to ensure recursive structures do not exceed reasonable memory bounds. In
 * smart constract execution, juice limits serve this purpose.
 * 
 * @param <T>
 */
public class RefDirect<T> extends Ref<T> {
	/**
	 * Direct value of this Ref
	 */
	private final T value;
	
	/**
	 * Flag for known embedded status. Unknown if false.
	 */
	private boolean embedded=false;
	
	private RefDirect(T value, Hash hash, int status) {
		super(hash, status);
		this.value = value;
	}

	static <T> RefDirect<T> create(T value, Hash hash, int status) {
		return new RefDirect<T>(value, hash, status);
	}

	public static <T> RefDirect<T> create(T value, Hash hash) {
		return create(value, hash, UNKNOWN);
	}

	@SuppressWarnings("unchecked")
	public static <T> RefDirect<T> create(T value) {
		if (value == null) return (RefDirect<T>) Ref.NULL_VALUE;
		return create(value, null);
	}

	protected Ref<T> updateStatus(int newStatus) {
		if (status == newStatus) return this;
		if ((status >= VERIFIED) && (newStatus < status)) {
			throw new IllegalArgumentException("Shouldn't be able to downgrade status if already verified");
		}
		switch (newStatus) {
		case UNKNOWN:
			throw new IllegalArgumentException("Shouldn't be able to downgrade status to unknown");
		case STORED:
			return create(value, hash, STORED);
		case PERSISTED:
			return create(value, hash, PERSISTED);
		case VERIFIED:
			return create(value, hash, VERIFIED);
		case ANNOUNCED:
			return create(value, hash, ANNOUNCED);
		case INVALID:
			return create(value, hash, INVALID);
		default:
			throw new IllegalArgumentException("Ref status not recognised: " + newStatus);
		}
	}

	public T getValue() {
		return value;
	}

	@Override
	public boolean isDirect() {
		return true;
	}

	@Override
	public boolean isEmbedded() {
		if (embedded) return true;
		embedded=Format.isEmbedded(value);
		return embedded;
	}

	@Override
	public Hash getHash() {
		if (hash == null) {
			hash = Hash.compute(value);
		}
		return hash;
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		if (isEmbedded()) {
			// embedded, so write embedded representation directly instead of Ref
			b = Format.write(b, value);
		} else {
			b = b.put(Tag.REF);
			writeRawHash(b);
		}
		return b;
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
			if ((a.hash != null) && (this.hash.equals(a.hash))) return true;
		}
		if (a instanceof RefDirect) {
			// fast non-hashing check for direct objects
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
	public long getMemorySize() {
		if (isEmbedded()) return 0L;
		return ((ACell)value).getMemorySize();
	}

	@Override
	public Ref<T> withValue(T newValue) {
		if (newValue!=value) return new RefDirect<T>(newValue,hash,status);
		return this;
	}

}
