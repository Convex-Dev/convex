package convex.core.data;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.function.Consumer;

import convex.core.crypto.Hash;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;

/**
 * Class representing a smart reference to a decentralised data object.
 * 
 * "The greatest trick the Devil ever pulled was convincing the world he didn’t
 * exist." - The Usual Suspects
 * 
 * A Ref itself is not a cell, but may be contained within a cell, in which case
 * the cell class must implement IRefContainer in order to persist and update
 * contained Refs correctly
 * 
 * Refs include a status that indicates the level of validation proven. It is
 * important not to rely on the value of a Ref until it has a sufficient status
 * - e.g. a minimum status of PERSISTED is required to be able to guarantee
 * walking an entire nested data structure.
 * 
 * Guarantees: - O(1) access to the Hash value, cached on first access - O(1)
 * access to the referenced object (though may required hitting storage if not
 * cached) - Indirectly referenced values may be collected by the garbage
 * collector, with the assumption that they can be retrieved from storage if
 * required
 *
 * @param <T> Type of stored value
 */
public abstract class Ref<T extends ACell> extends AObject implements Comparable<Ref<T>>, IWriteable, IValidated {

	/**
	 * Ref status indicating the status of this Ref is unknown. This is the default
	 * for new Refs
	 */
	public static final int UNKNOWN = 0;

	/**
	 * Ref status indicating the Ref has been shallowly persisted in long term
	 * storage. The Ref can be made soft, and retrieved from storage if needed. No
	 * guarantee about the existence / status of any child objects.
	 */
	public static final int STORED = 1;

	/**
	 * Ref status indicating the Ref has been deeply persisted in long term storage.
	 * The Ref and its children can be assumed to be accessible for the life of the
	 * storage subsystem execution.
	 */
	public static final int PERSISTED = 2;

	/**
	 * Ref status indicating the Ref has been both persisted and verified as genuine
	 * valid data.
	 */
	public static final int VERIFIED = 3;

	/**
	 * Ref status indicating the Ref has been shared by this peer in an announced
	 * Belief. This means that the Peer has a commitment to maintain this data
	 */
	public static final int ANNOUNCED = 4;

	public static final int MAX_STATUS = ANNOUNCED;
	
	/**
	 * Ref status indicating that the Ref refers to data that has been proven to be invalid
	 */
	public static final int INVALID = -1;

	/**
	 * Ref for null value. Important because we can't persist this, since null
	 * collides with the result of an empty soft reference.
	 */
	public static final RefDirect<?> NULL_VALUE = RefDirect.create(null, Hash.NULL_HASH, MAX_STATUS);

	public static final RefDirect<CVMBool> TRUE_VALUE = RefDirect.create(CVMBool.TRUE, Hash.TRUE_HASH, MAX_STATUS);
	public static final RefDirect<CVMBool> FALSE_VALUE = RefDirect.create(CVMBool.FALSE, Hash.FALSE_HASH, MAX_STATUS);

	public static final RefDirect<AList<?>> EMPTY_LIST = RefDirect.create(Lists.empty());
	public static final RefDirect<AVector<?>> EMPTY_VECTOR = RefDirect.create(Vectors.empty());

	public static final int BYTE_LENGTH = 32;

	public static final int INDIRECT_ENCODING_LENGTH = 1+BYTE_LENGTH;

	

	/**
	 * Hash of the serialised representation of the value Computed and stored upon
	 * demand.
	 */
	protected Hash hash;

	/**
	 * Status of this Ref. See public Ref status constants.
	 * 
	 * May be incremented atomically in the event of validation, proven storage.
	 */
	protected int status;

	protected Ref(Hash hash, int status) {
		this.hash = hash;
		this.status = status;
	}

	/**
	 * Gets the status of this Ref
	 * 
	 * @return UNKNOWN, PERSISTED, VERIFIED, ACCOUNCED or INVALID Ref status
	 *         constants
	 */
	public int getStatus() {
		return status;
	}

	/**
	 * Updates the status of this Ref to the specified value. Assumes any necessary
	 * changes to storage will be made separately. SECURITY: Dangerous if misused
	 * since may invalidate storage assumptions
	 */
	protected abstract Ref<T> updateStatus(int newStatus);

	/**
	 * Ensures the Ref has the given status, at minimum
	 */
	public Ref<T> withMinimumStatus(int newStatus) {
		if (status < newStatus) {
			return updateStatus(newStatus);
		}
		return this;
	}

