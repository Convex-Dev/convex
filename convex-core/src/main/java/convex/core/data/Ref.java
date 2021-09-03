package convex.core.data;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.function.Consumer;

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
	 * Ref status indicating the Ref has been both persisted and validated as genuine
	 * valid CVM data.
	 */
	public static final int VALIDATED = 3;

	/**
	 * Ref status indicating the Ref has been shared by this peer in an announced
	 * Belief. This means that the Peer has a commitment to maintain this data
	 */
	public static final int ANNOUNCED = 4;
	
	/**
	 * Ref status indicating the Ref is an internal embedded value that can be
	 * encoded and used independency of any given store state
	 */
	public static final int INTERNAL = 5;

	/**
	 * Maximum Ref status
	 */
	public static final int MAX_STATUS = INTERNAL;
	
	/**
	 * MAsk for Ref flag bits representing the Status
	 */
	public static final int STATUS_MASK = 0x0F;
	
	/**
	 * Mask bit for a proven embedded value
	 */
	public static final int KNOWN_EMBEDDED_MASK = 0x10;
	
	/**
	 * Mask bit for a proven non-embedded value
	 */
	public static final int NON_EMBEDDED_MASK = 0x20;

	/**
	 * Mask for embedding status
	 */
	public static final int EMBEDDING_MASK = KNOWN_EMBEDDED_MASK | NON_EMBEDDED_MASK;
	
	/**
	 * Mask bit for verified data, especially signatures
	 */
	public static final int VERIFIED_MASK = 0x40;

	/**
	 * Mask bit for bad data, especially signatures proved invalid
	 */
	public static final int BAD_MASK = 0x80;
	
	/**
	 * Mask bit for bad data, especially signatures proved invalid
	 */
	public static final int VERIFICATION_MASK = VERIFIED_MASK | BAD_MASK;

	/**
	 * Flags for internal constant values
	 */
	public static final int INTERNAL_FLAGS=INTERNAL|KNOWN_EMBEDDED_MASK|VERIFIED_MASK;
	
	/**
	 * Ref status indicating that the Ref refers to data that has been proven to be invalid
	 */
	public static final int INVALID = -1;

	/**
	 * Ref for null value. Important because we can't persist this, since null
	 * collides with the result of an empty soft reference.
	 */
	public static final RefDirect<?> NULL_VALUE = RefDirect.create(null, Hash.NULL_HASH, INTERNAL_FLAGS);

	public static final RefDirect<CVMBool> TRUE_VALUE = RefDirect.create(CVMBool.TRUE, Hash.TRUE_HASH, INTERNAL_FLAGS);
	public static final RefDirect<CVMBool> FALSE_VALUE = RefDirect.create(CVMBool.FALSE, Hash.FALSE_HASH, INTERNAL_FLAGS);

	/**
	 * Length of an external Reference encoding. Will be a tag byte plus the Hash length
	 */
	public static final int INDIRECT_ENCODING_LENGTH = 1+Hash.LENGTH;

	

	/**
	 * Hash of the serialised representation of the value Computed and stored upon
	 * demand.
	 */
	protected Hash hash;

	/**
	 * Flag values including Status of this Ref. See public Ref status constants.
	 * 
	 * May be incremented atomically in the event of validation, proven storage.
	 */
	protected int flags;

	protected Ref(Hash hash, int flags) {
		this.hash = hash;
		this.flags = flags;
	}

	/**
	 * Gets the status of this Ref
	 * 
	 * @return UNKNOWN, PERSISTED, VERIFIED, ACCOUNCED or INVALID Ref status
	 *         constants
	 */
	public int getStatus() {
		return flags&STATUS_MASK;
	}
	
	/**
	 * Gets flags with an updated status
	 * @param newStatus New status to apply to flags
	 * @return Updated flags (does not change this Ref)
	 */
	public int flagsWithStatus(int newStatus) {
		return (flags&~STATUS_MASK)|(newStatus&STATUS_MASK);
	}
	
	/**
	 * Gets the flags for this Ref
	 * 
	 * @return flag int value
	 */
	public int getFlags() {
		return flags;
	}

	/**
	 * Ensures the Ref has the given status, at minimum
	 * 
	 * Assumes any necessary changes to storage will be made separately. 
	 * SECURITY: Dangerous if misused since may invalidate storage assumptions
	 * @param newStatus New status to apply to Ref
	 * @return Updated Ref
	 */
	public Ref<T> withMinimumStatus(int newStatus) {
		newStatus&=STATUS_MASK;
		int status=getStatus();
		if (status >= newStatus) return this;
		if (status > MAX_STATUS) {
			throw new IllegalArgumentException("Ref status not recognised: " + newStatus);
		}
		int newFlags=(flags&(~STATUS_MASK))|newStatus;
		return withFlags(newFlags);
	}

	/**
	 * Create a new Ref of the same type with updated flags
	 * @param newFlags New flags to set
	 * @return Updated Ref
	 */
	public abstract Ref<T> withFlags(int newFlags);

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
	public void print(StringBuilder sb) {
		sb.append("#ref {:hash #hash ");
		sb.append((hash==null)?"nil":hash.toString());
		sb.append(", :flags ");
		sb.append(flags);
		sb.append("}");
	}

	@Override
	public String toString() {
		// TODO. Why protected by a try-catch? Looks like it will never throw.
		StringBuilder sb = new StringBuilder();
		try {
			print(sb);
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
	 * Checks if two Ref Values are equal. Equality is defined as referring to the
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
		return value.getRef();
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T> get(Object value) {
		if (value==null) return (Ref<T>) NULL_VALUE;
		if (value instanceof ACell) return ((ACell)value).getRef();
		return RT.cvm(value).getRef();
	}

	/**
	 * Creates a RefSoft using a specific Hash. Fetches the actual value lazily from the
	 * store on demand.
	 * 
	 * Internal soft reference may be initially empty: This Ref might not have
	 * available data in the store, in which case calls to getValue() may result in
	 * a MissingDataException
	 * 
	 * WARNING: Does not mark as either embedded or non-embedded, as this might be a top level 
	 * entry in the store. isEmbedded() will query the store to determine status.
	 * 
	 * @param hash The hash value for this Ref to refer to
	 * @return Ref for the specific hash.
	 */
	public static <T extends ACell> RefSoft<T> forHash(Hash hash) {
		return RefSoft.createForHash(hash);
	}
	
	public Ref<T> markEmbedded(boolean isEmbedded) {
		int newFlags=mergeFlags(flags,(isEmbedded?KNOWN_EMBEDDED_MASK:NON_EMBEDDED_MASK));
		flags=newFlags;
		return this;
	}
	
	/**
	 * Sets the Flags for this Ref. WARNING: caller must have performed any necessary validation
	 * @param newFlags Flags to set
	 * @return Updated Ref
	 */
	public Ref<T> setFlags(int newFlags) {
		flags=newFlags;
		return this;
	}

	/**
	 * Reads a ref from the given ByteBuffer. Assumes no tag.
	 * 
	 * Marks as non-embedded
	 * 
	 * @param data ByteBuffer containing the data to read at the current position
	 * @return Ref read from ByteBuffer
	 */
	public static <T extends ACell> Ref<T> readRaw(ByteBuffer data) {
		Hash h = Hash.readRaw(data);
		Ref<T> ref=Ref.forHash(h);
		return ref.markEmbedded(false);
	}

	public void validate() throws InvalidDataException {
		if (hash != null) hash.validate();
		// TODO is this sane?
		if (getStatus() < VALIDATED) {
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
		return getStatus() >= PERSISTED;
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
	 * @param noveltyHandler Novelty handler to call (may be null)
	 * @return the persisted Ref 
	 * @throws MissingDataException If the Ref's value does not exist or has been
	 *         garbage collected before being persisted 
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> persist(Consumer<Ref<ACell>> noveltyHandler) {
		int status = getStatus();
		if (status >= PERSISTED) return (Ref<R>) this; // already persisted in some form
		AStore store=Stores.current();
		return (Ref<R>) store.storeRef(this, Ref.PERSISTED,noveltyHandler);
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
	 * Accumulates the set of all unique Refs in the given object.
	 * 
	 * Might stack overflow if nesting is too deep - not for use in on-chain code.
	 * 
	 * @param a Ref or Cell
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
	 * @param refs Array of Refs to update
	 * @param func Ref update function
	 * @return Array of updated Refs
	 */
	public static <T extends ACell> Ref<T>[] updateRefs(Ref<T>[] refs, IRefFunction func) {
		Ref<T>[] newRefs = refs;
		int n = refs.length;
		for (int i = 0; i < n; i++) {
			Ref<T> ref = refs[i];
			@SuppressWarnings("unchecked")
			Ref<T> newRef = (Ref<T>) func.apply(ref);
			if (ref != newRef) {
				// Ensure newRefs is a new copy since we are making at least one change
				if (newRefs == refs) {
					newRefs = refs.clone();
				}
				newRefs[i] = newRef;
			}
		}
		return newRefs;
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
	 * @param store Store to add to
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
	public final boolean isEmbedded() {
		if ((flags&KNOWN_EMBEDDED_MASK)!=0) return true; 
		if ((flags&NON_EMBEDDED_MASK)!=0) return false;
		boolean em= Format.isEmbedded(getValue());
		flags=flags|(em?KNOWN_EMBEDDED_MASK:NON_EMBEDDED_MASK);
		return em;
	}

	/**
	 * Converts this Ref to a RefDirect
	 * @return Direct Ref
	 */
	public Ref<T> toDirect() {
		return RefDirect.create(getValue(), hash, flags);
	}

	/**
	 * Persists a Ref shallowly in the current store.
	 * 
	 * Status will be updated to STORED or higher.
	 * 
	 * @return Ref with status of STORED or above
	 */
	public <R extends ACell> Ref<R> persistShallow() {
		return persistShallow(null);
	}
	
	/**
	 * Persists a Ref shallowly in the current store.
	 * 
	 * Status will be updated STORED or higher. Novelty handler will be called exactly once if and only if
	 * the ref was not previously stored
	 * 
	 * @param noveltyHandler Novelty handler to call (may be null)
	 * @return Ref with status of STORED or above
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> persistShallow(Consumer<Ref<ACell>> noveltyHandler) {
		AStore store=Stores.current();
		return (Ref<R>) store.storeTopRef((Ref<ACell>)this, Ref.STORED, noveltyHandler);
	}

	/**
	 * Updates the value stored within this Ref. New value must be equal in value to the old value 
	 * (identical hash), but may have updated internal refs etc.
	 * 
	 * @param newValue New value
	 * @return Updated Ref
	 */
	public abstract Ref<T> withValue(T newValue);
	
	/**
	 * Writes the ref to a byte array. Embeds embedded values as necessary.
	 * @param bs Byte array to encode to
	 * @return Updated position
	 */
	@Override
	public final int encode(byte[] bs, int pos) {
		if (isEmbedded()) {
			T value=getValue();
			if (value==null) {
				bs[pos++]=Tag.NULL;
				return pos;
			}
			return value.encode(bs, pos);
		} else {
			bs[pos++]=Tag.REF;
			return getHash().encodeRaw(bs, pos);
		}
	}
	
	@Override
	public final ByteBuffer write(ByteBuffer bb) {
		if (isEmbedded()) {
			return Format.write(bb, getValue());
		} else {
			bb=bb.put(Tag.REF);
			return getHash().writeToBuffer(bb);
		}
	}
	
	@Override
	protected Blob createEncoding() {
		if (isEmbedded()) {
			return Format.encodedBlob(getValue());
		}
		
		byte[] bs=new byte[Ref.INDIRECT_ENCODING_LENGTH];	
		Hash h=getHash();
		int pos=0;
		bs[pos++]=Tag.REF;
		pos=h.encodeRaw(bs, pos);
		return Blob.wrap(bs,0,pos);
	}

	/**
	 * Gets the encoding length for writing this Ref. Will be equal to the encoding length
	 * of the Ref's value if embedded, otherwise INDIRECT_ENCODING_LENGTH
	 *  
	 * @return Exact length of encoding
	 */
	public final long getEncodingLength() {
		if (isEmbedded()) {
			T value=getValue();
			if (value==null) return 1;
			return value.getEncodingLength();
		} else {
			return Ref.INDIRECT_ENCODING_LENGTH;
		}
	}

	/**
	 * Gets the indirect memory size for this Ref
	 * @return 0 for fully embedded values with no child refs, memory size of referred value otherwise
	 */
	public long getMemorySize() {
		T value=getValue();
		if (value==null) return 0;
		return value.getMemorySize();
	}

	/**
	 * Finds all instances of missing data in this Ref, and adds them to the missing set
	 * @param missingSet Set to add missing instances to
	 */
	public void findMissing(HashSet<Hash> missingSet) {
		if (getStatus()>=Ref.PERSISTED) return;
		if (isMissing()) {
			missingSet.add(getHash());
		} else {
			// Should be OK to get value, since non-missing!
			T val=getValue();
			
			// TODO: maybe needs to be non-stack-consuming?
			// recursively scan for missing children
			int n=val.getRefCount();
			for (int i=0; i<n; i++) {
				Ref<?> r=val.getRef(i);
				r.findMissing(missingSet);
			}
		}
	}

	/**
	 * Checks if this Ref refers to missing data, i.e. a Cell that does not exist in the
	 * currect store.
	 * 
	 * @return true if this specific Ref has missing data, false otherwise.
	 */
	public abstract boolean isMissing();

	/**
	 * Merges flags in an idempotent way. Assume flags are valid
	 * @param a First set of flags
	 * @param b Second set of flags
	 * @return Merged flags
	 */
	public static int mergeFlags(int a, int b) {
		return ((a|b)&~STATUS_MASK)|Math.max(a&STATUS_MASK, b& STATUS_MASK);
	}



}