	/**
	 * Gets the value from this Ref.
	 * 
	 * Important notes: - May throw a MissingDataException if the data does not
	 * exist in available storage - Will return null if and only if the Ref refers
	 * to the null value
	 * 
	 * @return The value contained in this Ref
	 */
	public abstract T getValue();

	@Override
	public int hashCode() {
		return getHash().hashCode();
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#ref {:hash ");
		sb.append(Utils.ednString(hash));
		sb.append(", :status ");
		sb.append(status);
		sb.append("}");
	}
	
	@Override
	public void print(StringBuilder sb) {
		ednString(sb);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		try {
			ednString(sb);
		} catch (MissingDataException e) {
			throw Utils.sneakyThrow(e);
		}
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Ref)) return false;
		return equalsValue((Ref<T>) o);
	}

	/**
	 * Checks if two Ref objects are equal. Equality is defined as referring to the
	 * same data, i.e. have an identical hash.
	 * 
	 * @param a The Ref to compare with
	 * @return true if Refs have the same value, false otherwise
	 */
	public abstract boolean equalsValue(Ref<T> a);

	@Override
	public int compareTo(Ref<T> a) {
		if (this == a) return 0;
		return getHash().compareTo(a.getHash());
	}

	/**
	 * Gets the Hash of this ref's value.
	 * 
	 * @return Hash of the value
	 */
	public abstract Hash getHash();
	
	/**
	 * Gets the Hash of this ref's value, or null if not yet computed
	 * 
	 * @return Hash of the value
	 */
	public final Hash cachedHash() {
		return hash;
	}

	/**
	 * Returns a direct Ref wrapping the given value. Does not perform any Ref
	 * lookup in stores etc.
	 * 
	 * @param value Value to wrap in the Ref
	 * @return New Ref wrapping the given value.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T> get(T value) {
		if (value==null) return (Ref<T>) NULL_VALUE;
		return ((T)value).getRef();
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T> get(Object value) {
		if (value==null) return (Ref<T>) NULL_VALUE;
		if (value instanceof ACell) return ((ACell)value).getRef();
		return RT.cvm(value).getRef();
	}

	/**
	 * Creates a persisted Ref with the given value in the current store..
	 * 
	 * @param value Any CVM value to persist
	 * @return Ref to the given value
	 */
	public static <T extends ACell> Ref<T> createPersisted(T value) {
		return createPersisted(value, null);
	}

	/**
	 * Creates a persisted Ref with the given value in the current store.
	 * 
	 * Novelty handler is called for all new Refs that are persisted (recursively),
	 * starting from lowest levels.
	 * 
	 * @param value Any CVM value to persist
	 * @return Persisted Ref
	 */
	public static <T extends ACell> Ref<T> createPersisted(T value, Consumer<Ref<ACell>> noveltyHandler) {
		Ref<T> ref = RefDirect.create(value, null, Ref.UNKNOWN);
		return (Ref<T>) Stores.current().persistRef(ref, noveltyHandler);
	}
	
	/**
	 * Creates an ANNOUNCED Ref with the given value in the current store.
	 * 
	 * Novelty handler is called for all new Refs that are persisted (recursively),
	 * starting from lowest levels.
	 * 
	 * @param value
	 * @return Persisted Ref
	 */
	public static <T extends ACell> Ref<T> createAnnounced(T value, Consumer<Ref<ACell>> noveltyHandler) {
		if (Format.isEmbedded(value)) {
			return RefDirect.create(value, null, Ref.ANNOUNCED);
		}
		Ref<T> ref = RefDirect.create(value, null, Ref.UNKNOWN);
		AStore store=Stores.current();
		return (Ref<T>) store.announceRef(ref, noveltyHandler);
	}

	/**
	 * Creates a Ref using a specific Hash. Fetches the actual value lazily from the
	 * store on demand.
	 * 
	 * Internal soft reference may be initially empty: This Ref might not have
	 * available data in the store, in which case calls to getValue() may result in
	 * a MissingDataException
	 * 
	 * @param hash The hash value for this Ref to refer to
	 * @return Ref for the specific hash.
	 */
	public static <T extends ACell> Ref<T> forHash(Hash hash) {
		return RefSoft.create(hash);
	}

	/**
	 * Creates a Ref by reading a raw hash value from the specified offset in a Blob
	 * 
	 * @param src
	 * @param offset
	 * @return Ref read from blob data
	 */
	public static Ref<ABlob> readRaw(Blob src, int offset) {
		Hash hash = Hash.wrap(src.getInternalArray(), src.offset + offset, BYTE_LENGTH);
		return forHash(hash);
	}

	/**
	 * Reads a ref from the given ByteBuffer Assumes no tag.
	 * 
	 * @param data ByteBuffer containing the data to read at the current position
	 * @return Ref read from ByteBuffer
	 */
	public static <T extends ACell> Ref<T> readRaw(ByteBuffer data) {
		Hash h = Hash.read(data);
		return Ref.forHash(h);
	}

	public void validate() throws InvalidDataException {
		if (hash != null) hash.validate();
		// TODO is this sane?
		if (status < VERIFIED) {
			T o = getValue();
			o.validate();
		}
	}

	/**
	 * Return true if this Ref is a direct reference, i.e. the value is pinned in
	 * memory and cannot be garbage collected
	 * 
	 * @return true if this Ref is direct, false otherwise
	 */
	public abstract boolean isDirect();

	/**
	 * Return true if this Ref's status indicates it has definitely been persisted
	 * to storage.
	 * 
	 * May return false negatives, e.g. the object could be in the store but this
	 * Ref instance still has a status of "UNKNOWN".
	 * 
	 * @return true if this Ref has a status of PERSISTED or above, false otherwise
	 */
	public boolean isPersisted() {
		return status >= PERSISTED;
	}
	
	/**
	 * Persists this Ref in the current store if not embedded and not already
	 * persisted.
	 * 
	 * This may convert the Ref from a direct reference to a soft reference.
	 * 
	 * If the persisted Ref represents novelty, will trigger the specified novelty
	 * handler 
	 * 
	 * @return the persisted Ref 
	 * @throws MissingDataException If the Ref's value does not exist or has been
	 *         garbage collected before being persisted 
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> persist(Consumer<Ref<ACell>> noveltyHandler) {
		int status = getStatus();
		if (status >= PERSISTED) return (Ref<R>) this; // already persisted in some form
		AStore store=Stores.current();
		return (Ref<R>) store.persistRef(this, noveltyHandler);
	}
	
	/**
	 * Persists this Ref in the current store if not embedded and not already
	 * persisted. Resulting status will be PERSISTED or higher.
	 * 
	 * This may convert the Ref from a direct reference to a soft reference.
	 * 
	 * @throws MissingDataException if the Ref cannot be fully persisted.
	 * @return the persisted Ref
	 */
	public <R extends ACell> Ref<R> persist() {
		return persist(null);
	}
	
	/**
	 * Persists this Ref in the current store if not embedded and not already
	 * persisted.
	 * 
	 * This may convert the Ref from a direct reference to a soft reference.
	 * 
	 * If the persisted Ref represents novelty, will trigger the specified novelty
	 * handler 
	 * 
	 * @return the persisted Ref 
	 * @throws MissingDataException If the Ref's value does not exist or has been
	 *         garbage collected before being persisted 
	 */
	@SuppressWarnings("unchecked")
	public Ref<T> announce(Consumer<Ref<ACell>> noveltyHandler) {
		int status = getStatus();
		if (status >= ANNOUNCED) return this; // already announced
		AStore store=Stores.current();
		return (Ref<T>) store.announceRef((Ref<ACell>)this, noveltyHandler);
	}
	
	/**
	 * Persists this Ref in the current store if not embedded and not already
	 * persisted. Resulting status will be PERSISTED or higher.
	 * 
	 * This may convert the Ref from a direct reference to a soft reference.
	 * 
	 * @throws MissingDataException if the Ref cannot be fully persisted.
	 * @return the persisted Ref
	 */
	public Ref<T> announce() {
		return announce(null);
	}

	/**
	 * Accumulates the set of all unique Refs in the given object.
	 * 
	 * Might stack overflow if nesting is too deep - not for use in on-chain code.
	 * 
	 * @param a
	 * @return Set containing all unique refs (accoumulated recursively) within the
	 *         given object
	 */
	public static java.util.Set<Ref<?>> accumulateRefSet(Object a) {
		HashSet<Ref<?>> hs = new HashSet<>();
		accumulateRefSet(a, hs);
		return hs;
	}

	private static void accumulateRefSet(Object a, HashSet<Ref<?>> hs) {
		if (a instanceof Ref) {
			Ref<?> ref = (Ref<?>) a;
			if (hs.contains(ref)) return;
			hs.add(ref);
			accumulateRefSet(ref.getValue(), hs);
		} else if (a instanceof ACell) {
			ACell rc = (ACell) a;
			rc.updateRefs(r -> {
				accumulateRefSet(r, hs);
				return r;
			});
		}
	}



	/**
	 * Updates an array of Refs with the given function.
	 * 
	 * Returns the original array unchanged if no refs were changed, otherwise
	 * returns a new array.
	 * 
	 * @return Array of updated Refs
	 */
	public static <T extends ACell> Ref<T>[] updateRefs(Ref<T>[] refs, IRefFunction func) {
		Ref<T>[] newRefs = null;
		int n = refs.length;
		for (int i = 0; i < n; i++) {
			Ref<T> ref = refs[i];
			@SuppressWarnings("unchecked")
			Ref<T> newRef = (Ref<T>) func.apply(ref);
			if (ref != newRef) {
				if (newRefs == null) {
					newRefs = refs.clone();
				}
				newRefs[i] = newRef;
			}
		}
		if (newRefs != null) return newRefs;
		return refs;
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T>[] createArray(T[] values) {
		int n = values.length;
		Ref<T>[] refs = new Ref[n];
		for (int i = 0; i < n; i++) {
			refs[i] = Ref.get(values[i]);
		}
		return refs;
	}

	/**
	 * Adds the value of this Ref and all non-embedded child values to a given set.
	 * 
	 * Logically, provides the guarantee that the set will contain all cells needed
	 * to recreate the complete value of this Ref.
	 * 
	 * @param store
	 * @return Set containing this Ref and all direct or indirect child refs
	 */
	@SuppressWarnings("unchecked")
	public ASet<ACell> addAllToSet(ASet<ACell> store) {
		store = store.includeRef((Ref<ACell>) this);
		ACell rc = getValue();
		
		int n = rc.getRefCount();
		for (int i = 0; i < n; i++) {
			Ref<ACell> rr = rc.getRef(i);
			if (rr.isEmbedded()) continue;
			store = rr.addAllToSet(store);
		}
		return store;
	}

	/**
	 * Check if the Ref's value is embedded. 
	 * 
	 * If false, the value must be an ACell instance.
	 * 
	 * @return true if embedded, false otherwise
	 */
	public abstract boolean isEmbedded();

	/**
	 * Converts this Ref to a RefDirect
	 * @return
	 */
	public Ref<T> toDirect() {
		return RefDirect.create(getValue(), hash, status);
	}

	/**
	 * Persists a Ref shallowly in the current store.
	 * 
	 * Status will be updated to STORED or higher.
	 * 
	 * @return Ref with status of STORED or above
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> persistShallow() {
		AStore store=Stores.current();
		return (Ref<R>) store.storeRef((Ref<ACell>)this, null);
	}
	
	/**
	 * Persists a Ref shallowly in the current store.
	 * 
	 * Status will be updated STORED or higher. Novelty handler will be called exactly once if and only if
	 * the ref was not previously stored.
	 * 
	 * @return Ref with status of STORED or above
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> persistShallow(Consumer<Ref<ACell>> noveltyHandler) {
		AStore store=Stores.current();
		return (Ref<R>) store.storeRef((Ref<ACell>)this, noveltyHandler);
	}

	/**
	 * Updates the value stored within this Ref. New value must be equal in value to the old value (identical hash), 
	 * but may have updated internal refs etc.
	 * 
	 * @param newValue
	 * @return Updated Ref
	 */
	public abstract Ref<T> withValue(T newValue);
	
	/**
	 * Writes the ref to a byte array. Embdeds embedded values as necessary.
	 * @param bb
	 * @return
	 */
	public abstract int encode(byte[] bs,int pos);
	
	/**
	 * Writes the raw ref Hash to the given ByteBuffer
	 * @param bb
	 * @return
	 */
	public int writeRawHash(byte[] bs,int pos) {
		return getHash().encodeRaw(bs,pos);
	}

	/**
	 * Gets the encoding length for writing this Ref. Will be equal to the encoding length
	 * of the Ref's value if embedded.
	 *  
	 * @return
	 */
	public abstract long getEncodingLength();

	/**
	 * Gets the indirect memory size for this Ref
	 * @return 0 for fully embedded values with no child refs, memory size of referred value otherwise
	 */
	public long getMemorySize() {
		T value=getValue();
		if (value==null) return 0;
		return value.getMemorySize();
	}

}
